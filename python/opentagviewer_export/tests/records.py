"""
Records to build bundles out of, shaped like the ones a real account produces.

The values mirror `app/src/test/resources/09082026_2`, a sanitised export from a real Mac, so a
test that passes here is testing against the shape the importer actually meets. The key material
is nonsense of the right length and type - nothing here is a key.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

BEACON_ID = "725A989D-D871-49A7-B2FE-948C24F356AB"
NAMING_ID = "59AFE75B-8BD8-4522-85BC-F9B0BECD257A"
ALIGNMENT_ID = "D2C5F114-987C-48DB-9900-1E5B6D98671D"

OTHER_BEACON_ID = "B1CE4F0C-2489-486E-8295-45690FACF1E8"
OTHER_NAMING_ID = "0A19E4A2-91E0-4C40-9E9C-1B60E9B8FA31"

MISSING = object()
"""Passed as an override to remove a key rather than change it."""


def wrapped(data: bytes) -> dict[str, Any]:
    """Key material, nested the two levels the format nests it - see bundle.py."""
    return {"key": {"data": data}}


def owned_beacon(**overrides: Any) -> dict[str, Any]:
    """An `OwnedBeacons` record for an AirTag."""
    record: dict[str, Any] = {
        "identifier": BEACON_ID,
        "privateKey": wrapped(bytes(range(52))),
        "publicKey": wrapped(bytes(range(28))),
        "sharedSecret": wrapped(bytes(range(32))),
        "secondarySharedSecret": wrapped(bytes(range(1, 33))),
        "model": "",
        "productId": 21760,
        "vendorId": 76,
        "systemVersion": "2.0.73",
        "batteryLevel": 1,
        "isZeus": False,
        "pairingDate": datetime(2025, 2, 27, 20, 15, 44),
        "stableIdentifier": ["2001~#001234a12345aaac~#A02BCDEFG1AB"],
        "cloudKitMetadata": b"",
    }
    return _applied(record, overrides)


def naming_record(**overrides: Any) -> dict[str, Any]:
    """A `BeaconNamingRecord`, which names its accessory in `associatedBeacon`."""
    record: dict[str, Any] = {
        "identifier": NAMING_ID,
        "associatedBeacon": BEACON_ID,
        "name": "cat",
        "emoji": "\N{CAT}",
        "roleId": 9,
        "cloudKitMetadata": b"",
    }
    return _applied(record, overrides)


def alignment_record(**overrides: Any) -> dict[str, Any]:
    """A `KeyAlignmentRecord`, which names its accessory in `beaconIdentifier` instead."""
    record: dict[str, Any] = {
        "identifier": ALIGNMENT_ID,
        "beaconIdentifier": BEACON_ID,
        "lastIndexObserved": 48090,
        "lastIndexObservationDate": datetime(2026, 7, 13, 18, 45, 10),
        "cloudKitMetadata": b"",
    }
    return _applied(record, overrides)


def _applied(record: dict[str, Any], overrides: dict[str, Any]) -> dict[str, Any]:
    for key, value in overrides.items():
        if value is MISSING:
            record.pop(key, None)
        else:
            record[key] = value
    return record
