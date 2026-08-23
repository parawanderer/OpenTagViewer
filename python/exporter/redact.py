"""
Take the obvious personal identifiers out of a log, so posting one is less costly.

**Best effort, and the wording everywhere says so.** This cannot promise a clean file: the text
comes from a library reading Apple's structures, and a field that is innocuous on one account may
not be on another. What it can do is remove the things that are *known* to appear and are
recognisable by shape, which is most of what a person would otherwise have to find by hand.

**A library was considered and rejected.** `scrubadub` pulls scikit-learn, textblob, dateparser
and faker; `presidio-analyzer` pulls spacy, numpy and pydantic. Both are large enough to matter in
a frozen desktop bundle, and neither knows what an Apple device serial, a Cuttlefish peer hash or
a keychain `acct` field looks like - they are built for prose. Everything worth removing here has
a known shape, and a known shape is a regex.

**Values are numbered rather than blanked**, so `<serial-1>` is the same serial everywhere it
appears. A log where every identifier became `***` loses the one thing that makes it a log: that
this record and that record are about the same device. Redaction that destroys the diagnosis
defeats the reason for sending the file.
"""

from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass


@dataclass(frozen=True)
class Rule:
    """One kind of identifier, and how to find it."""

    name: str
    pattern: re.Pattern[str]
    group: int = 0
    """Which capture group holds the value. 0 means the whole match."""


# Order matters where two rules could claim the same text. Paths run before names so that a
# username inside a home directory is taken as a path rather than left for something looser, and
# the labelled fields run before the loose shapes so their placeholder names stay meaningful.
RULES: tuple[Rule, ...] = (
    # An Apple ID, wherever it turns up - an error message, a prompt echoed back, a keychain
    # attribute. The one identifier here that names a person rather than a device.
    Rule("email", re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.-]+\b")),

    # `/Users/paula/…`, `/home/amity/…`, `C:\Users\paula\…`. Tracebacks are full of these, and the
    # account name is very often the person's actual name.
    Rule("user", re.compile(r"(?i)(?:/Users/|/home/|[A-Z]:\\Users\\)([^/\\\s'\"]+)"), group=1),

    # Cuttlefish peers, as `Peer SHA256:<base64>=`. Pseudonymous, stable, one per device on the
    # account - so a set of them fingerprints the account even without a name attached.
    Rule("peer", re.compile(r"SHA256:[A-Za-z0-9+/]{20,}={0,2}")),

    # How escrow records describe themselves: "…, serial F2LX9Q…, escrowed 2024-03-11".
    Rule("serial", re.compile(r"\bserial\s+([A-Z0-9]{6,20})\b"), group=1),

    # The device name an escrow record carries, which is whatever the user called their phone -
    # very often their own name. Both of the lines FindMy.py logs it on.
    #
    # **MULTILINE is not decoration.** Without it `$` matches only at the end of the whole file, so
    # this fired on the last line and nothing else - it reported zero devices on a real log full of
    # them, which is the failure mode where redaction looks like it ran.
    Rule(
        "device",
        re.compile(r"(?:escrow recovery for|escrow record)\s+(.+?)(?=,|$)", re.MULTILINE),
        group=1,
    ),

    # How `EscrowRecord.describe()` renders: "<name>, <model>, serial …, escrowed …". The name is
    # whatever somebody called their phone, and is frequently their own name; the model beside it
    # is not personal and is worth keeping, since it is often the point of the line.
    #
    # Anchored on a model followed by `serial` so it cannot run away with a whole sentence.
    # The anchor allows a list marker, because this is most often read off a numbered menu of
    # recovery options - "  1. Paula's iPhone, iPhone15,2, serial …" - and anchoring on the bare
    # line start missed every one of them.
    Rule(
        "device",
        re.compile(
            r"(?:^[ \t]*(?:\d+\.[ \t]*)?|\?[ \t]|:[ \t])"
            r"([^,\n]{1,60}?),[ \t]+"
            # Bounded rather than comma-free: an Apple model identifier contains a comma of its
            # own - `iPhone15,2` - so a lookahead that stops at the first comma never reaches the
            # `serial` it is anchored on, and matched only the models that happen not to have one.
            r"(?=(?:iPhone|iPad|iPod|Mac|MacBook|iMac|Watch).{0,40}?,[ \t]+serial\b)",
            re.MULTILINE,
        ),
        group=1,
    ),

    # `describe_item` prints keychain attributes verbatim: acct='…', labl='…'. What is in them is
    # Apple's choice, so the safe assumption is that it identifies somebody.
    #
    # The quotes are matched but *not* captured, so an empty attribute stays `acct=''` instead of
    # becoming `acct=<item-1>` - a placeholder standing in for nothing, which reads as though
    # something was hidden and makes the count wrong.
    Rule("item", re.compile(r"\b(?:acct|labl|srvr|agrp)=(['\"])([^'\"]*)\1"), group=2),

    # Record and beacon identifiers. Not secret, but unique to one account's accessories.
    Rule("uuid", re.compile(r"\b[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}\b")),

    # ------------------------------------------------------------------ only the app produces these
    #
    # The exporter never sees a location or a bundle password, so none of the rules below will
    # ever fire on a wizard log. They live here anyway, because the alternative is a second
    # redactor with its own answer to "is my data in this file", and the one thing worse than a
    # rule that never matches is two sets of rules that disagree.

    # **A location is the most personal thing this app handles**, and four decimal places is
    # about eleven metres - somebody's home, not a neighbourhood. Labelled first, so the
    # placeholder keeps the label's meaning.
    Rule("coordinate", re.compile(r"(?i)\b(?:lat|lon|lng|latitude|longitude)\s*[=:]\s*(-?\d+\.\d+)"), group=1),

    # And unlabelled, as a pair - which is how the app actually writes them:
    # `String.format("%.4f,%.4f", …)` for a geocoding cache key, and `point=(lat, lon)` for a
    # tapped marker.
    #
    # **Three decimals minimum, and both halves must have them.** Without that this eats version
    # numbers, durations and byte counts. With it, the shape is specific enough that a false
    # positive is a pair of precise decimals next to each other, which in a log of this app is
    # very likely a coordinate anyway.
    Rule("coordinate", re.compile(r"-?\b\d{1,3}\.\d{3,}\s*,\s*-?\d{1,3}\.\d{3,}\b")),

    # A reverse-geocoded place name: a street, a town, whatever Google or AMap resolved a
    # coordinate to.
    #
    # **Labelled only, because prose has no shape.** "221B Baker St, London NW1 6XE" cannot be
    # told from any other sentence by regex, and a rule loose enough to catch it would redact
    # half the log. So this catches it where it is announced as an address and no further -
    # which is worth having precisely because nothing logs one *today*: the day somebody adds
    # `Log.d(TAG, "resolved to " + address)`, this is already in place, and nobody has to
    # remember to come back here.
    Rule(
        "place",
        re.compile(
            r"(?i)\b(?:address|addressLine|locality|thoroughfare|place|placeName"
            r"|geocoded(?:\s*to)?)\s*[=:]\s*(.+?)(?=$|[;|]|\s{2,})",
            re.MULTILINE,
        ),
        group=1,
    ),

    # The code that unlocks an export bundle, as `format_passcode` writes it: three groups of
    # four from Crockford's base32.
    #
    # Grouped form only. The bare twelve characters are indistinguishable from a hash fragment or
    # half a serial, and redacting every twelve-character uppercase run would take out things the
    # log is read for. The grouped form is what a person copies, types and pastes.
    #
    # **A password in a log is not the same risk as an identifier.** Whoever holds an export and
    # this can locate the tags in it indefinitely, and unpairing is the only way to withdraw that.
    Rule("passcode", re.compile(r"\b[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}\b")),

    # The number Apple offers to text a code to, which `main.py` prints when it lists the second
    # factor methods: `Option: SMS (+44 7700 900123)`. Partly masked by Apple already, and the
    # unmasked digits are still somebody's phone number.
    Rule("phone", re.compile(r"(?i)\bSMS\s*\(([^)]+)\)"), group=1),
)


def redact(text: str) -> tuple[str, Counter[str]]:
    """
    Replace recognised identifiers with stable placeholders.

    :returns: The cleaned text, and how many *distinct* values were replaced per kind - which is
        what a person needs to sanity-check the result. Ten devices found in a log from somebody
        who owns two is a rule matching too much, and no serials found at all is a rule that has
        stopped matching, and both are worth seeing.
    """
    seen: dict[str, dict[str, str]] = {rule.name: {} for rule in RULES}

    for rule in RULES:
        def substitute(match: re.Match[str], rule: Rule = rule) -> str:
            value = match.group(rule.group)

            # Nothing to do, and replacing it would turn an empty field into a fake identifier.
            if not value or not value.strip():
                return match.group(0)

            known = seen[rule.name]
            if value not in known:
                known[value] = f"<{rule.name}-{len(known) + 1}>"

            # Only the captured part is replaced, so the surrounding text - which is what makes
            # the line readable - survives. "serial F2LX9Q" becomes "serial <serial-1>", not
            # "<serial-1>".
            start, end = match.span(rule.group)
            return match.group(0)[: start - match.start()] + known[value] + match.group(0)[end - match.start():]

        text = rule.pattern.sub(substitute, text)

    return text, Counter({name: len(values) for name, values in seen.items() if values})


def summarise(counts: Counter[str]) -> str:
    """One line naming what was taken out, for showing to the person about to send the file."""
    if not counts:
        return "Nothing recognisable was found to remove."

    parts = [f"{count} {name}{'s' if count != 1 else ''}" for name, count in sorted(counts.items())]

    return "Replaced " + ", ".join(parts) + "."
