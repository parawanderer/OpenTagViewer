"""
Rebuild the committed macOS exports, and compare with what a Mac actually wrote.

`app/src/test/resources/` holds sanitised copies of real exports - the same files the Android
importer's own tests read. Rebuilding them from their parsed contents is the strongest check
available without an Apple account: it says this writer produces the bytes macOS produces, not
merely bytes that satisfy this writer's own idea of the format.

Two fixtures, and they cover different halves:

- `19032025/` is export format `0.0.1`, from before key alignment records existed. The accessory
  has none, which is the path that must keep working rather than being treated as broken.
- `09082026_2/` is `0.0.2` and carries one, which is the path worth having.
"""

from __future__ import annotations

import plistlib
from pathlib import Path

import pytest
import yaml

from opentagviewer_export import EXPORT_FORMAT_VERSION, AccessoryExport, build_export

FIXTURES = Path(__file__).parents[3] / "app" / "src" / "test" / "resources"

WITHOUT_ALIGNMENT = "19032025"
WITH_ALIGNMENT = "09082026_2"

needs_fixtures = pytest.mark.skipif(
    not FIXTURES.is_dir(),
    reason=f"no export fixtures at {FIXTURES}",
)


def read_export(name: str) -> tuple[dict, dict[str, AccessoryExport], set[str]]:
    """
    Read a committed export back into the parts `build_export` takes.

    Returns its metadata, its accessories keyed by beacon id, and the set of paths it holds - so
    a test can assert on what was rebuilt *and* on what the original contained.
    """
    root = FIXTURES / name

    metadata = yaml.safe_load((root / "OPENTAGVIEWER.yml").read_text())

    paths = {
        # Sorted for a readable failure, and `.DS_Store` and friends are not part of an export.
        str(path.relative_to(root)).replace("\\", "/")
        for path in sorted(root.rglob("*"))
        if path.is_file() and (path.suffix == ".plist" or path.name == "OPENTAGVIEWER.yml")
    }

    accessories = {}
    for beacon_plist in sorted((root / "OwnedBeacons").glob("*.plist")):
        beacon_id = beacon_plist.stem
        naming = next((root / "BeaconNamingRecord" / beacon_id).glob("*.plist"))
        alignment = next((root / "KeyAlignmentRecords" / beacon_id).glob("*.plist"), None)

        accessories[beacon_id] = AccessoryExport(
            owned_beacon=plistlib.loads(beacon_plist.read_bytes()),
            naming_record=plistlib.loads(naming.read_bytes()),
            key_alignment_record=plistlib.loads(alignment.read_bytes()) if alignment else None,
        )

    return metadata, accessories, paths


def rebuild(name: str, accessories: dict[str, AccessoryExport]):
    """Rebuild a bundle with the metadata the original recorded, so the comparison is fair."""
    metadata, _, _ = read_export(name)

    return build_export(
        list(accessories.values()),
        via=metadata["via"],
        source_user=metadata["sourceUser"],
        exported_at_ms=metadata["exportTimestamp"],
    )


@needs_fixtures
@pytest.mark.parametrize("name", [WITHOUT_ALIGNMENT, WITH_ALIGNMENT])
def test_rebuilds_the_same_set_of_files(name):
    _, accessories, paths = read_export(name)

    assert set(rebuild(name, accessories).entries) == paths


@needs_fixtures
@pytest.mark.parametrize("name", [WITHOUT_ALIGNMENT, WITH_ALIGNMENT])
def test_rebuilds_every_record_byte_for_byte(name):
    # Not "parses to the same thing" - the same bytes. plistlib's XML output matches what macOS
    # wrote exactly, down to the tab indentation and the sorted keys, so any drift in how this
    # writer serialises a record shows up here rather than on someone's phone.
    _, accessories, _ = read_export(name)
    bundle = rebuild(name, accessories)

    for path, content in bundle.entries.items():
        if path.endswith(".plist"):
            assert content == (FIXTURES / name / path).read_bytes(), path


@needs_fixtures
def test_rebuilds_the_metadata_file_byte_for_byte():
    # The current-format fixture only. Rebuilding the 0.0.1 one writes 0.0.2, which is correct:
    # this writes the format it emits, not the format it happened to read - see the next test.
    _, accessories, _ = read_export(WITH_ALIGNMENT)
    bundle = rebuild(WITH_ALIGNMENT, accessories)

    assert bundle.entries["OPENTAGVIEWER.yml"] == (
        FIXTURES / WITH_ALIGNMENT / "OPENTAGVIEWER.yml"
    ).read_bytes()


@needs_fixtures
def test_an_older_export_is_rebuilt_at_the_current_format_version():
    metadata, accessories, _ = read_export(WITHOUT_ALIGNMENT)
    rebuilt = yaml.safe_load(rebuild(WITHOUT_ALIGNMENT, accessories).entries["OPENTAGVIEWER.yml"])

    assert metadata["version"] == "0.0.1"
    assert rebuilt["version"] == EXPORT_FORMAT_VERSION
    # Everything else is the caller's to supply, and is passed through untouched.
    assert rebuilt["via"] == metadata["via"]
    assert rebuilt["sourceUser"] == metadata["sourceUser"]
    assert rebuilt["exportTimestamp"] == metadata["exportTimestamp"]


@needs_fixtures
def test_an_export_without_an_alignment_record_rebuilds_without_one():
    # Format 0.0.1 predates them entirely. Absence is not an error, and this is the fixture that
    # says so - the import is slower, not broken.
    _, accessories, paths = read_export(WITHOUT_ALIGNMENT)

    assert not any(path.startswith("KeyAlignmentRecords/") for path in paths)
    assert all(a.key_alignment_record is None for a in accessories.values())
    assert not any(path.startswith("KeyAlignmentRecords/") for path in rebuild(WITHOUT_ALIGNMENT, accessories).entries)


@needs_fixtures
def test_an_export_with_an_alignment_record_keeps_it_under_its_accessory():
    _, accessories, _ = read_export(WITH_ALIGNMENT)
    bundle = rebuild(WITH_ALIGNMENT, accessories)

    aligned = [path for path in bundle.entries if path.startswith("KeyAlignmentRecords/")]

    assert len(aligned) == 1
    beacon_id = next(iter(accessories))
    assert aligned[0].startswith(f"KeyAlignmentRecords/{beacon_id}/")
