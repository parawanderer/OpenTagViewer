"""
Running the async half of the exporter from a tkinter callback.

FindMy.py is asynchronous and tkinter is not, and the naive join - `asyncio.run` inside a button
handler - freezes the window for the whole of a sign-in. On macOS that is not merely ugly: a
window that stops answering events gets the spinning cursor and, after long enough, an offer to
force-quit the application in the middle of a network call.

So the coroutine runs on a worker thread and the main thread keeps drawing. Everything Tk touches
stays on the main thread, because Tk is not thread-safe and calling into it from a worker fails in
ways that look like anything but a threading bug.

**The prompts a flow needs come back the other way.** A sign-in has to ask for a verification code
half way through, and that question has to be asked on the main thread. :func:`run_with_progress`
takes a coroutine that receives an `Asker` for exactly that: it blocks the worker, hops the
question to the main thread, and hands back what was typed.
"""

from __future__ import annotations

import asyncio
import queue
import threading
import tkinter as tk
from tkinter import ttk
from typing import Any, Callable, Coroutine

from exporter.tkutil import centre_over

# How often the main thread looks at the worker. Fast enough to feel immediate, slow enough not to
# be a busy loop.
_POLL_MS = 50


class Asker:
    """
    Asks the user something, from a worker thread.

    Handed to a coroutine so it can prompt mid-flow. Every call blocks the worker until the main
    thread has drawn a dialog and the user has answered it.
    """

    def __init__(self, root: tk.Misc) -> None:
        self._root = root
        self._cancelled = threading.Event()
        self._say: Callable[[str], None] | None = None

    def attach_status(self, say: Callable[[str], None]) -> None:
        """Wire `say` to the progress window's label. Called by :func:`run_with_progress`."""
        self._say = say

    def say(self, text: str) -> None:
        """
        Change what the progress window says, from the worker thread.

        **A window that has said "Signing in to iCloud…" for a minute is indistinguishable from a
        hung one**, and that matters here because waiting is now something this deliberately does:
        after Apple takes a verification code and then fails, the recovery is to wait and ask for
        a new one. A silent minute in the middle of a sign-in is exactly when somebody force-quits.

        Unlike :meth:`ask` this does not block and does not raise when cancelled - it is a status
        line, and a caller should not have to handle a window closing to write to one.
        """
        if self._say is None or self._cancelled.is_set():
            return

        say = self._say
        self._root.after(0, lambda: say(text))

    def cancel(self) -> None:
        """
        Stop answering questions, because the user closed the window.

        Called from the main thread; read from the worker. The worker does not stop when the
        window does - a sign-in half way through still wants a verification code - and that
        dialog is a question about work the user has already abandoned.
        """
        self._cancelled.set()

    def ask(self, dialog: Callable[[], Any]) -> Any:
        """
        Run `dialog` on the main thread and return what it returned.

        :param dialog: Called with no arguments, on the main thread. Anything Tk.
        :raises Cancelled: If the user has closed the progress window. Raised on the worker
            thread, where it unwinds whatever was in progress instead of drawing over a window
            that is no longer there.
        """
        if self._cancelled.is_set():
            raise Cancelled

        answer: queue.Queue = queue.Queue(maxsize=1)

        def _on_main_thread() -> None:
            try:
                answer.put((True, dialog()))
            except BaseException as e:  # noqa: BLE001 - carried across the thread, not swallowed
                answer.put((False, e))

        self._root.after(0, _on_main_thread)

        succeeded, value = answer.get()
        if not succeeded:
            raise value
        return value


class Cancelled(Exception):
    """Raised in place of a result when the user closed the progress window."""


def run_with_progress(
    root: tk.Tk,
    message: str,
    make_coroutine: Callable[[Asker], Coroutine[Any, Any, Any]],
) -> Any:
    """
    Run a coroutine on a worker thread while a modal progress window turns.

    :param root: The application window.
    :param message: What to say while it runs.
    :param make_coroutine: Given an :class:`Asker`, returns the coroutine to run.
    :returns: Whatever the coroutine returned.
    :raises Cancelled: If the user closed the progress window.
    :raises Exception: Whatever the coroutine raised, on the main thread, so a caller can report it
        the way it would report any other failure.
    """
    window, label = _progress_window(root, message)
    asker = Asker(root)

    # So the worker can say what it is doing, rather than leaving one sentence up for a minute.
    # Guarded on the widget still existing: the worker keeps going after the window is closed.
    asker.attach_status(_status_setter(label))
    outcome: queue.Queue = queue.Queue(maxsize=1)

    def _work() -> None:
        try:
            outcome.put((True, asyncio.run(make_coroutine(asker))))
        except BaseException as e:  # noqa: BLE001 - re-raised on the main thread below
            outcome.put((False, e))

    worker = threading.Thread(target=_work, daemon=True)
    worker.start()

    # The main thread waits here, but inside Tk's own event loop rather than instead of it: the
    # window keeps drawing, the progress bar keeps moving, and any dialog the worker asks for can
    # still be shown.
    result: list[Any] = []

    def _poll() -> None:
        try:
            result.append(outcome.get_nowait())
        except queue.Empty:
            if window.winfo_exists():
                root.after(_POLL_MS, _poll)
                return

            # The user closed it. Nothing will poll again and nothing will call `quit` later, so
            # the wait ends here - and the worker is told, so the next thing it asks is refused
            # rather than drawn over a window that has gone.
            asker.cancel()
            result.append((False, Cancelled()))
            root.quit()
            return

        if window.winfo_exists():
            window.destroy()
        root.quit()

    # On `root`, not on `window`, and that is the difference between cancelling and hanging.
    # tkinter registers an `after` callback as a Tcl command owned by the widget it was scheduled
    # on, and destroying a widget deletes its commands - so a poll scheduled on the progress
    # window stops being called at the exact moment it is needed. The branch above that notices
    # the window has gone could never run, and `mainloop` never returned.
    root.after(_POLL_MS, _poll)
    root.mainloop()

    succeeded, value = result[0]
    if not succeeded:
        raise value
    return value


def _status_setter(label: ttk.Label) -> Callable[[str], None]:
    """
    Rewrite the progress window's line, or do nothing once it has gone.

    Its own function rather than a closure inside `run_with_progress`, which flake8 already
    considers as branchy as it is allowed to get - and the guard is the interesting part: the
    worker keeps running after the user closes the window, so this is called on a dead widget in
    the ordinary course of cancelling.
    """
    def say(text: str) -> None:
        if label.winfo_exists():
            label.configure(text=text)

    return say


def _progress_window(root: tk.Tk, message: str) -> tuple[tk.Toplevel, ttk.Label]:
    """
    A small modal window with an indeterminate bar, since none of this reports progress.

    Returns the label as well as the window so the worker can rewrite it - see :meth:`Asker.say`.
    """
    window = tk.Toplevel(root)
    window.title("Working")
    window.resizable(width=False, height=False)
    window.transient(root)

    frame = ttk.Frame(window, padding=16)
    frame.pack(fill="both", expand=True)

    label = ttk.Label(frame, text=message, wraplength=320)
    label.pack(pady=(0, 12))

    bar = ttk.Progressbar(frame, mode="indeterminate", length=320)
    bar.pack()
    bar.start(12)

    centre_over(window, root)
    window.grab_set()
    window.update_idletasks()

    return window, label
