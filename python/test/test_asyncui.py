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
import gc
import queue
import threading
import time

import pytest

tk = pytest.importorskip("tkinter")

from exporter.asyncui import Cancelled, run_with_progress  # noqa: E402 - after the skip above

# How long a test waits before deciding the main loop is never coming back. Long enough that a
# slow machine is not called a hang; short enough that a suite with the bug still finishes.
_GIVE_UP_MS = 5000

# How long teardown waits for a worker to finish before giving up on it. See `_let_workers_finish`.
_JOIN_TIMEOUT = 5


def _let_workers_finish() -> None:
    """
    Wait for the threads a test started, before anything Tk is destroyed or collected.

    **Not tidiness - the suite aborts without it.** `run_with_progress` runs its coroutine on a
    daemon thread and never joins it, which is right for the program: the thread may be half way
    through a network call and the window must not wait for it. In a test it means the *next* test
    can start while this one's worker is still exiting, and a garbage collection that lands on
    that thread frees Tk objects from a thread Tcl was not created on. Tcl aborts the process for
    that rather than raising, so it arrives as SIGABRT in whichever test happened to be running.

    Which is exactly what CI caught: `Fatal Python error: Aborted`, in `Garbage-collecting`, on a
    thread belonging to a test that had already passed.
    """
    for thread in threading.enumerate():
        if thread is not threading.main_thread() and thread.is_alive():
            thread.join(_JOIN_TIMEOUT)


@pytest.fixture
def root():
    """A hidden application window, or a skip if this machine cannot draw one."""
    try:
        window = tk.Tk()
    except tk.TclError as e:
        pytest.skip(f"no display to draw on: {e}")

    window.withdraw()
    started_with = set(threading.enumerate())

    yield window

    _let_workers_finish()
    leftover = [
        thread for thread in threading.enumerate()
        if thread not in started_with and thread.is_alive()
    ]

    try:
        window.destroy()
    except tk.TclError:
        pass

    # On the main thread, deliberately: it is the one Tcl was created on, and leaving this to
    # whenever CPython feels like it is what puts a widget's finalizer on a worker.
    gc.collect()

    # Said out loud rather than left to chance. A test that ends with its worker still running is
    # not this test's problem - it is the *next* one's, as an abort with an unrelated name on it.
    assert not leftover, f"a worker outlived the test that started it: {leftover}"


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

        try:
            with pytest.raises(Cancelled):
                run_with_progress(root, "Working…", never_finishes)
        finally:
            # In a `finally` because the worker loops until it is set: a failed assertion that
            # skipped this would leave a thread running into the next test, which is the way this
            # file aborts a whole suite rather than failing one case.
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

        try:
            with pytest.raises(Cancelled):
                run_with_progress(root, "Working…", asks_after_it_was_cancelled)
        finally:
            released.set()  # as above: the worker is blocked on it, and teardown joins the worker

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
