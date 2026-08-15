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

    def ask(self, dialog: Callable[[], Any]) -> Any:
        """
        Run `dialog` on the main thread and return what it returned.

        :param dialog: Called with no arguments, on the main thread. Anything Tk.
        """
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
    window = _progress_window(root, message)
    asker = Asker(root)
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
                window.after(_POLL_MS, _poll)
            else:
                result.append((False, Cancelled()))
            return

        if window.winfo_exists():
            window.destroy()
        root.quit()

    window.after(_POLL_MS, _poll)
    root.mainloop()

    succeeded, value = result[0]
    if not succeeded:
        raise value
    return value


def _progress_window(root: tk.Tk, message: str) -> tk.Toplevel:
    """A small modal window with an indeterminate bar, since none of this reports progress."""
    window = tk.Toplevel(root)
    window.title("Working")
    window.resizable(width=False, height=False)
    window.transient(root)

    frame = ttk.Frame(window, padding=16)
    frame.pack(fill="both", expand=True)

    ttk.Label(frame, text=message, wraplength=320).pack(pady=(0, 12))

    bar = ttk.Progressbar(frame, mode="indeterminate", length=320)
    bar.pack()
    bar.start(12)

    window.grab_set()
    window.update_idletasks()

    return window
