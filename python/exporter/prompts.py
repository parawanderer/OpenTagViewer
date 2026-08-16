"""
Asking the user things, with arrow keys and checkboxes where there is a terminal to draw on.

Typing `1 3 4` at a numbered list works and is tedious, and it gets worse the more tags somebody
has. So where there is a real terminal this draws the list and lets them move through it; where
there is not, it falls back to exactly the numbered prompt it replaced.

> **The fallback is chosen up front, not after a failure.** With no terminal attached,
> prompt_toolkit does not raise - it waits for keystrokes that are never coming, and the program
> hangs with no output. So :func:`interactive` is consulted *before* anything is drawn, and a
> pipe, a CI job or a redirect takes the plain path by decision rather than by accident.

Everything here is async, because it is called from inside a running event loop - the export flow
is asynchronous end to end. questionary's synchronous API starts its own loop and cannot be used
from within one.
"""

from __future__ import annotations

import getpass
import sys
from dataclasses import dataclass
from typing import Any, Sequence

import questionary
import wcwidth

# Muted, so the questions read as questions rather than as decoration. Nothing here depends on
# colour: every state that matters is also indicated by a symbol or by position.
_STYLE = questionary.Style([
    ("qmark", "fg:#5f819d bold"),
    ("question", "bold"),
    ("answer", "fg:#5f819d"),
    ("pointer", "fg:#5f819d bold"),
    ("highlighted", "fg:#5f819d bold"),
    ("selected", "fg:#5f819d"),
    ("instruction", "fg:#808080 italic"),
])


@dataclass(frozen=True)
class Option:
    """One thing that can be picked, and what picking it means."""

    label: str
    value: Any
    note: str = ""
    """Shown after the label, for something worth knowing before choosing rather than after."""

    @property
    def shown(self) -> str:
        return f"{self.label}  {self.note}" if self.note else self.label

    def padded(self, width: int) -> str:
        """
        The same, with the note aligned into a column across every option.

        The gap is padding to `width` plus a fixed two spaces. A `max(1, ...)` floor on the padding
        looks harmless and is not: it pushes the *longest* label one column further than every
        other, which is the one row that had no padding to give.
        """
        if not self.note:
            return self.label

        return f"{self.label}{' ' * max(0, width - display_width(self.label))}  {self.note}"


def display_width(text: str) -> int:
    """
    How many terminal columns some text occupies.

    Not `len`. An emoji is one character and two columns wide, and tag names are full of them - so
    padding by length lines the notes up on a list of plain names and stairsteps them the moment
    somebody has a 🎒 on one tag and nothing on another.
    """
    return sum(max(0, wcwidth.wcwidth(character)) for character in text)


def _aligned(options: Sequence[Option]) -> list[str]:
    """Render every option with its note in the same column."""
    width = max((display_width(option.label) for option in options), default=0)

    return [option.padded(width) for option in options]


class Abandoned(Exception):
    """Raised when the user pressed Ctrl-C at a prompt rather than answering it."""


def interactive() -> bool:
    """
    Whether there is a terminal to draw on, at both ends.

    Both, because drawing needs to write *and* to read raw keystrokes: output redirected to a file
    with input still on the terminal would paint the list into the file.
    """
    return sys.stdin.isatty() and sys.stdout.isatty()


async def select(question: str, options: Sequence[Option]) -> Any:
    """Pick exactly one. Arrow keys where possible, a numbered list where not."""
    if not options:
        raise ValueError("Nothing to choose between.")

    if not interactive():
        return _numbered(question, options, multiple=False)[0]

    shown = _aligned(options)
    answer = await questionary.select(
        question,
        choices=[
            questionary.Choice(title=title, value=option.value)
            for title, option in zip(shown, options)
        ],
        style=_STYLE,
        instruction="(arrow keys, enter to choose)",
    ).ask_async()

    return _answered(answer)


async def checkbox(question: str, options: Sequence[Option], instruction: str = "") -> list[Any]:
    """
    Pick any number, including none.

    **Nothing is selected to begin with**, deliberately. Exporting a tag cannot be undone, and a
    list that arrives pre-ticked turns "hand over one tag" and "hand over everything I own" into
    the same keystroke.
    """
    if not options:
        raise ValueError("Nothing to choose between.")

    if not interactive():
        return _numbered(question, options, multiple=True)

    shown = _aligned(options)
    answer = await questionary.checkbox(
        question,
        choices=[
            questionary.Choice(title=title, value=option.value)
            for title, option in zip(shown, options)
        ],
        style=_STYLE,
        instruction=instruction or "(space to select, a for all, enter to confirm)",
    ).ask_async()

    return _answered(answer)


async def text(question: str, default: str = "") -> str:
    """Ask for a line of text."""
    if not interactive():
        print(f"{question}{f' [{default}]' if default else ''} ", end="", file=sys.stderr, flush=True)
        return input().strip() or default

    answer = await questionary.text(question, default=default, style=_STYLE).ask_async()

    return _answered(answer).strip()


async def password(question: str) -> str:
    """
    Ask for something that must not appear on screen.

    getpass either way: it is the one prompt where falling back to `input()` would echo a password
    into a terminal's scrollback, so the non-interactive path has to hide it too.
    """
    if not interactive():
        return getpass.getpass(f"{question} ")

    answer = await questionary.password(question, style=_STYLE).ask_async()

    return _answered(answer)


async def confirm(question: str, default: bool = False) -> bool:
    """Ask a yes or no question."""
    if not interactive():
        print(f"{question} [{'Y/n' if default else 'y/N'}] ", end="", file=sys.stderr, flush=True)
        typed = input().strip().lower()
        return default if not typed else typed in ("y", "yes")

    answer = await questionary.confirm(question, default=default, style=_STYLE).ask_async()

    return _answered(answer)


def _answered(answer: Any) -> Any:
    """
    Turn questionary's "the user pressed Ctrl-C" into an exception.

    It returns None for that rather than raising, which is easy to carry on from by accident -
    and carrying on from an unanswered question about which tags to export is the worst possible
    place to guess.
    """
    if answer is None:
        raise Abandoned("Stopped at a prompt.")

    return answer


def _numbered(question: str, options: Sequence[Option], *, multiple: bool) -> list[Any]:
    """
    The plain path: print a numbered list and read numbers back.

    Kept working rather than kept around. A pipe, a CI job and a terminal that cannot draw all end
    up here, and this is also the only path anything can test without a pseudo-terminal.
    """
    for number, shown in enumerate(_aligned(options), start=1):
        print(f"  {number}. {shown}", file=sys.stderr)

    if multiple:
        print("\nNumbers separated by spaces, or 'all'.", file=sys.stderr)

    while True:
        print(f"{question} > ", end="", file=sys.stderr, flush=True)
        typed = input().strip().lower()

        if multiple and typed == "all":
            return [option.value for option in options]

        wanted = typed.split() if multiple else [typed]
        chosen = []
        for part in wanted:
            if part.isdigit() and 1 <= int(part) <= len(options):
                chosen.append(options[int(part) - 1].value)
            else:
                print(f"  {part!r} is not one of the numbers above.", file=sys.stderr)
                chosen = []
                break

        if chosen:
            return chosen
