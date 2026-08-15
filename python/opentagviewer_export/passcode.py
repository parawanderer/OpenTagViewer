"""
The code that unlocks an encrypted bundle.

**Why a code at all.** A bundle holds key material that cannot be revoked: the only way to
withdraw an exported accessory is to unpair it, so anyone holding the file can locate that tag
indefinitely. The file is then sent through a mail account, a chat app or a cloud drive, and it
outlives the conversation - it sits in a backup for years after everyone has forgotten it is
there. Encrypting it is worth real money against that, which is the case it is designed for.

**It is not protection against someone who deliberately keeps the file**, and the length is why.
The zip format derives its key with PBKDF2-HMAC-SHA1 at 1000 iterations, which is fixed by the
format and cannot be raised. A six-digit PIN is a million candidates and falls in seconds; no
better key-derivation function rescues it, because even a punishing one leaves a million guesses
tractable. Length is the only lever, so this generates twelve characters rather than asking for a
memorable number.

**And it has to travel separately from the file.** A code sent in the same chat as the bundle is
in the same backup as the bundle. Whatever presents this to a user should say so.
"""

from __future__ import annotations

import secrets

PASSCODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
"""
Crockford's base32: the digits and the alphabet minus `I`, `L`, `O` and `U`.

The first three are dropped because they are misread as `1`, `1` and `0` - and this is written on
paper and read back by a second person, which is exactly the case that goes wrong. `U` is dropped
so a random code cannot spell something unfortunate.
"""

PASSCODE_LENGTH = 12
"""
Twelve characters of the alphabet above: about sixty bits, which closes the offline attack.

Not a round number chosen for comfort - see the module docstring for why a short code cannot be
made safe by any amount of key stretching.
"""

_GROUP = 4

# What a person might type instead. Crockford's own decoding rules, which exist for this reason:
# the excluded letters are excluded *because* they are confusable, so they are accepted on input
# and folded onto what they were mistaken for.
_CONFUSABLE = {"I": "1", "L": "1", "O": "0"}

_SEPARATORS = " -_\t\r\n"


class PasscodeError(ValueError):
    """Raised when what was typed cannot be read as a code."""


def generate_passcode(length: int = PASSCODE_LENGTH) -> str:
    """
    Generate a code, undelimited and uppercase.

    This exact string is the password: the grouping :func:`format_passcode` adds is for reading,
    and :func:`normalise_passcode` takes it back off. A caller that shows one form and encrypts
    with another produces a bundle nobody can open.
    """
    if length < 1:
        raise ValueError("A passcode needs at least one character.")

    return "".join(secrets.choice(PASSCODE_ALPHABET) for _ in range(length))


def format_passcode(passcode: str, group: int = _GROUP) -> str:
    """
    Group a code for a human to write down: `H4K29WMR7TQX` becomes `H4K2-9WMR-7TQX`.

    For display only. The hyphens are not part of the password.
    """
    if group < 1:
        raise ValueError("A group needs at least one character.")

    return "-".join(passcode[i:i + group] for i in range(0, len(passcode), group))


def normalise_passcode(typed: str) -> str:
    """
    Turn what someone typed into the exact string the bundle was encrypted with.

    Accepts the grouped form, any spacing, lower case, and the confusable letters the alphabet
    leaves out - `O` for `0`, `I` or `L` for `1`. That last part is the point: those letters are
    excluded from the alphabet *because* people write them for the digits, so a code read off
    paper has to survive the substitution.

    **This is an interoperability contract, not a convenience.** Whatever opens a bundle - here,
    or the Android importer - must normalise identically, because the password is compared as
    bytes and a difference of one character reads as the wrong code.

    :raises PasscodeError: If what is left holds anything outside the alphabet.
    """
    cleaned = "".join(character for character in typed.upper() if character not in _SEPARATORS)
    cleaned = "".join(_CONFUSABLE.get(character, character) for character in cleaned)

    if not cleaned:
        raise PasscodeError("No code was typed.")

    unexpected = sorted({character for character in cleaned if character not in PASSCODE_ALPHABET})
    if unexpected:
        raise PasscodeError(
            f"A code is made only of {PASSCODE_ALPHABET}, and this one holds {', '.join(unexpected)}.",
        )

    return cleaned
