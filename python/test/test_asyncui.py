"""
What happens when somebody closes the progress window.

The rest of `asyncui` is exercised by every run of the wizard; cancelling is the path nobody
takes deliberately and everybody takes by accident, and it is the one that had nothing watching
it. Two things have to be true of it, and neither was:

- **The wait has to end.** The progress window is the only thing being polled, so once it is gone
  nothing is left to notice the coroutine finishing - and a main loop still running at that point
  is a wizard stuck on "Reading your accessories…" with no window to close.
- **A question asked afterwards has to be refused.** The worker thread does not stop when the
  window does; a sign-in half way through will still ask for a verification code, and drawing
  that dialog after the user has cancelled is asking about work they said to abandon.

Tk is needed for all of it, and CI has none - see `test_codes.py`. These skip there rather than
being written without a display, because what is being tested *is* the event loop.
"""

from __future__ import annotations

import asyncio
import queue
import threading
import time

import pytest

tk = pytest.importorskip("tkinter")

from exporter.asyncui import Cancelled, run_with_progress  # noqa: E402 - after the skip above

# How long a test waits before deciding the main loop is never coming back. Long enough that a
# slow machine is not called a hang; short enough that a suite with the bug still finishes.
_GIVE_UP_MS = 5000


@pytest.fixture
def root():
    """A hidden application window, or a skip if this machine cannot draw one."""
    try:
        window = tk.Tk()
    except tk.TclError as e:
        pytest.skip(f"no display to draw on: {e}")

    window.withdraw()
    yield window

    try:
        window.destroy()
    except tk.TclError:
        pass


def close_the_progress_window(root) -> None:
    """Do what the user does: close the window the progress bar is in."""
    for child in root.winfo_children():
        if isinstance(child, tk.Toplevel):
            child.destroy()


class TestCancelling:
    def test_closing_the_progress_window_ends_the_wait(self, root):
        """
        The whole bug, and the reason for the watchdog below.

        `Cancelled` was recorded but the main loop was never asked to stop, so `run_with_progress`
        sat inside `mainloop()` for ever. A test that just called it would hang rather than fail,
        which reports nothing - so a timeout quits the loop and is then asserted on.
        """
        finish = threading.Event()
        timed_out = []

        async def never_finishes(_asker):
            while not finish.is_set():
                await asyncio.sleep(0.01)
            return "not the result anybody gets"

        root.after(200, lambda: close_the_progress_window(root))
        root.after(_GIVE_UP_MS, lambda: (timed_out.append(True), root.quit()))

        with pytest.raises(Cancelled):
            run_with_progress(root, "Working…", never_finishes)

        finish.set()
        assert not timed_out, "closing the progress window left the main loop running"

    def test_a_question_asked_after_cancelling_is_refused(self, root):
        """
        The worker does not stop when the window does.

        Whatever it was doing runs on, and a sign-in asks for a verification code half way
        through. That dialog appearing after the user closed the window is a question about work
        they have already abandoned - and answering it would carry on the flow.
        """
        drawn = []
        released = threading.Event()
        outcome: queue.Queue = queue.Queue(maxsize=1)

        async def asks_after_it_was_cancelled(asker):
            # Held until the window has gone, so this asks at exactly the moment being tested.
            released.wait(timeout=5)
            try:
                asker.ask(lambda: drawn.append("a dialog"))
                outcome.put("drew a dialog")
            except BaseException as e:  # noqa: BLE001 - the type is what is being asserted
                outcome.put(type(e).__name__)

        root.after(200, lambda: close_the_progress_window(root))
        root.after(_GIVE_UP_MS, root.quit)

        with pytest.raises(Cancelled):
            run_with_progress(root, "Working…", asks_after_it_was_cancelled)

        released.set()

        # Anything the worker hands to the main thread runs in an event loop, so one has to be
        # turning for the unfixed version to get its dialog drawn. This is that loop.
        deadline = time.monotonic() + 5
        while outcome.empty() and time.monotonic() < deadline:
            root.update()
            time.sleep(0.01)

        assert outcome.get_nowait() == "Cancelled"
        assert drawn == [], "a dialog was drawn after the user cancelled"


class TestFinishing:
    def test_the_result_comes_back_and_the_window_goes_away(self, root):
        async def works(_asker):
            await asyncio.sleep(0.01)
            return "done"

        assert run_with_progress(root, "Working…", works) == "done"
        assert not [child for child in root.winfo_children() if isinstance(child, tk.Toplevel)]

    def test_a_failure_is_raised_on_the_main_thread(self, root):
        """So the caller reports it like any other failure, rather than losing it in a thread."""
        async def fails(_asker):
            raise ValueError("Apple said no")

        with pytest.raises(ValueError, match="Apple said no"):
            run_with_progress(root, "Working…", fails)

    def test_a_question_is_answered_on_the_main_thread(self, root):
        threads = {}

        async def asks(asker):
            threads["worker"] = threading.current_thread().name
            return asker.ask(lambda: threading.current_thread().name)

        assert run_with_progress(root, "Working…", asks) == threading.current_thread().name
        assert threads["worker"] != threading.current_thread().name
