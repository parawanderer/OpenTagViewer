"""Check whether a real OpenTagViewer export can be parsed by FindMy 0.9.x.

Run this against your own export directory before upgrading. It answers one
question: would the lazy accessory_json backfill succeed for each of your
beacons, or would any of them be silently skipped and stop updating?

It deliberately prints only derived facts - parse success, which optional plist
fields are present, tag age, and the resulting first-fetch key-search size. It
never prints file contents, key material, identifiers, or filenames, so the
output is safe to paste into an issue.

Usage:
    python scripts/check_export_compatibility.py <export-dir-or-OwnedBeacons-dir>
"""

from __future__ import annotations

import argparse
import plistlib
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

try:
    from findmy import FindMyAccessory
except ImportError:
    print(
        "FindMy is not installed in this interpreter.\n"
        "  python -m venv .venv && .venv/Scripts/pip install 'FindMy==0.9.8'",
        file=sys.stderr,
    )
    raise SystemExit(2) from None

# Fields from_plist reads. Presence, not value, is what we report.
REQUIRED = ("privateKey", "sharedSecret", "pairingDate", "model", "identifier")
SECONDARY = ("secondarySharedSecret", "secureLocationsSharedSecret")


def _locate(root: Path) -> list[Path]:
    owned = root / "OwnedBeacons"
    search_root = owned if owned.is_dir() else root
    return sorted(p for p in search_root.rglob("*.plist") if p.is_file())


def _field_report(path: Path) -> tuple[list[str], str | None]:
    """Which required fields are missing, and which secondary-secret variant is used."""
    try:
        with path.open("rb") as fp:
            doc = plistlib.load(fp)
    except Exception:
        return list(REQUIRED), None

    missing = [f for f in REQUIRED if f not in doc]
    variant = next((f for f in SECONDARY if f in doc), None)
    return missing, variant


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("path", type=Path)
    parser.add_argument("--hours-back", type=int, default=24)
    args = parser.parse_args(argv[1:])

    if not args.path.exists():
        print(f"no such path: {args.path}", file=sys.stderr)
        return 2

    plists = _locate(args.path)
    if not plists:
        print(f"no .plist files found under {args.path}", file=sys.stderr)
        return 2

    now = datetime.now(tz=timezone.utc)
    start = now - timedelta(hours=args.hours_back)

    print(f"Checking {len(plists)} beacon(s) against FindMy 0.9.x\n")

    failed = 0
    worst = 0
    for position, path in enumerate(plists, start=1):
        missing, variant = _field_report(path)

        try:
            accessory = FindMyAccessory.from_plist(path)
        except Exception as exc:  # noqa: BLE001 - the error type is the useful signal
            failed += 1
            print(f"  beacon #{position}: PARSE FAILED - {type(exc).__name__}: {exc}")
            if missing:
                print(f"    missing fields: {', '.join(missing)}")
            print("    -> would be skipped by the backfill and stop updating\n")
            continue

        paired = accessory.paired_at
        if paired.tzinfo is None:
            paired = paired.replace(tzinfo=timezone.utc)
        age_days = (now - paired).days

        keys = max(0, accessory.get_max_index(now) - accessory.get_min_index(start) + 1)
        worst = max(worst, keys)

        print(f"  beacon #{position}: OK")
        print(f"    model              {accessory.model}")
        print(f"    secondary secret   {variant or '(none found)'}")
        print(f"    tag age            {age_days} days")
        print(f"    first-fetch keys   {keys:,}")

        try:
            FindMyAccessory.from_json(accessory.to_json())
            print("    json round trip    OK")
        except Exception as exc:  # noqa: BLE001
            failed += 1
            print(f"    json round trip    FAILED - {type(exc).__name__}: {exc}")
        print()

    print(f"Worst-case first fetch across all beacons: {worst:,} keys")
    if failed:
        print(f"\n{failed} beacon(s) would not survive the upgrade.", file=sys.stderr)
        return 1
    print("\nAll beacons parse and round-trip cleanly.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
