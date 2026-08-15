"""
Working out what a beacon record actually *is*, from what it carries.

An accessory the owner never named arrives with no name, and a list of three rows reading
"unnamed" is not a list anybody can choose from. Everything needed to tell them apart is already
on the record - it is just not in one field.

**This is the second implementation of this heuristic in the project**, and the first is
`BeaconInformation.isAirTag()` / `isIpad()` in the Android app. They must agree: the same tag
described as an AirTag in one place and as "product 0x5500" in the other is the kind of
disagreement nobody notices until a user reports it. What is known is gathered here, with where
each fact came from, so there is one thing to update when a new device turns up.

Everything is a heuristic and none of it is authoritative. Where a record does not match anything
known, this says what the record holds rather than guessing - a wrong product name in a list
somebody is choosing from is worse than a number they can search for.
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any

AIRTAG_PRODUCT_ID = 21760
"""
`productId` on an AirTag - `0x5500`.

**[observed]** by the Android app across several AirTags, and by this project's own committed
export fixtures. It is what `BeaconInformation.AIRTAG_PRODUCT_ID` holds, and the two must not
drift apart.
"""

APPLE_VENDOR_ID = 76
"""
`vendorId` on Apple hardware - `0x004C`.

A Bluetooth SIG assigned company identifier rather than anything Find My invented, so it is as
close to authoritative as anything here gets. **[observed]** on AirTags; the owner's own devices
carry no vendor id at all.
"""

# Who made a Find My accessory, by its Bluetooth SIG company identifier.
#
# **Taken from the registry rather than remembered**, on 2026-08-15, from
# https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml
# which held 4,002 companies. To check one, or to add a maker that turns up on a real account:
#
#     curl -sL <that url> | grep -B1 -i 'chipolo'
#
# **A curated handful rather than all four thousand.** The full list is around 120 KB of table for
# a field most people never look at, and it ships in a phone app as well as a desktop bundle.
# Anything absent falls through to `vendor 0x____`, which is exactly what somebody needs to look it
# up - so the cost of a missing entry is small and the cost of a wrong one is not.
#
# Names are shortened to what a person calls the company. Two traps found while doing it:
# `Pebble Technology` (0x0154) is the smartwatch company and **not** Pebblebee, which makes Find My
# trackers and is not in the registry under that name at all; and eufy's trackers are Anker's.
_VENDORS = {
    0x0006: "Microsoft",
    0x004C: "Apple",
    0x005C: "Belkin",
    0x0075: "Samsung",
    0x0087: "Garmin",
    0x009E: "Bose",
    0x00E0: "Google",
    0x0171: "Amazon",
    0x012D: "Sony",
    0x018E: "Google",
    0x01DA: "Logitech",
    0x027D: "Huawei",
    0x038F: "Xiaomi",
    0x067C: "Tile",
    0x08C3: "Chipolo",
    0x0CC2: "Anker",  # eufy's trackers are Anker's
    0x0CCB: "Nothing",
    0x0E66: "Baseus",
    0x0FA6: "Ugreen",
}

# `iPad13,18`, `iPhone15,2`, `MacBookPro18,3` - Apple's own model identifiers, which its devices
# use for themselves and accessories do not. The family is the leading letters.
_APPLE_MODEL = re.compile(r"^([A-Za-z]+)\d+,\d+$")

# The friendlier name for a family, where the identifier is not already readable. Anything absent
# falls through to the identifier itself, which is still perfectly identifying - so a family
# nobody here has heard of costs a reader nothing.
#
# **These are Apple's own model identifiers**, the ones its devices report for themselves. They
# are stable, published in a hundred places, and not something this project observed - which is
# the distinction worth keeping: `AIRTAG_PRODUCT_ID` above was measured on real hardware by this
# project, and this table is general knowledge. If one of these is wrong the cost is a wrong
# friendly name beside a correct identifier; if the product id is wrong, an AirTag stops being
# recognised as one.
_FAMILIES = {
    "iPad": "iPad",
    "iPhone": "iPhone",
    "iPod": "iPod",
    "Watch": "Apple Watch",
    "iMac": "iMac",
    "iMacPro": "iMac Pro",
    "Macmini": "Mac mini",
    "MacBook": "MacBook",
    "MacBookAir": "MacBook Air",
    "MacBookPro": "MacBook Pro",
    "MacPro": "Mac Pro",
    "Mac": "Mac",
    "AirPods": "AirPods",
    "AudioAccessory": "HomePod",
    "AppleTV": "Apple TV",
    "RealityDevice": "Apple Vision Pro",
}

# Section markers inside the AirPods form of `stableIdentifier`. See findmy-export 06-output
# section 2.4: `a:/<uuid>~#¶<model>§<hardware-id>§<serial as hex ASCII>§<position>`.
_AIRPODS_MARKER = "\u00b6"  # ¶
_AIRPODS_SEPARATOR = "\u00a7"  # §
_AIRPODS_SECTIONS = 4

_AIRPODS_UNITS = {"0": "left", "1": "right", "2": "case"}

# The third-party accessory form: `a:/<uuid>~#<serial>`, against an AirTag's `<n>~#<hwid>~#<serial>`.
_THIRD_PARTY_PREFIX = "a:/"


def identify(record: Mapping[str, Any]) -> str | None:
    """
    Describe what a beacon record is, or None if nothing here recognises it.

    :param record: A decrypted `MasterBeaconRecord`'s values, or the plist rendered from one -
        the fields this reads are named the same in both.

    The order matters, and is from most specific to least: a set of AirPods is also Apple hardware,
    and saying "Apple accessory" about one would be true and useless.
    """
    return (
        _airpods(record)
        or _airtag(record)
        or _apple_device(record)
        or _third_party(record)
        or _unrecognised(record)
    )


def is_own_device(record: Mapping[str, Any]) -> bool:
    """
    Whether this is one of the owner's own devices rather than an accessory.

    **Asked because exporting one is a different act.** Handing over an AirTag's keys lets someone
    locate a wallet; handing over a MacBook's lets them locate the person, wherever they carry it,
    for as long as the keys are valid - and neither can be revoked without unpairing. The list
    shows what each row is, which is most of the job, but "select all" is one keystroke and the
    consequence is not the one people think they are agreeing to.

    Two signals, either of which is enough:

    - **An Apple model identifier** in `model` - `iPad13,18`, `MacBookPro18,3`. An accessory's
      `model` is empty; it identifies itself through `productId` and `vendorId` instead.
    - **`secureLocationsSharedSecret`**, which an iPhone, iPad or Mac carries in place of the
      `secondarySharedSecret` an accessory carries. See findmy-export 06-output section 2.3.

    **[observed]** a real account returned an iPad and a MacBook Air here, both carrying key
    material - so this is not the same question as "can it be located", which they passed.

    AirPods are deliberately not caught: their model lives inside `stableIdentifier` rather than in
    `model`, and they are an accessory somebody bought rather than the computer they work on.
    """
    model = record.get("model")

    if isinstance(model, str) and _APPLE_MODEL.match(model):
        return True

    return record.get("secureLocationsSharedSecret") is not None


def _airpods(record: Mapping[str, Any]) -> str | None:
    """
    AirPods, and **which one** - the difference between three otherwise identical rows.

    A set is three accessories sharing a group identifier, so "AirPods" three times over is
    exactly the problem this module exists to solve.
    """
    tail = _stable_identifier_tail(record)
    if tail is None or not tail.startswith(_AIRPODS_MARKER):
        return None

    sections = tail.lstrip(_AIRPODS_MARKER).split(_AIRPODS_SEPARATOR)
    if len(sections) < _AIRPODS_SECTIONS:
        return "AirPods"

    model = _FAMILIES.get(sections[0]) or sections[0] or "AirPods"
    unit = _AIRPODS_UNITS.get(sections[3])

    return f"{model} ({unit})" if unit else model


def _airtag(record: Mapping[str, Any]) -> str | None:
    """
    An AirTag, by its product id.

    The same test the Android app makes. Deliberately **not** the leading number of
    `stableIdentifier`, which looks like a kind marker and is not: two accounts show two values for
    the same AirTag shape, so matching on it rejects real ones.
    """
    return "AirTag" if record.get("productId") == AIRTAG_PRODUCT_ID else None


def _apple_device(record: Mapping[str, Any]) -> str | None:
    """
    One of the owner's own devices - an iPhone, iPad or Mac, which are findable too.

    Their `model` is an Apple model identifier, where an accessory's is empty. The identifier is
    kept alongside the friendly name rather than replaced by it: `iPad13,18` is what a search
    engine answers questions about, and "iPad" is what a person recognises.
    """
    model = record.get("model")
    if not isinstance(model, str) or not model:
        return None

    match = _APPLE_MODEL.match(model)
    if not match:
        # A model this does not recognise the shape of is still a model, and still identifying.
        return model

    family = _FAMILIES.get(match.group(1))

    return f"{family} ({model})" if family else model


def _third_party(record: Mapping[str, Any]) -> str | None:
    """
    A Find My-certified accessory made by somebody other than Apple.

    Named by its maker where the registry knows them - "Chipolo tag" is a great deal more use than
    "Find My accessory" when three of them are in a list.
    """
    tail = _stable_identifier(record)
    if tail is None or not tail.startswith(_THIRD_PARTY_PREFIX):
        return None

    vendor = _vendor(record)

    return f"{vendor} tag" if vendor and vendor != "Apple" else "Find My accessory"


def _unrecognised(record: Mapping[str, Any]) -> str | None:
    """
    What the record says about itself, when nothing above recognised it.

    In hex, because that is the form these are written in everywhere they can be looked up - the
    Bluetooth SIG's own registry, and every forum thread about one.
    """
    product = record.get("productId")
    vendor = record.get("vendorId")
    named = _vendor(record)

    parts = []
    if named:
        parts.append(named)
    elif isinstance(vendor, int) and vendor >= 0:
        parts.append(f"vendor 0x{vendor:04X}")
    if isinstance(product, int) and product >= 0:
        parts.append(f"product 0x{product:04X}")

    return " ".join(parts) if parts else None


def _vendor(record: Mapping[str, Any]) -> str | None:
    """Who made it, if the registry names them. See :data:`_VENDORS`."""
    vendor = record.get("vendorId")

    return _VENDORS.get(vendor) if isinstance(vendor, int) and vendor >= 0 else None


def where_to_look_up(record: Mapping[str, Any]) -> str | None:
    """
    How somebody could find out what an unrecognised accessory is, in one line.

    Offered rather than guessed at. The two numbers on the record are real registry values, and a
    person holding an accessory nobody here recognises can settle it in a browser in under a
    minute - which is a better answer than a name this module made up.

    Returns None when there is nothing to look up, so a caller can say nothing at all.
    """
    vendor = record.get("vendorId")

    if not isinstance(vendor, int) or vendor < 0 or _VENDORS.get(vendor):
        return None

    return (
        f"Vendor 0x{vendor:04X} is a Bluetooth SIG company identifier. To find out who that is,"
        " search for it at https://www.bluetooth.com/specifications/assigned-numbers/"
    )


def _stable_identifier(record: Mapping[str, Any]) -> str | None:
    """
    The first `stableIdentifier` entry, whichever shape the record is in.

    CloudKit carries a single string here and the plist carries a list of them, and this reads
    both - the same function serves a record fetched from an account and one read from a bundle.
    """
    value = record.get("stableIdentifier")

    if isinstance(value, str):
        return value or None
    if isinstance(value, (list, tuple)) and value and isinstance(value[0], str):
        return value[0] or None

    return None


def _stable_identifier_tail(record: Mapping[str, Any]) -> str | None:
    """Everything after the last `~#`, which is where the structured part lives."""
    value = _stable_identifier(record)

    return value.split("~#")[-1] if value else None
