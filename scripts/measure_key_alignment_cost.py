"""Measure the first-fetch key-search cost for an exported beacon plist.

Why this exists
---------------
FindMy.py's ``FindMyAccessory.from_plist()`` accepts an optional second plist
(``key_alignment_plist``) carrying ``lastIndexObservationDate`` /
``lastIndexObserved``. OpenTagViewer's export wizard only captures
``OwnedBeacons/`` and ``BeaconNamingRecord/``, so that argument is always None
and the accessory falls back to::

    alignment_date  = paired_at
    alignment_index = 0

Key lookup then spans ``range(get_min_index(start), get_max_index(end) + 1)``.
For a tag paired long ago that is a large number of keys on the *first* fetch
after migrating to 0.9.x - which is exactly the "spam queries to Apple and get
your account flagged" risk raised in issue #30.

After the first successful fetch, ``update_alignment()`` narrows the window and
BeaconRepository#storeFetchResult persists it, so this is a one-time cost per
beacon. This script quantifies that one-time cost so we can decide whether the
first fetch needs to be staged.

This is read-only and offline: it never contacts Apple and never needs an
account. Nothing it reads is written anywhere.

Usage
-----
    python scripts/measure_key_alignment_cost.py <plist-or-directory> [--hours-back 24]

Accepts a single ``<UUID>.plist`` or a directory (scanned recursively).
"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

try:
    from findmy import FindMyAccessory
except ImportError:
    print(
        "FindMy is not installed in this interpreter.\n"
        "  python -m venv .venv && .venv/bin/pip install 'FindMy==0.9.8'",
        file=sys.stderr,
    )
    raise SystemExit(2) from None


# Rough guide only: what counts as "fine" vs "worth staging" for a single first fetch.
WARN_KEY_COUNT = 5_000
BAD_KEY_COUNT = 25_000


def _describe(accessory: FindMyAccessory, hours_back: int) -> dict:
    now = datetime.now(tz=timezone.utc)
    start = now - timedelta(hours=hours_back)

    min_index = accessory.get_min_index(start)
    max_index = accessory.get_max_index(now)
    key_count = max(0, max_index - min_index + 1)

    paired_at = accessory.paired_at
    if paired_at.tzinfo is None:
        paired_at = paired_at.replace(tzinfo=timezone.utc)
    age_days = (now - paired_at).days

    return {
        "identifier": getattr(accessory, "identifier", "?"),
        "model": getattr(accessory, "model", "?"),
        "paired_at": paired_at,
        "age_days": age_days,
        "min_index": min_index,
        "max_index": max_index,
        "key_count": key_count,
    }


def _verdict(key_count: int) -> str:
    if key_count >= BAD_KEY_COUNT:
        return "HIGH - stage the first fetch"
    if key_count >= WARN_KEY_COUNT:
        return "elevated"
    return "fine"


def _round_trips_cleanly(accessory: FindMyAccessory) -> bool:
    """The migration path is plist -> to_json -> stored -> from_json, so prove it survives."""
    try:
        restored = FindMyAccessory.from_json(accessory.to_json())
    except Exception as exc:  # noqa: BLE001 - reporting, not handling
        print(f"    ! to_json/from_json round trip FAILED: {exc}")
        return False

    same = (
        getattr(restored, "identifier", None) == getattr(accessory, "identifier", None)
        and restored.paired_at == accessory.paired_at
    )
    if not same:
        print("    ! round trip lost or altered identity fields")
    return same


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("path", type=Path, help="a .plist file, or a directory to scan recursively")
    parser.add_argument("--hours-back", type=int, default=24, help="fetch window to simulate (default: 24)")
    parser.add_argument(
        "--show-identifiers",
        action="store_true",
        help="print accessory UUIDs and filenames. Off by default: the key counts are the "
             "point, and the identifiers are not needed to interpret them.",
    )
    args = parser.parse_args(argv[1:])

    if not args.path.exists():
        print(f"no such path: {args.path}", file=sys.stderr)
        return 2

    if args.path.is_dir():
        plists = sorted(p for p in args.path.rglob("*.plist") if p.is_file())
    else:
        plists = [args.path]

    if not plists:
        print(f"no .plist files found under {args.path}", file=sys.stderr)
        return 2

    print(f"Simulating a {args.hours_back}h fetch for {len(plists)} beacon(s), "
          f"with no key alignment record (the export wizard does not capture one).\n")

    failures = 0
    worst = 0
    for position, plist in enumerate(plists, start=1):
        label = plist.name if args.show_identifiers else f"beacon #{position}"

        try:
            accessory = FindMyAccessory.from_plist(plist)
        except Exception as exc:  # noqa: BLE001 - we want to know which one failed
            print(f"  {label}: FAILED to parse - {exc}")
            failures += 1
            continue

        info = _describe(accessory, args.hours_back)
        worst = max(worst, info["key_count"])

        print(f"  {label}")
        identity = f"   identifier {info['identifier']}" if args.show_identifiers else ""
        print(f"    model            {info['model']}{identity}")
        print(f"    paired           {info['paired_at']:%Y-%m-%d} ({info['age_days']} days ago)")
        print(f"    index range      {info['min_index']} .. {info['max_index']}")
        print(f"    keys first fetch {info['key_count']:,}  [{_verdict(info['key_count'])}]")
        _round_trips_cleanly(accessory)
        print()

    print(f"Worst-case first fetch: {worst:,} keys  [{_verdict(worst)}]")
    if failures:
        print(f"{failures} plist(s) could not be parsed by FindMy 0.9.x - these would be "
              f"silently skipped by the backfill.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
