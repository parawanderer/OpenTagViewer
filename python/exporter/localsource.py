"""
Reading accessories out of a Mac's own files, as the other source of the same thing.

This is the original export route: macOS keeps a local cache of the Find My records under
`~/Library/com.apple.icloud.searchpartyd`, encrypted with a key in the login keychain. It still
works, and it is kept because it works - the Apple ID password is typed into macOS rather than
into this program, which is a real advantage to some people.

**It is not an offline route, and calling it one is the mistake to avoid.** Those files are a
local copy of the CloudKit records, and macOS got them by signing into iCloud, joining the
keychain trust circle as a peer and syncing them down. So it needs a signed-in Mac, and it leaves
*more* on the account than the iCloud route does - a full device peer and an escrow record,
created by Apple's own software rather than by us.

What it produces is a :class:`~exporter.icloud.Candidate`, the same as the iCloud route, so the
selection screen, the naming of the nameless and the bundle writer are shared. Only the source
differs.
"""

from __future__ import annotations

import logging
import os
from typing import Any

from exporter.airtag_decryptor import (
    BEACON_NAMING_RECORD,
    INPUT_PATH,
    KEY_ALIGNMENT_RECORDS,
    KEYCHAIN_LABEL,
    MASTER_BEACONS,
    OWNED_BEACONS,
    WHITELISTED_DIRS,
    decrypt_plist,
    get_key_fallback,
)
from exporter.icloud import Candidate, ExportSourceError, Fetched, Skipped
from opentagviewer_export.hardware import identify

logger = logging.getLogger(__name__)


class ReadFailure(Exception):
    """One file that could not be read, carried so the caller can report it rather than guess."""


def read_key() -> bytearray:
    """
    Get the BeaconStore key out of the login keychain.

    **This is what makes macOS ask for the user's password**, twice, in a dialog this program does
    not draw and cannot style. Worth telling them before it happens rather than after.

    :raises ExportSourceError: If the keychain refused.
    """
    key = get_key_fallback(KEYCHAIN_LABEL)

    if not key:
        raise ExportSourceError(
            f"Access to '{KEYCHAIN_LABEL}' in your keychain was refused, so the local files cannot"
            " be decrypted. Try again and enter your password both times it asks.",
        )

    return key


def fetch(key: bytearray, input_path: str = INPUT_PATH) -> Fetched:
    """
    Read and decrypt the local cache, as the records a bundle carries.

    :param key: What :func:`read_key` returned.
    :param input_path: Where macOS keeps them. A parameter so a test can point somewhere else.
    """
    owned, naming, alignment = _read_all(key, input_path)

    by_beacon: dict[str, dict[str, Any]] = {}
    skipped: list[Skipped] = []

    for record in owned:
        identifier = record.get("identifier")
        if not isinstance(identifier, str):
            continue

        # The same test the iCloud route applies, and the rule this route has always used: a
        # record with no private key cannot be located, so exporting it produces an entry that can
        # never do anything.
        if "privateKey" not in record:
            skipped.append(Skipped(identifier, "has no private key, so it cannot be located"))
            continue

        by_beacon[identifier] = record

    named: dict[str, dict[str, Any]] = {}
    for record in naming:
        associated = record.get("associatedBeacon")
        if isinstance(associated, str) and associated in by_beacon:
            named[associated] = record

    aligned: dict[str, dict[str, Any]] = {}
    for record, path in alignment:
        # An alignment record carries no `associatedBeacon`: on disk the association is the
        # directory it sits in, exactly as it is in the bundle.
        beacon_id = os.path.basename(os.path.dirname(path))
        if beacon_id in by_beacon:
            aligned[beacon_id] = record

    candidates = [
        Candidate(
            beacon_id=identifier,
            name=named.get(identifier, {}).get("name"),
            emoji=named.get(identifier, {}).get("emoji"),
            has_alignment=identifier in aligned,
            owned_beacon=record,
            naming_record=named.get(identifier),
            key_alignment_record=aligned.get(identifier),
            hardware=identify(record),
            serial_number=_serial(record),
            paired_at=record.get("pairingDate"),
        )
        for identifier, record in by_beacon.items()
    ]

    logger.info("Read %d accessory record(s) from %s", len(candidates), input_path)

    return Fetched(candidates=candidates, skipped=skipped)


def _serial(record: dict[str, Any]) -> str | None:
    """The hardware serial out of `stableIdentifier`, which is already a list in these files."""
    from findmy.accessory import _extract_serial_from_stable_id  # noqa: PLC0415, PLC2701

    stable = record.get("stableIdentifier")

    return _extract_serial_from_stable_id(stable if isinstance(stable, list) else None)


def _read_all(
    key: bytearray,
    input_path: str,
) -> tuple[list[dict], list[dict], list[tuple[dict, str]]]:
    """
    Decrypt every file this needs, in one walk.

    Alignment records keep their path because that is where their association lives. The other two
    do not need one - a beacon names itself, and a naming record names its beacon.
    """
    owned: list[dict] = []
    naming: list[dict] = []
    alignment: list[tuple[dict, str]] = []

    if not os.path.isdir(input_path):
        raise ExportSourceError(
            f"There is no Find My cache at {input_path}. That directory is what this route reads,"
            " and macOS creates it when the Mac is signed into iCloud with Find My on.",
        )

    for entry in os.listdir(input_path):
        if entry not in WHITELISTED_DIRS:
            continue

        for record, path in _decrypt_folder(os.path.join(input_path, entry), key):
            if entry in (OWNED_BEACONS, MASTER_BEACONS):
                # MasterBeacons is the legacy name, used by macOS 11.
                owned.append(record)
            elif entry == BEACON_NAMING_RECORD:
                naming.append(record)
            elif entry == KEY_ALIGNMENT_RECORDS:
                alignment.append((record, path))

    return owned, naming, alignment


def _decrypt_folder(folder: str, key: bytearray) -> list[tuple[dict, str]]:
    """
    Decrypt every file under one directory, skipping what will not decrypt.

    A file that fails is logged and left out rather than taking the export with it: these
    directories accumulate residue, and one unreadable record should not cost somebody every tag
    they own. The count is what the caller reports.
    """
    records: list[tuple[dict, str]] = []

    for path, _, filenames in os.walk(folder):
        for filename in filenames:
            full = os.path.join(path, filename)
            try:
                records.append((decrypt_plist(full, key), full))
            except Exception:  # noqa: BLE001, PERF203 - per file, deliberately
                logger.warning("Could not decrypt %s; skipping it", full, exc_info=True)

    return records
