"""
Write the locked-bundle fixture the Android importer's tests read.

**Why a committed fixture rather than a zip the test builds itself.** The thing that can break
is *interoperability*: the exporter writes AES-256 in the WinZip scheme through `pyzipper`, and
the app reads it through zip4j. A test that both writes and reads with zip4j proves zip4j agrees
with itself, which is not the question. This produces the bytes the real exporter produces, using
the real exporter's own code, so the test that opens it is testing the thing that would actually
go wrong.

**And the passcode is the other half of it.** The code below is normalised by
`opentagviewer_export.passcode.normalise_passcode`; the app normalises what the user types with
`BundlePasscode.normalise`. A zip password is compared as bytes, so if those two ever disagree
the fixture stops opening - which is the point.

Regenerate after any change to `zipsink.py` or `passcode.py`:

    python scripts/make_locked_bundle_fixture.py

It is deterministic apart from the AES salts, which are random per entry by design, so expect
the bytes to differ every run even when nothing has changed.
"""

from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "python"))

from opentagviewer_export.bundle import ExportBundle  # noqa: E402
from opentagviewer_export.passcode import normalise_passcode  # noqa: E402
from opentagviewer_export.zipsink import write_zip  # noqa: E402

OUTPUT = REPO_ROOT / "app" / "src" / "androidTest" / "assets" / "locked_bundle_fixture.zip"

PASSCODE_AS_DISPLAYED = "H4K2-9WMR-7TQX"
"""
Written the way the exporter shows it, hyphens and all.

The test types this form, so the grouping has to survive normalisation on the Android side too -
that is a real thing a user does and a real way it could break.
"""

BEACON = "0FB0AEAC-C083-405E-A979-4AA6A73F5C56"

MANIFEST = (
    "version: 0.0.2\n"
    "exportTimestamp: 1740685990163\n"
    "via: OpenTagViewer.fixture:0.0.0\n"
    "sourceUser: fixture\n"
)

OWNED_BEACON = (
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<plist version="1.0"><dict>'
    "<key>identifier</key><string>" + BEACON + "</string>"
    "<key>name</key><string>Fixture Tag</string>"
    "</dict></plist>\n"
)

NAMING_RECORD = (
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<plist version="1.0"><dict>'
    "<key>name</key><string>Fixture Tag</string>"
    "</dict></plist>\n"
)


def main() -> int:
    entries = {
        "OPENTAGVIEWER.yml": MANIFEST,
        f"OwnedBeacons/{BEACON}.plist": OWNED_BEACON,
        f"BeaconNamingRecord/{BEACON}/19FDE267-DE22-4B9A-BC44-E22C5970FCDC.plist": NAMING_RECORD,
    }

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)

    bundle = ExportBundle(
        entries={name: content.encode("utf-8") for name, content in entries.items()},
        exported_at_ms=1740685990163,
    )

    write_zip(bundle, OUTPUT, password=normalise_passcode(PASSCODE_AS_DISPLAYED))

    print(f"Wrote {OUTPUT.relative_to(REPO_ROOT)} ({OUTPUT.stat().st_size} bytes)")
    print(f"Unlock code: {PASSCODE_AS_DISPLAYED}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
