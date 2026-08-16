"""
Rendering Apple's terms of service so they can be read in a terminal.

An account with unaccepted iCloud terms fails the delegate exchange that signing in ends with, and
Apple offers exactly two places to accept: one of its own devices, or iCloud.com. Someone using
this project generally has neither - which is the whole reason for showing them here.

**What arrives is a web page**, and dumping its tags at someone is not showing them anything. This
turns it into text with its structure intact: headings that read as headings, paragraphs wrapped to
the terminal, and lists that look like lists. It is the document, legibly, not a summary of it -
nothing here shortens, reorders or omits, because what is displayed is what gets agreed to.

The rendering lives apart from the asking so the windowed exporter can show the same text in a
scrolling box.
"""

from __future__ import annotations

import shutil
import textwrap

import bs4

# Wide enough to read, narrow enough that a line does not span a maximised terminal. Prose stops
# being readable somewhere around here regardless of how much room there is.
_MAX_WIDTH = 88
_MIN_WIDTH = 40

_HEADINGS = ("h1", "h2", "h3", "h4", "h5", "h6")
_BLOCKS = (*_HEADINGS, "p", "li", "tr", "div", "blockquote", "pre")


def terminal_width() -> int:
    """How wide to wrap, from the terminal if it will say and a sane default if not."""
    return max(_MIN_WIDTH, min(_MAX_WIDTH, shutil.get_terminal_size((_MAX_WIDTH, 24)).columns - 2))


def render(html: str, width: int | None = None) -> str:
    """
    Turn one terms document into wrapped plain text.

    :param html: The document, as Apple sent it.
    :param width: Columns to wrap at. Taken from the terminal when not given.
    """
    width = width or terminal_width()
    soup = bs4.BeautifulSoup(html, features="html.parser")

    # Neither is content, and both read as gibberish if their text is taken.
    for unwanted in soup(["script", "style"]):
        unwanted.decompose()

    # A line break is the one inline tag that carries meaning as whitespace, and extracting text
    # without it would run two lines of an address into one word.
    for line_break in soup.find_all("br"):
        line_break.replace_with("\n")

    blocks: list[str] = []
    for element in soup.find_all(_BLOCKS):
        # Only the innermost block of a nest, or a wrapping <div> repeats everything inside it.
        if element.find(_BLOCKS):
            continue

        # Joined with nothing rather than with a space: the source's own spacing already sits
        # inside the text nodes, and a separator inserts one where the markup did not have one -
        # `Welcome to <b>iCloud</b>.` becomes "iCloud ." That is the sort of thing that makes a
        # rendered contract look mangled, so the whitespace is collapsed afterwards instead.
        #
        # Collapsed a line at a time, because collapsing the whole block would undo the line
        # breaks put back above and run an address onto one line.
        lines = [" ".join(line.split()) for line in element.get_text("").split("\n")]
        lines = [line for line in lines if line]
        if not lines:
            continue

        blocks.append(_block(element.name, lines, width))

    if not blocks:
        # A document this cannot parse is still a document somebody has to read, so it is shown
        # flat rather than swallowed.
        flat = " ".join(soup.get_text(" ", strip=True).split())
        blocks = [textwrap.fill(flat, width=width)] if flat else []

    return "\n\n".join(blocks)


def _block(tag: str, lines: list[str], width: int) -> str:
    """Render one block, in a shape that says what it is without any markup surviving."""
    if tag in _HEADINGS:
        # Underlined rather than marked up: it has to read as a heading in a plain terminal, and
        # a row of dashes is the one convention that does that everywhere.
        heading = textwrap.fill(" ".join(lines).upper(), width=width)
        return f"{heading}\n{'-' * min(width, max(len(line) for line in heading.splitlines()))}"

    if tag == "li":
        return textwrap.fill(
            " ".join(lines),
            width=width,
            initial_indent="  - ",
            subsequent_indent="    ",
        )

    # Each source line wrapped on its own, so a line break in the document is a line break here.
    return "\n".join(textwrap.fill(line, width=width) for line in lines)


def summarise(page_id: str, html: str) -> str:
    """A one-line description of a document, for a listing or a confirmation."""
    words = len(render(html, width=_MAX_WIDTH).split())

    return f"{page_id} ({words:,} words)"
