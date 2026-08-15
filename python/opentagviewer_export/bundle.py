"""
Turns accessory records into the exact file layout OpenTagViewer imports.

The layout is specified in `docs/findmy-export/06-output.md` section 5.5:

    OPENTAGVIEWER.yml
    OwnedBeacons/<beacon-uuid>.plist
    BeaconNamingRecord/<beacon-uuid>/<naming-record-uuid>.plist
    KeyAlignmentRecords/<beacon-uuid>/<record-uuid>.plist

Four details of it are not guessable from the shape, and each produces a bundle that looks
correct and is not: `KeyAlignmentRecords` is **plural** where the other two are singular; every
file is `.plist` even though macOS names some of them `.record`; the plists are **XML**, because
the importer parses them with XPath; and an alignment record's accessory is its **parent
directory** rather than a field, so writing these flat loses the association.

**This module refuses rather than degrades.** Everything it checks is something that produces a
bundle which imports cleanly and is missing a tag, or fails in Java with a message about
XPath - both of which surface on the recipient's phone, days later, with nothing to point at.
The cost of being wrong here is entirely paid by somebody who cannot debug it, so a caller
handing over something unimportable gets an exception naming the record and the reason.
"""

from __future__ import annotations

import json
import plistlib
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

import yaml

EXPORT_FORMAT_VERSION = "0.0.2"
"""
The **format** version, not the producer's - see `via` for that.

`0.0.2` is the version that carries key alignment records. The app's schema insists on three
dot-separated numbers, so this is not a free-form string.
"""

EXPORT_FORMAT_VERSION_WITH_CUSTOM = "0.0.3"
"""
What a bundle says when it carries a self-generated accessory - see :class:`CustomAccessoryExport`.

**Written only when one is present**, so the version describes what a reader has to understand
rather than which exporter happened to write it. A bundle of Apple-paired tags from this version
is byte-identical to one from the last, and a reader that meets `0.0.3` knows there is something
in it that `0.0.2` could not express.
"""

METADATA_FILENAME = "OPENTAGVIEWER.yml"

OWNED_BEACONS_DIR = "OwnedBeacons"
BEACON_NAMING_RECORD_DIR = "BeaconNamingRecord"
KEY_ALIGNMENT_RECORDS_DIR = "KeyAlignmentRecords"
"""Plural, where the other two are singular. There is no reason for it; it is simply the name."""

CUSTOM_ACCESSORIES_DIR = "CustomAccessories"
"""
Where self-generated accessories live, because the plist layout cannot hold one.

A separate directory rather than a `.json` among the plists: the importer's whitelist is driven by
extension and a regex per directory, and mixing two unrelated formats in one place makes that
matcher answer two questions at once.
"""

CUSTOM_ACCESSORY_TYPE = "custom_rolling_key_accessory"
"""FindMy.py's own tag for the mapping written into :data:`CUSTOM_ACCESSORIES_DIR`."""

# Only these in a filename, and never one that names a directory instead of a file. An identifier
# reaching this module came out of a file somebody else wrote, so it is treated as text rather
# than as a path.
_UNSAFE_IN_FILENAME = re.compile(r"[^A-Za-z0-9._-]")
_MAX_IDENTIFIER = 100

_IMPORTABLE_UUID = re.compile(
    r"^[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$",
)
"""
The identifiers the importer will accept, copied from its own regex.

`AppleZipImporterUtil.MATCHERS` matches zip entry names against exactly this: uppercase hex, and
a version-4 UUID specifically - the `4` and the `[89AB]` are the version and variant nibbles. A
file whose name does not match is **skipped silently**, with the import reporting success and the
tag simply absent, so this is checked here where it can still be reported to whoever ran the
export.
"""

# The fields FindMy.py's `FindMyAccessory.from_plist` subscripts rather than `.get`s. Absent, the
# import fails at conversion instead of producing an unusable accessory - but it fails on the
# phone, so it is checked here.
_REQUIRED_BEACON_FIELDS = ("identifier", "privateKey", "sharedSecret", "model", "pairingDate")

# Key material is nested two levels deep - `privateKey -> {"key": {"data": <bytes>}}` - rather
# than stored as bare bytes. The wrapping carries no information; it is an artefact of the macOS
# framework that wrote the original cache. Omitting it produces a file that fails to import for a
# reason nothing will explain, so the shape is checked.
_WRAPPED_KEY_FIELDS = (
    "privateKey",
    "publicKey",
    "sharedSecret",
    "secondarySharedSecret",
    "secureLocationsSharedSecret",
)


class ExportError(Exception):
    """Raised when what was handed over could not be written as an importable bundle."""


@dataclass(frozen=True)
class AccessoryExport:
    """
    One accessory, as the three records the bundle carries for it.

    Each is a plist mapping in the layout a Mac's own cache uses - which is what FindMy.py's
    `to_owned_beacon_plist`, `to_beacon_naming_plist` and `to_key_alignment_plist` return, and
    what `plistlib.load` gives back for a file from an existing export. This module does not
    import FindMy.py and does not decrypt anything: the mapping from CloudKit records to these
    dictionaries belongs to the library, and the join belongs to the caller.

    :param owned_beacon: The `OwnedBeacons` record. Must carry key material.
    :param naming_record: Its `BeaconNamingRecord`. **Not optional** - the importer inner-joins
        the two and drops any accessory it cannot pair with one, so an accessory exported without
        a name is an accessory silently missing from the import.
    :param key_alignment_record: Its `KeyAlignmentRecord`, if it has one. Genuinely optional, and
        absence is normal - not every accessory has one. Without it an import searches the tag's
        entire key history, which is slow enough to look like abuse of the account, so pass one
        whenever there is one.
    """

    owned_beacon: Mapping[str, Any]
    naming_record: Mapping[str, Any]
    key_alignment_record: Mapping[str, Any] | None = None


@dataclass(frozen=True)
class CustomAccessoryExport:
    """
    One self-generated accessory - an OpenHaystack-style tag, whose keys were never in any Apple
    account.

    **A different kind of thing from :class:`AccessoryExport`, not a variant of it.** An
    Apple-paired accessory carries a private key, a shared secret and a secondary secret, which are
    the inputs to Apple's rolling-key derivation; this one carries a plain list of pre-generated
    keys and derives nothing. The plist layout has no field for that, which is why these are
    written as JSON in their own directory rather than squeezed into `OwnedBeacons`.

    :param mapping: FindMy.py's `custom_rolling_key_accessory` mapping - what
        `FixedRollingKeyPairAccessory.to_json()` returns. Taken rather than built here for the same
        reason the plists are: the library owns its own formats, and a second implementation of one
        is a second thing to get wrong.

    A `name` is **required**, unlike for a paired accessory. A paired one has a naming record the
    owner wrote; this has whatever the file it came from carried, which is often nothing - and an
    unnamed tag arrives in the app as a row with no way to tell it from any other.
    """

    mapping: Mapping[str, Any]


@dataclass(frozen=True)
class ExportBundle:
    """
    A bundle's files, in memory.

    :param entries: Relative POSIX paths to file bytes. This *is* the bundle; a zip is one way
        to carry it, and the Android app zipping these natively is another.
    :param exported_at_ms: What `OPENTAGVIEWER.yml` says, carried so a sink can date its entries
        from the same clock rather than from whenever it happens to run.
    """

    entries: dict[str, bytes]
    exported_at_ms: int


def build_export(
    accessories: Sequence[AccessoryExport | CustomAccessoryExport],
    *,
    via: str,
    source_user: str,
    exported_at_ms: int,
) -> ExportBundle:
    """
    Build the bundle for exactly the accessories given.

    **The list is the selection.** Nothing here defaults to "everything": sharing one tag with a
    friend and handing over a household's entire set are different acts, and exported keys cannot
    be withdrawn afterwards - the only way to revoke one is to unpair the accessory.

    :param accessories: What to export, already joined and already chosen by the user.
    :param via: What produced this bundle, as `<producer>:<version>`. **A parameter, never
        invented here**: the desktop exporter, its CLI and the Android app are three producers
        with three versions, and `via:` is how anyone looking at a zip afterwards works out which
        one built it. A shared module that made this up would answer that question wrongly.
    :param source_user: What the recipient sees as "exported by", shown in the app. A label,
        never an Apple ID - see below.
    :param exported_at_ms: Milliseconds since the Unix epoch. Passed rather than read from the
        clock so this stays a pure function of its inputs.
    :raises ExportError: If anything given would produce a bundle that does not import.
    """
    if not accessories:
        raise ExportError("Nothing was selected, and an export with no accessories imports nothing.")

    if not via or not via.strip():
        raise ExportError(
            "`via` must name the producer and its version, as `<producer>:<version>`. It is what"
            " identifies which program built a bundle, so it cannot be blank.",
        )

    if not source_user or not source_user.strip():
        raise ExportError(
            "`source_user` must be a non-empty string: the app's import schema requires it, and an"
            " export without it fails validation on the phone rather than here.",
        )

    if not isinstance(exported_at_ms, int) or isinstance(exported_at_ms, bool) or exported_at_ms <= 0:
        raise ExportError(
            f"`exported_at_ms` must be a positive integer count of milliseconds, not {exported_at_ms!r}.",
        )

    entries: dict[str, bytes] = {}
    seen: set[str] = set()
    carries_custom = False

    for accessory in accessories:
        if isinstance(accessory, CustomAccessoryExport):
            carries_custom = True
            identity, files = _custom_entries(accessory)
        elif isinstance(accessory, AccessoryExport):
            identity, files = _accessory_entries(accessory)
        else:
            raise ExportError(
                f"{type(accessory).__name__} is not something this can export. Pass an"
                " AccessoryExport for an Apple-paired accessory, or a CustomAccessoryExport for a"
                " self-generated one.",
            )

        if identity in seen:
            # Checked before merging rather than after: a second copy would overwrite the first
            # silently, and the bundle would carry whichever came last with nothing to show for it.
            raise ExportError(
                f"Accessory {identity} was given twice. Its files would overwrite each other, so"
                " the bundle would silently carry whichever came last.",
            )
        seen.add(identity)
        entries.update(files)

    entries[METADATA_FILENAME] = _metadata(
        via=via,
        source_user=source_user,
        exported_at_ms=exported_at_ms,
        # Said only when it is true: a bundle of paired accessories from this version is
        # byte-identical to one from the version before, and a reader meeting 0.0.3 knows it holds
        # something 0.0.2 had no way to express.
        version=EXPORT_FORMAT_VERSION_WITH_CUSTOM if carries_custom else EXPORT_FORMAT_VERSION,
    )

    return ExportBundle(entries=entries, exported_at_ms=exported_at_ms)


def _custom_entries(accessory: CustomAccessoryExport) -> tuple[str, dict[str, bytes]]:
    """Build one self-generated accessory's file, and say what it is filed under."""
    mapping = accessory.mapping

    if not isinstance(mapping, Mapping):
        raise ExportError(f"A custom accessory is a mapping, not {type(mapping).__name__}.")

    kind = mapping.get("type")
    if kind != CUSTOM_ACCESSORY_TYPE:
        raise ExportError(
            f"A custom accessory's mapping must say `type: {CUSTOM_ACCESSORY_TYPE}`, and this one"
            f" says {kind!r}."
            + (
                " That is the mapping for an Apple-paired accessory, which belongs in the plist"
                " layout - pass it as an AccessoryExport instead."
                if kind == "accessory"
                else ""
            ),
        )

    keys = mapping.get("private_keys")
    if not isinstance(keys, list) or not keys:
        raise ExportError(
            "A custom accessory needs a non-empty `private_keys` list. Without keys there is"
            " nothing to locate it by, so the entry could never do anything.",
        )

    for key in keys:
        # Hex, because that is what FindMy.py writes and reads back. Checked rather than assumed:
        # a key list that fails to decode fails at *locate* time on the recipient's phone, which is
        # a long way from here.
        if not isinstance(key, str) or not key:
            raise ExportError(f"A private key is written as a hex string, and this one is {key!r}.")
        try:
            bytes.fromhex(key)
        except ValueError as e:
            raise ExportError(f"A private key is not readable as hex: {key!r} ({e}).") from None

    name = mapping.get("name")
    if not isinstance(name, str) or not name.strip():
        raise ExportError(
            "A self-generated accessory must be given a name. It has no naming record to fall back"
            " on - unlike an Apple-paired accessory - so without one it reaches the app as a row"
            " with nothing to tell it apart from any other.",
        )

    identifier = _filename_identifier(mapping.get("identifier"), name)

    document = json.dumps(dict(mapping), indent=2, sort_keys=True, ensure_ascii=False) + "\n"

    return identifier, {f"{CUSTOM_ACCESSORIES_DIR}/{identifier}.json": document.encode("utf-8")}


def _filename_identifier(identifier: Any, name: str) -> str:
    """
    Turn an accessory's identifier into something safe to use as a filename.

    These identifiers come out of a file somebody else wrote - Macless Haystack numbers them, other
    tools use whatever they like - so unlike the UUIDs elsewhere in this format there is no shape
    to insist on. What matters is that it names a file inside the bundle and nothing outside it.
    """
    if not isinstance(identifier, str) or not identifier.strip():
        raise ExportError(
            f"The custom accessory named {name!r} has no `identifier`, and the identifier is its"
            " filename in the bundle.",
        )

    safe = _UNSAFE_IN_FILENAME.sub("_", identifier.strip())

    if not safe.strip("._") or len(safe) > _MAX_IDENTIFIER:
        raise ExportError(
            f"The identifier {identifier!r} cannot be used as a filename. Give the accessory a"
            " simpler one - letters, digits, dots, dashes and underscores, up to"
            f" {_MAX_IDENTIFIER} characters.",
        )

    return safe


def _accessory_entries(accessory: AccessoryExport) -> tuple[str, dict[str, bytes]]:
    """Build one accessory's files, and say which beacon identifier they are filed under."""
    entries: dict[str, bytes] = {}
    beacon = _checked_beacon(accessory.owned_beacon)
    beacon_id = _uuid(beacon["identifier"], "an OwnedBeacons record's `identifier`")

    naming = _checked_naming(accessory.naming_record, beacon_id)
    naming_id = _uuid(naming["identifier"], f"the naming record of accessory {beacon_id}")

    entries[f"{OWNED_BEACONS_DIR}/{beacon_id}.plist"] = _plist(beacon)
    entries[f"{BEACON_NAMING_RECORD_DIR}/{beacon_id}/{naming_id}.plist"] = _plist(naming)

    if accessory.key_alignment_record is not None:
        alignment = _checked_alignment(accessory.key_alignment_record, beacon_id)
        alignment_id = _uuid(alignment["identifier"], f"the alignment record of accessory {beacon_id}")
        # The directory is the association: an alignment record carries no `associatedBeacon`
        # field, unlike a naming record, so writing these flat would lose which tag they belong to.
        entries[f"{KEY_ALIGNMENT_RECORDS_DIR}/{beacon_id}/{alignment_id}.plist"] = _plist(alignment)

    return beacon_id, entries


def _checked_beacon(record: Mapping[str, Any]) -> dict[str, Any]:
    """Check an `OwnedBeacons` record carries what an import needs, and normalise it."""
    beacon = _normalised(record, "an OwnedBeacons record")

    missing = [field for field in _REQUIRED_BEACON_FIELDS if field not in beacon]
    if missing:
        identifier = beacon.get("identifier", "<no identifier>")
        raise ExportError(
            f"OwnedBeacons record {identifier} is missing {', '.join(missing)}. FindMy.py reads"
            " these unconditionally when the bundle is imported, so the accessory would fail"
            " conversion on the recipient's phone rather than here."
            + (
                " A record with no `privateKey` is not an accessory at all - it is one of the"
                " owner's own devices, which cannot be located and must not be exported."
                if "privateKey" in missing
                else ""
            ),
        )

    if not isinstance(beacon["pairingDate"], datetime):
        raise ExportError(
            f"OwnedBeacons record {beacon['identifier']} has a `pairingDate` of type"
            f" {type(beacon['pairingDate']).__name__}, and the importer needs a date.",
        )

    # An accessory carries `secondarySharedSecret`; one of the owner's iPhones, iPads or Macs
    # carries `secureLocationsSharedSecret` instead. FindMy.py reads whichever is present as the
    # secondary secret, and derives no secondary keys at all without one.
    if "secondarySharedSecret" not in beacon and "secureLocationsSharedSecret" not in beacon:
        raise ExportError(
            f"OwnedBeacons record {beacon['identifier']} carries neither `secondarySharedSecret`"
            " nor `secureLocationsSharedSecret`, so nothing importing it can generate the"
            " accessory's secondary keys.",
        )

    for field in _WRAPPED_KEY_FIELDS:
        if field in beacon:
            _check_wrapped(beacon, field)

    return beacon


def _check_wrapped(beacon: Mapping[str, Any], field: str) -> None:
    """Insist key material is nested the way the format nests it."""
    value = beacon[field]
    inner = value.get("key") if isinstance(value, Mapping) else None
    data = inner.get("data") if isinstance(inner, Mapping) else None

    if not isinstance(data, bytes):
        raise ExportError(
            f"OwnedBeacons record {beacon.get('identifier')} carries `{field}` as"
            f" {type(value).__name__} rather than as {{'key': {{'data': <bytes>}}}}. Key material"
            " is nested two levels deep in this format; bare bytes produce a file that fails to"
            " import for a reason nothing will explain.",
        )


def _checked_naming(record: Mapping[str, Any], beacon_id: str) -> dict[str, Any]:
    """Check a naming record belongs to the accessory it is being filed under."""
    naming = _normalised(record, f"the naming record of accessory {beacon_id}")

    if "identifier" not in naming:
        raise ExportError(
            f"The naming record of accessory {beacon_id} has no `identifier`, and the record's own"
            " identifier is its filename in the bundle.",
        )

    associated = naming.get("associatedBeacon")
    if isinstance(associated, str):
        associated = _uuid(associated, f"`associatedBeacon` of the naming record of {beacon_id}")
        naming["associatedBeacon"] = associated
        if associated != beacon_id:
            # A naming record names its accessory in `associatedBeacon` - an alignment record uses
            # `beaconIdentifier` for the same association, which is why this is worth stating. A
            # mismatch means the join upstream paired the wrong two records, and the bundle would
            # carry a record filed under one accessory and claiming another.
            raise ExportError(
                f"The naming record filed under accessory {beacon_id} says its"
                f" `associatedBeacon` is {associated}. One of them is wrong, and the bundle would"
                " carry the disagreement.",
            )

    return naming


def _checked_alignment(record: Mapping[str, Any], beacon_id: str) -> dict[str, Any]:
    """Check an alignment record is usable, and belongs to the accessory it is filed under."""
    alignment = _normalised(record, f"the alignment record of accessory {beacon_id}")

    if "identifier" not in alignment:
        raise ExportError(
            f"The alignment record of accessory {beacon_id} has no `identifier`, and the record's"
            " own identifier is its filename in the bundle.",
        )

    associated = alignment.get("beaconIdentifier")
    if isinstance(associated, str):
        associated = _uuid(associated, f"`beaconIdentifier` of the alignment record of {beacon_id}")
        alignment["beaconIdentifier"] = associated
        if associated != beacon_id:
            raise ExportError(
                f"The alignment record filed under accessory {beacon_id} says its"
                f" `beaconIdentifier` is {associated}. One of them is wrong.",
            )

    # Both, or the record is worse than useless: FindMy.py subscripts them when one is supplied,
    # so a half-filled record turns an import that would have worked slowly into one that fails.
    index = alignment.get("lastIndexObserved")
    observed = alignment.get("lastIndexObservationDate")
    if not isinstance(index, int) or isinstance(index, bool) or not isinstance(observed, datetime):
        raise ExportError(
            f"The alignment record of accessory {beacon_id} needs an integer `lastIndexObserved`"
            " and a `lastIndexObservationDate`, and carries"
            f" {type(index).__name__} and {type(observed).__name__}. Pass no alignment record at"
            " all rather than an unreadable one: the import is then slow, not broken.",
        )

    return alignment


def _uuid(value: Any, what: str) -> str:
    """Normalise an identifier to the form the importer's regex accepts, or refuse it."""
    if not isinstance(value, str):
        raise ExportError(f"{what} is {type(value).__name__}, and identifiers are strings.")

    # Uppercased rather than demanded uppercase: a UUID's hex is case-insensitive, macOS writes it
    # uppercase, and the importer's regex accepts nothing else. Doing it here keeps the filename
    # and the record's own `identifier` field in agreement, which nothing downstream re-checks.
    canonical = value.strip().upper()

    if not _IMPORTABLE_UUID.match(canonical):
        raise ExportError(
            f"{what} is {value!r}, which is not a version-4 UUID. The importer matches entry names"
            " against exactly that shape and skips anything else **without reporting it**, so this"
            " would import as a bundle that is simply missing the tag.",
        )

    return canonical


def _normalised(record: Mapping[str, Any], what: str) -> dict[str, Any]:
    """Copy a record, with its identifier canonical and its dates in UTC."""
    if not isinstance(record, Mapping):
        raise ExportError(f"{what} is {type(record).__name__}, and a record is a mapping.")

    normalised = {key: _normalise_value(value, what) for key, value in record.items()}

    if isinstance(normalised.get("identifier"), str):
        normalised["identifier"] = _uuid(normalised["identifier"], f"`identifier` of {what}")

    return normalised


def _normalise_value(value: Any, what: str) -> Any:
    """Make one value writable as XML, recursing into containers."""
    if isinstance(value, datetime):
        # plistlib formats a datetime from its own fields and ignores tzinfo entirely, so an aware
        # datetime in any other zone would be written as though its local time were UTC - a date
        # wrong by hours, silently. Convert, then drop the tzinfo it is about to ignore.
        if value.tzinfo is not None:
            value = value.astimezone(timezone.utc)
        return value.replace(tzinfo=None)

    if isinstance(value, Mapping):
        return {key: _normalise_value(item, what) for key, item in value.items()}

    if isinstance(value, (list, tuple)):
        return [_normalise_value(item, what) for item in value]

    if value is None:
        raise ExportError(
            f"{what} carries a null value, and property lists have no null. Leave the key out"
            " instead - every field the importer reads is optional or checked above.",
        )

    return value


def _plist(record: Mapping[str, Any]) -> bytes:
    """Serialise one record as an XML property list."""
    try:
        # XML, not binary: the importer parses these with XPath and a binary plist is not
        # something it can read at all.
        return plistlib.dumps(dict(record), fmt=plistlib.FMT_XML, sort_keys=True)
    except (TypeError, OverflowError) as e:
        identifier = record.get("identifier", "<no identifier>")
        raise ExportError(f"Record {identifier} holds something no property list can carry: {e}") from e


def _metadata(*, via: str, source_user: str, exported_at_ms: int, version: str) -> bytes:
    """Build `OPENTAGVIEWER.yml`, which the app validates against a JSON schema on import."""
    document = {
        "version": version,
        "exportTimestamp": exported_at_ms,
        "sourceUser": source_user,
        "via": via,
    }

    return yaml.dump(document, default_flow_style=False, sort_keys=True).encode("utf-8")
