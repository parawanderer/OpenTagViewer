"""
What counts as a verification code, for both entry points.

Apple sends six digits. People paste them with a space or a hyphen in the middle, because that is
how the notification shows them, so taking the digits out of whatever was typed is kinder than
rejecting the paste.

**Checking before sending is not politeness, it is arithmetic.** A rejected code is not a free
question: Apple has to send another one, and the person has to wait for it. Catching "12345" here
costs nothing and saves that round trip.

This lives in a module of its own rather than in `wizard.py`, where it started, because the CLI
needs the same answer and importing the wizard means importing tkinter. That is not hypothetical -
it broke CI, whose Python has no `_tkinter` at all, and it would break the same way on any
headless machine somebody runs the CLI on.
"""

from __future__ import annotations

VERIFICATION_CODE_LENGTH = 6


def verification_code(typed: str) -> str:
    """
    Read a verification code out of whatever was typed.

    Apple sends six digits, and people paste them with a space or a hyphen in the middle because
    that is how the notification shows them. Taking the digits is kinder than rejecting the paste.
    """
    return "".join(character for character in typed if character.isdigit())


def is_verification_code(typed: str) -> bool:
    """Whether that is a code worth sending. Apple's are six digits."""
    return len(verification_code(typed)) == VERIFICATION_CODE_LENGTH
