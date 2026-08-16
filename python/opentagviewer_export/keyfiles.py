"""
Read self-generated tags out of whatever file the user has.

There is no one format for these. OpenHaystack writes text files, Macless Haystack writes a JSON
array, FindMy.py writes its own JSON, and plenty of people simply have a list of keys - so this
tries each in turn and, when none of them fits, says what it understands rather than that the file
was wrong.

**Parsing only.** No crypto here: a key is checked for length and encoding, and that is all that
can be checked without deriving a public key from it. Where a file *states* the advertisement key,
it is carried through in :attr:`ParsedTag.advertisement_key` so a caller that has the maths can
confirm the two agree - which is the one check that catches the likeliest mistake, pasting a
public key where the private one belongs. Both are 28 bytes, so nothing here can tell them apart.
"""

from __future__ import annotations

import base64
import binascii
import json
import re
from dataclasses import dataclass, field
from typing import Any

PRIVATE_KEY_LENGTH = 28
"""
Bytes in a SECP224R1 private key, which is what a Find My accessory uses.

`KeyPair.new()` is `secrets.token_bytes(28)`, and an advertisement key is 28 bytes as well - so
this rules out a mistyped key, and not a swapped one.
"""


class KeyFileError(ValueError):
    """Raised when a file holds no tags this understands."""


@dataclass(frozen=True)
class ParsedTag:
    """
    One self-generated tag, read out of a file.

    :param private_keys: Its keys, in the order the file gave them. A tag may carry many: each is
        used at a different moment, and locating it means asking about all of them.
    :param name: What the file called it, if it said. Often nothing - which is why a caller has to
        ask before exporting one.
    :param identifier: What the file called it internally, if it said.
    :param advertisement_key: The public key the file claimed, if it stated one. Not used here;
        carried so a caller can check it against what the private key actually derives to.
    :param source_format: Which reader understood the file, for a message to the user.
    """

    private_keys: list[bytes]
    name: str | None = None
    identifier: str | None = None
    advertisement_key: bytes | None = field(default=None, repr=False)
    source_format: str = "unknown"


# What each reader is called when it is named in an error, and in `source_format`.
_MACLESS_HAYSTACK = "Macless Haystack devices.json"
_FINDMY_CUSTOM = "FindMy.py custom_rolling_key_accessory JSON"
_OPENHAYSTACK_KEYS = "OpenHaystack .keys file"
_BARE_KEYS = "a list of keys, one per line"

_SUPPORTED = (_MACLESS_HAYSTACK, _FINDMY_CUSTOM, _OPENHAYSTACK_KEYS, _BARE_KEYS)

# `Private key: <base64>`, and the same shape for the two public values. OpenHaystack writes these
# with varying capitalisation and spacing, so the label is matched loosely.
_LABELLED = re.compile(r"^\s*([A-Za-z ]+?)\s*:\s*(\S+)\s*$")

_PRIVATE_LABELS = {"private key", "privatekey", "private"}
_ADVERTISEMENT_LABELS = {"advertisement key", "advertisementkey", "adv key", "public key"}
_HASHED_LABELS = {"hashed adv key", "hashed advertisement key", "hashedadvkey"}


def parse_key_file(content: bytes | str, *, filename: str | None = None) -> list[ParsedTag]:
    """
    Read every tag a file holds.

    :param content: The file, as bytes or text.
    :param filename: Its name, if there is one. Used only as a fallback name for a format that
        carries none - an OpenHaystack file is named after the tag and holds nothing else.
    :raises KeyFileError: If no reader understood it. The message names every format that was
        tried, because "this file is not right" is not actionable and "these four are" is.
    """
    text = content.decode("utf-8", errors="replace") if isinstance(content, bytes) else content

    if not text.strip():
        raise KeyFileError("That file is empty.")

    stem = _stem(filename)

    for reader in (_read_json, _read_labelled, _read_bare):
        tags = reader(text, stem)
        if tags:
            return tags

    raise KeyFileError(
        "Nothing in that file looked like a set of accessory keys. What can be read:\n"
        + "\n".join(f"  - {name}" for name in _SUPPORTED)
        + "\n\nA key is 28 bytes, written in base64 or hex.",
    )


def _read_json(text: str, stem: str | None) -> list[ParsedTag]:
    """Read the two JSON shapes, and say which one it was."""
    stripped = text.lstrip()
    if not stripped.startswith(("{", "[")):
        return []

    try:
        document = json.loads(text)
    except json.JSONDecodeError as e:
        raise KeyFileError(
            f"That file starts like JSON but does not parse: {e}. If it came from Macless"
            " Haystack, export it again rather than editing it by hand.",
        ) from None

    if isinstance(document, dict) and document.get("type") == "custom_rolling_key_accessory":
        return [_findmy_custom(document)]

    if isinstance(document, dict) and document.get("type") == "accessory":
        raise KeyFileError(
            "That is an Apple-paired accessory exported by FindMy.py, not a self-generated tag."
            " Accessories from an Apple account come out of the account itself - sign in and pick"
            " them from the list rather than importing them here.",
        )

    devices = document if isinstance(document, list) else [document]
    tags = [
        _macless_haystack(device, stem)
        for device in devices
        if isinstance(device, dict) and "privateKey" in device
    ]

    if not tags and isinstance(document, dict):
        keys = ", ".join(sorted(document)) or "none"
        raise KeyFileError(
            "That JSON has no `privateKey` and no `type`, so it is neither a Macless Haystack"
            f" export nor a FindMy.py one. Keys it does have: {keys}.",
        )

    return tags


def _findmy_custom(document: dict[str, Any]) -> ParsedTag:
    """FindMy.py's own format: hex keys, and a name and identifier it may or may not carry."""
    keys = document.get("private_keys")
    if not isinstance(keys, list) or not keys:
        raise KeyFileError("That FindMy.py accessory carries no `private_keys`.")

    return ParsedTag(
        private_keys=[_key(key, _FINDMY_CUSTOM) for key in keys],
        name=_text(document.get("name")),
        identifier=_text(document.get("identifier")),
        source_format=_FINDMY_CUSTOM,
    )


def _macless_haystack(device: dict[str, Any], stem: str | None) -> ParsedTag:
    """
    Macless Haystack's `devices.json`: base64 keys, with the current one held apart from the rest.

    `additionalKeys` are the earlier ones and `privateKey` the current, which is the order
    FindMy.py's own converter uses - the list is what gets searched, so all of them belong in it.
    """
    additional = device.get("additionalKeys") or []
    if not isinstance(additional, list):
        raise KeyFileError("`additionalKeys` should be a list of keys.")

    keys = [_key(key, _MACLESS_HAYSTACK) for key in [*additional, device["privateKey"]]]

    identifier = device.get("id")

    return ParsedTag(
        private_keys=keys,
        name=_text(device.get("name")) or stem,
        identifier=_text(identifier) if identifier is None else str(identifier),
        source_format=_MACLESS_HAYSTACK,
    )


def _read_labelled(text: str, stem: str | None) -> list[ParsedTag]:
    """
    Read an OpenHaystack-style file of `Label: value` lines.

    .. warning::
        **Written from the format as documented, not verified against a file OpenHaystack wrote.**
        The labels are matched loosely for that reason. If a real export does not read, the labels
        are the first thing to look at - and :func:`parse_key_file` falls through to the
        one-key-per-line reader, which will still find the keys.
    """
    private: bytes | None = None
    advertisement: bytes | None = None
    name: str | None = None

    for line in text.splitlines():
        match = _LABELLED.match(line)
        if not match:
            continue

        label = match.group(1).strip().lower()
        value = match.group(2)

        if label in _PRIVATE_LABELS:
            private = _key(value, _OPENHAYSTACK_KEYS)
        elif label in _ADVERTISEMENT_LABELS:
            advertisement = _key(value, _OPENHAYSTACK_KEYS)
        elif label in _HASHED_LABELS:
            continue  # derived from the advertisement key; nothing here needs it
        elif label == "name":
            name = value

    if private is None:
        return []

    return [
        ParsedTag(
            private_keys=[private],
            name=name or stem,
            # No identifier from the filename. A name is a label and a filename is a fine source
            # for one; an identifier names a file *inside the bundle*, and two tags exported from
            # two `keys.txt` files would collide. A caller derives a stable one from the public key.
            advertisement_key=advertisement,
            source_format=_OPENHAYSTACK_KEYS,
        ),
    ]


def _read_bare(text: str, stem: str | None) -> list[ParsedTag]:
    """
    Read a file that is simply keys, one per line.

    The catch-all, and worth having: a tag's keys reach people as a text file more often than in
    any of the formats above. Blank lines and `#` comments are skipped.

    Every line has to be a key. A file where only some are is more likely to be a format this does
    not know than a list with a typo, and reading half of it would produce a tag that locates
    sometimes - which is worse than not reading it.
    """
    lines = [
        line.strip()
        for line in text.splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]

    if not lines:
        return []

    keys = []
    for line in lines:
        try:
            keys.append(_key(line, _BARE_KEYS))
        except KeyFileError:
            return []

    # The filename becomes the name, not the identifier - see _read_labelled for why.
    return [ParsedTag(private_keys=keys, name=stem, source_format=_BARE_KEYS)]


def _key(value: Any, source: str) -> bytes:
    """Decode one key from base64 or hex, and insist it is the right length."""
    if isinstance(value, bytes):
        raw = value
    elif not isinstance(value, str):
        raise KeyFileError(f"A key in {source} is written as text, and this one is {type(value).__name__}.")
    else:
        raw = _decode(value.strip(), source)

    if len(raw) != PRIVATE_KEY_LENGTH:
        raise KeyFileError(
            f"A key in {source} is {len(raw)} bytes, and an accessory key is"
            f" {PRIVATE_KEY_LENGTH}."
            + (
                " A 32-byte value is usually a shared secret from an Apple-paired accessory, which"
                " is a different thing entirely."
                if len(raw) == 32  # noqa: PLR2004 - named right here
                else ""
            ),
        )

    return raw


def _decode(value: str, source: str) -> bytes:
    """
    Decode base64, or hex if it cannot be base64.

    Hex is tried second and only when the text is entirely hex characters, because a hex string is
    also valid base64 - `deadbeef` decodes as base64 to six bytes of nonsense rather than failing.
    Length then rejects it, but only if the wrong reading is the one attempted second.
    """
    if re.fullmatch(r"[0-9a-fA-F]+", value) and len(value) == PRIVATE_KEY_LENGTH * 2:
        return bytes.fromhex(value)

    try:
        return base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError):
        pass

    try:
        return bytes.fromhex(value)
    except ValueError:
        raise KeyFileError(f"A key in {source} is neither base64 nor hex: {value!r}") from None


def _text(value: Any) -> str | None:
    """A non-empty string, or nothing."""
    return value.strip() if isinstance(value, str) and value.strip() else None


def _stem(filename: str | None) -> str | None:
    """A filename without its extension or directories - what OpenHaystack names a tag by."""
    if not filename:
        return None

    stem = filename.replace("\\", "/").rsplit("/", 1)[-1]
    stem = stem.rsplit(".", 1)[0] if "." in stem else stem

    return stem.strip() or None
