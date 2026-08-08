"""Generate synthetic OwnedBeacons plists for tests.

The key material produced here is deterministic filler - derived from a fixed
formula, not from any real accessory - so the output is safe to commit. It is
structurally valid enough for FindMy.py's ``FindMyAccessory.from_plist()``,
which is all the tests need: none of them decrypt anything or contact Apple.

The one field that genuinely matters for behaviour is ``pairingDate``. Without a
key alignment record (which the export wizard does not capture) FindMy.py falls
back to ``alignment_date = paired_at, alignment_index = 0``, so the size of the
first-fetch key search is a pure function of how old the tag is. That is why the
fixtures come in several ages.

Usage:
    python scripts/make_test_beacon_plist.py <output.plist> [--days-old N] [--kind airtag|idevice]
"""

from __future__ import annotations

import argparse
import plistlib
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path


def _filler(length: int, seed: int) -> bytes:
    """Deterministic non-random bytes. Obviously synthetic, never a real key."""
    return bytes((i * seed + seed) % 256 for i in range(length))


def build(days_old: int, kind: str) -> dict:
    paired = datetime.now(tz=timezone.utc) - timedelta(days=days_old)
    # plistlib writes naive datetimes as UTC, which is how the real records look.
    paired = paired.replace(tzinfo=None, microsecond=0)

    doc = {
        # PRIVATE master key - 28 bytes are read from the tail.
        "privateKey": {"key": {"data": _filler(28, 7)}},
        # "Primary" shared secret.
        "sharedSecret": {"key": {"data": _filler(32, 11)}},
        "pairingDate": paired,
        "model": "A2187" if kind == "airtag" else "iPhone14,2",
        "identifier": "AABBCCDD-1122-4333-8444-555566667777",
    }

    if kind == "airtag":
        doc["secondarySharedSecret"] = {"key": {"data": _filler(32, 13)}}
    else:
        # iDevices carry the secondary secret under a different key.
        doc["secureLocationsSharedSecret"] = {"key": {"data": _filler(32, 13)}}

    return doc


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("output", type=Path)
    parser.add_argument("--days-old", type=int, default=730, help="age of the pairing (default: 730)")
    parser.add_argument("--kind", choices=("airtag", "idevice"), default="airtag")
    args = parser.parse_args(argv[1:])

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("wb") as fp:
        plistlib.dump(build(args.days_old, args.kind), fp, fmt=plistlib.FMT_XML)

    print(f"wrote {args.output} ({args.kind}, paired {args.days_old} days ago)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
