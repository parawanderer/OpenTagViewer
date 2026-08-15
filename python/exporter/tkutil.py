"""
Small Tk things that every window here needs and Tk does not do on its own.

Placing one, mostly. A window with no explicit position opens at the top-left corner of the
screen, wedged into the menu bar - which is not a choice anybody made, it is simply what happens
when nothing says otherwise.
"""

from __future__ import annotations

import tkinter as tk

# A third of the way down rather than dead centre: a window centred vertically sits lower than the
# eye expects, because people read the top of a screen first.
_VERTICAL_BIAS = 3


def centre_on_screen(window: tk.Tk | tk.Toplevel) -> None:
    """
    Put a window in the middle of the screen it opens on.

    `update_idletasks` first, because a window that has not been laid out yet reports a size of
    1x1 and would be centred as though it were a single pixel.
    """
    window.update_idletasks()

    width = window.winfo_width()
    height = window.winfo_height()

    x = max(0, (window.winfo_screenwidth() - width) // 2)
    y = max(0, (window.winfo_screenheight() - height) // _VERTICAL_BIAS)

    window.geometry(f"+{x}+{y}")


def centre_over(window: tk.Tk | tk.Toplevel, parent: tk.Misc) -> None:
    """
    Put a dialog over the window that opened it.

    Over its parent rather than over the screen: a dialog that appears somewhere else makes the
    person look for it, and on a second monitor it can appear on the wrong one entirely.

    Falls back to the screen if the parent has no position yet - which happens when a dialog is
    the first thing a window shows.
    """
    window.update_idletasks()

    if parent.winfo_width() <= 1:
        centre_on_screen(window)
        return

    x = parent.winfo_rootx() + (parent.winfo_width() - window.winfo_width()) // 2
    y = parent.winfo_rooty() + (parent.winfo_height() - window.winfo_height()) // _VERTICAL_BIAS

    window.geometry(f"+{max(0, x)}+{max(0, y)}")
