"""
Write the bundle fixture that `CustomAccessoryImportTest` reads.

**Written by the real exporter, not by the test.** The point of the fixture is to check the
app against the format something else produces, so a copy hand-assembled here would only ever
check the app against itself - and would keep passing on the day `bundle.py` changed shape.
The locked-bundle fixture exists for the same reason; see CONTRIBUTING.

Run from the repository root, with the exporter's environment:

    cd python && uv run python ../scripts/make_custom_accessory_fixture.py

The zip is deliberately *not* passcode-locked. What this fixture is for is the
`CustomAccessories/` entry and the `0.0.3` version that comes with it; encryption is
`LockedBundleTest`'s subject and mixing the two would make a failure ambiguous.
"""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "python"))

from findmy.accessory import FixedRollingKeyPairAccessory  # noqa: E402
from opentagviewer_export.bundle import (  # noqa: E402
    CustomAccessoryExport,
    build_export,
)

OUT = REPO / "app" / "src" / "androidTest" / "assets" / "custom_accessory_fixture.zip"

# Fixed so the fixture is byte-stable between runs: a fixture that changes every time is a
# diff nobody reads. These are not anybody's keys - 28 bytes each, which is the size
# FindMy.py's KeyPair takes, filled with a recognisable pattern.
PRIVATE_KEYS = [bytes([n]) * 28 for n in (0x11, 0x22, 0x33)]

IDENTIFIER = "openhaystack-demo-tag"
NAME = "Bike (self-generated)"

# Not read from the clock, so the bundle is reproducible.
EXPORTED_AT_MS = 1_760_000_000_000


def main() -> None:
    accessory = FixedRollingKeyPairAccessory(
        private_keys=PRIVATE_KEYS,
        name=NAME,
        identifier=IDENTIFIER,
    )

    bundle = build_export(
        [CustomAccessoryExport(mapping=accessory.to_json())],
        via="OpenTagViewer.fixture:0",
        source_user="fixtures@example.invalid",
        exported_at_ms=EXPORTED_AT_MS,
    )

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, content in sorted(bundle.entries.items()):
            archive.writestr(name, content)

    print(f"Wrote {OUT.relative_to(REPO)}")
    for name in sorted(bundle.entries):
        print(f"  {name}")


if __name__ == "__main__":
    main()
