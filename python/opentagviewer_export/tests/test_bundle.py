"""
What `build_export` writes, and what it refuses to.

The refusals are the point of most of this. Every one of them is a bundle that would have been
produced without complaint and then failed on the recipient's phone - or worse, imported cleanly
with a tag missing, which reports success and looks like the export never included it.
"""

from __future__ import annotations

import plistlib
from datetime import datetime, timedelta, timezone

import pytest
import yaml

from opentagviewer_export import AccessoryExport, ExportError, build_export
from opentagviewer_export.tests.records import (
    ALIGNMENT_ID,
    BEACON_ID,
    MISSING,
    NAMING_ID,
    OTHER_BEACON_ID,
    OTHER_NAMING_ID,
    alignment_record,
    naming_record,
    owned_beacon,
    wrapped,
)

VIA = "OpenTagViewer.wizard:1.1.0"
SOURCE_USER = "someone"
EXPORTED_AT_MS = 1786282770586


def build(*accessories: AccessoryExport, **overrides):
    """Build a bundle from whole accessories, with the metadata a caller has to supply."""
    arguments = {"via": VIA, "source_user": SOURCE_USER, "exported_at_ms": EXPORTED_AT_MS}
    arguments.update(overrides)
    return build_export(list(accessories), **arguments)


def accessory(**overrides) -> AccessoryExport:
    """One complete accessory, overridable a record at a time."""
    records = {
        "owned_beacon": owned_beacon(),
        "naming_record": naming_record(),
        "key_alignment_record": alignment_record(),
    }
    records.update(overrides)
    return AccessoryExport(**records)


class TestLayout:
    def test_writes_exactly_the_four_files_the_format_specifies(self):
        bundle = build(accessory())

        assert set(bundle.entries) == {
            "OPENTAGVIEWER.yml",
            f"OwnedBeacons/{BEACON_ID}.plist",
            f"BeaconNamingRecord/{BEACON_ID}/{NAMING_ID}.plist",
            f"KeyAlignmentRecords/{BEACON_ID}/{ALIGNMENT_ID}.plist",
        }

    def test_the_alignment_directory_is_plural_and_the_others_are_not(self):
        # There is no reason for it; it is simply the name. A bundle with `KeyAlignmentRecord`
        # imports, and every accessory in it then searches its whole key history.
        paths = set(build(accessory()).entries)

        assert any(path.startswith("KeyAlignmentRecords/") for path in paths)
        assert not any(path.startswith("KeyAlignmentRecord/") for path in paths)
        assert any(path.startswith("BeaconNamingRecord/") for path in paths)
        assert not any(path.startswith("BeaconNamingRecords/") for path in paths)

    def test_every_file_is_a_plist_even_though_macos_calls_two_of_them_records(self):
        paths = [path for path in build(accessory()) .entries if path != "OPENTAGVIEWER.yml"]

        assert paths, "expected some records"
        assert all(path.endswith(".plist") for path in paths)

    def test_an_alignment_record_is_filed_under_its_accessory_not_flat(self):
        # It carries no `associatedBeacon`, unlike a naming record: the association *is* the
        # directory, so a flat layout loses which tag the record belongs to.
        bundle = build(accessory())

        assert f"KeyAlignmentRecords/{BEACON_ID}/{ALIGNMENT_ID}.plist" in bundle.entries

    def test_plists_are_xml_because_the_importer_reads_them_with_xpath(self):
        bundle = build(accessory())

        for path, content in bundle.entries.items():
            if path.endswith(".plist"):
                assert content.startswith(b"<?xml"), path
                plistlib.loads(content)  # parses, and as XML

    def test_an_accessory_with_no_alignment_record_is_exported_without_one(self):
        # Absence is normal - not every accessory has one - and it is not an error.
        bundle = build(accessory(key_alignment_record=None))

        assert not any(path.startswith("KeyAlignmentRecords/") for path in bundle.entries)
        assert f"OwnedBeacons/{BEACON_ID}.plist" in bundle.entries

    def test_only_the_accessories_given_are_written(self):
        # The list is the selection. Handing over one tag and handing over a household's whole
        # set are different acts, and exported keys cannot be withdrawn afterwards.
        chosen = accessory()
        build(chosen)  # the other accessory exists, and is simply not passed

        bundle = build(chosen)

        assert not any(OTHER_BEACON_ID in path for path in bundle.entries)


class TestMetadata:
    def test_carries_the_four_keys_the_apps_schema_requires(self):
        document = yaml.safe_load(build(accessory()).entries["OPENTAGVIEWER.yml"])

        assert document == {
            "version": "0.0.2",
            "exportTimestamp": EXPORTED_AT_MS,
            "sourceUser": SOURCE_USER,
            "via": VIA,
        }

    def test_the_producer_is_whatever_the_caller_said_it_was(self):
        # Never invented in here: the desktop exporter, its CLI and the Android app are three
        # producers, and `via:` is how anyone looking at a zip later works out which one built it.
        document = yaml.safe_load(build(accessory(), via="OpenTagViewer.android:2.3.4").entries["OPENTAGVIEWER.yml"])

        assert document["via"] == "OpenTagViewer.android:2.3.4"

    @pytest.mark.parametrize("blank", ["", "   "])
    def test_refuses_a_blank_producer(self, blank):
        with pytest.raises(ExportError, match="via"):
            build(accessory(), via=blank)

    @pytest.mark.parametrize("blank", ["", "   "])
    def test_refuses_a_blank_source_user(self, blank):
        # The app's import schema lists it as required, so an export without it fails validation
        # on the phone rather than here.
        with pytest.raises(ExportError, match="source_user"):
            build(accessory(), source_user=blank)

    @pytest.mark.parametrize("bad", [0, -1, "1786282770586", 1786282770586.0, True])
    def test_refuses_a_timestamp_that_is_not_a_positive_integer(self, bad):
        with pytest.raises(ExportError, match="exported_at_ms"):
            build(accessory(), exported_at_ms=bad)


class TestIdentifiers:
    def test_uppercases_an_identifier_in_both_the_path_and_the_record(self):
        # The importer's regex accepts uppercase hex only, and skips anything else silently. The
        # record's own field is uppercased with it, so nothing downstream sees the two disagree.
        lower = BEACON_ID.lower()
        bundle = build(accessory(
            owned_beacon=owned_beacon(identifier=lower),
            naming_record=naming_record(associatedBeacon=lower),
        ))

        assert f"OwnedBeacons/{BEACON_ID}.plist" in bundle.entries
        written = plistlib.loads(bundle.entries[f"OwnedBeacons/{BEACON_ID}.plist"])
        assert written["identifier"] == BEACON_ID

    @pytest.mark.parametrize(
        "bad",
        [
            "725A989D-D871-19A7-B2FE-948C24F356AB",  # version nibble is not 4
            "725A989D-D871-49A7-72FE-948C24F356AB",  # variant nibble is not 8, 9, A or B
            "725A989D-D871-49A7-B2FE-948C24F356",  # too short
            "not a uuid at all",
        ],
    )
    def test_refuses_an_identifier_the_importer_would_skip_without_saying_so(self, bad):
        with pytest.raises(ExportError, match="version-4 UUID"):
            build(accessory(owned_beacon=owned_beacon(identifier=bad)))

    def test_refuses_an_identifier_that_is_not_a_string(self):
        with pytest.raises(ExportError, match="identifiers are strings"):
            build(accessory(owned_beacon=owned_beacon(identifier=7)))

    def test_refuses_the_same_accessory_twice(self):
        # The second copy would overwrite the first and the bundle would carry no sign of it.
        with pytest.raises(ExportError, match="twice"):
            build(accessory(), accessory())

    def test_refuses_an_empty_selection(self):
        with pytest.raises(ExportError, match="Nothing was selected"):
            build()


class TestTheRecordsMustBeUsable:
    def test_refuses_a_beacon_with_no_private_key(self):
        # The test the macOS exporter has always used, and the reason: a record with no private
        # key is one of the owner's own devices, which cannot be located. Exporting it produces an
        # entry that can never do anything.
        with pytest.raises(ExportError, match="privateKey"):
            build(accessory(owned_beacon=owned_beacon(privateKey=MISSING)))

    @pytest.mark.parametrize("field", ["sharedSecret", "model", "pairingDate"])
    def test_refuses_a_beacon_missing_a_field_the_importer_subscripts(self, field):
        with pytest.raises(ExportError, match=field):
            build(accessory(owned_beacon=owned_beacon(**{field: MISSING})))

    def test_refuses_a_beacon_with_neither_secondary_secret(self):
        with pytest.raises(ExportError, match="secondary keys"):
            build(accessory(owned_beacon=owned_beacon(secondarySharedSecret=MISSING)))

    def test_accepts_a_device_style_secondary_secret(self):
        # An accessory carries `secondarySharedSecret`; an iPhone, iPad or Mac carries
        # `secureLocationsSharedSecret` in the same position, and FindMy.py reads either.
        bundle = build(accessory(owned_beacon=owned_beacon(
            secondarySharedSecret=MISSING,
            secureLocationsSharedSecret=wrapped(bytes(range(32))),
        )))

        assert f"OwnedBeacons/{BEACON_ID}.plist" in bundle.entries

    @pytest.mark.parametrize("unwrapped", [b"\x00" * 32, {"data": b"\x00" * 32}, "hex"])
    def test_refuses_key_material_that_is_not_nested_the_way_the_format_nests_it(self, unwrapped):
        # Bare bytes produce a file that fails to import for a reason nothing will explain.
        with pytest.raises(ExportError, match="nested two levels deep"):
            build(accessory(owned_beacon=owned_beacon(privateKey=unwrapped)))

    def test_refuses_a_pairing_date_that_is_not_a_date(self):
        with pytest.raises(ExportError, match="pairingDate"):
            build(accessory(owned_beacon=owned_beacon(pairingDate="2025-02-27")))

    def test_refuses_a_null(self):
        # Property lists have no null, so this would raise inside plistlib with a message about
        # types rather than about the field.
        with pytest.raises(ExportError, match="no null"):
            build(accessory(owned_beacon=owned_beacon(model=None)))


class TestTheJoinMustAgree:
    def test_refuses_a_naming_record_that_names_a_different_accessory(self):
        # A naming record names its accessory in `associatedBeacon`; a mismatch means the join
        # upstream paired the wrong two records.
        with pytest.raises(ExportError, match="associatedBeacon"):
            build(accessory(naming_record=naming_record(associatedBeacon=OTHER_BEACON_ID)))

    def test_refuses_an_alignment_record_that_names_a_different_accessory(self):
        # And an alignment record names it in `beaconIdentifier`. The two look symmetrical and
        # are not, which is why both are checked.
        with pytest.raises(ExportError, match="beaconIdentifier"):
            build(accessory(key_alignment_record=alignment_record(beaconIdentifier=OTHER_BEACON_ID)))

    def test_accepts_a_naming_record_that_does_not_name_its_accessory_at_all(self):
        # The association is the directory. A record without the field is filed correctly anyway.
        bundle = build(accessory(naming_record=naming_record(associatedBeacon=MISSING)))

        assert f"BeaconNamingRecord/{BEACON_ID}/{NAMING_ID}.plist" in bundle.entries

    @pytest.mark.parametrize("field", ["lastIndexObserved", "lastIndexObservationDate"])
    def test_refuses_a_half_filled_alignment_record(self, field):
        # FindMy.py subscripts both when an alignment record is supplied, so half of one turns an
        # import that would have been slow into one that fails.
        with pytest.raises(ExportError, match="alignment record"):
            build(accessory(key_alignment_record=alignment_record(**{field: MISSING})))

    def test_refuses_a_naming_record_with_no_identifier_of_its_own(self):
        with pytest.raises(ExportError, match="identifier"):
            build(accessory(naming_record=naming_record(identifier=MISSING)))


class TestDates:
    def test_a_date_in_another_zone_is_converted_rather_than_relabelled(self):
        # plistlib formats a datetime from its own fields and ignores tzinfo entirely, so an
        # unconverted date in +02:00 would be written as though its local time were UTC: a
        # pairing date wrong by two hours, silently, in a file nobody re-reads.
        berlin = timezone(timedelta(hours=2))
        paired = datetime(2025, 2, 27, 22, 15, 44, tzinfo=berlin)

        bundle = build(accessory(owned_beacon=owned_beacon(pairingDate=paired)))
        written = plistlib.loads(bundle.entries[f"OwnedBeacons/{BEACON_ID}.plist"])

        assert written["pairingDate"] == datetime(2025, 2, 27, 20, 15, 44)
        assert b"<date>2025-02-27T20:15:44Z</date>" in bundle.entries[f"OwnedBeacons/{BEACON_ID}.plist"]

    def test_a_date_already_in_utc_is_left_where_it_is(self):
        paired = datetime(2025, 2, 27, 20, 15, 44, tzinfo=timezone.utc)

        bundle = build(accessory(owned_beacon=owned_beacon(pairingDate=paired)))
        written = plistlib.loads(bundle.entries[f"OwnedBeacons/{BEACON_ID}.plist"])

        assert written["pairingDate"] == datetime(2025, 2, 27, 20, 15, 44)


class TestSeveralAccessories:
    def test_each_gets_its_own_directory(self):
        second = AccessoryExport(
            owned_beacon=owned_beacon(identifier=OTHER_BEACON_ID),
            naming_record=naming_record(
                identifier=OTHER_NAMING_ID,
                associatedBeacon=OTHER_BEACON_ID,
                name="keys",
            ),
        )

        bundle = build(accessory(), second)

        assert set(bundle.entries) == {
            "OPENTAGVIEWER.yml",
            f"OwnedBeacons/{BEACON_ID}.plist",
            f"BeaconNamingRecord/{BEACON_ID}/{NAMING_ID}.plist",
            f"KeyAlignmentRecords/{BEACON_ID}/{ALIGNMENT_ID}.plist",
            f"OwnedBeacons/{OTHER_BEACON_ID}.plist",
            f"BeaconNamingRecord/{OTHER_BEACON_ID}/{OTHER_NAMING_ID}.plist",
        }

    def test_one_bad_accessory_fails_the_whole_export(self):
        # Rather than exporting the rest and leaving a bundle quietly missing a tag the user
        # selected: a partial success is indistinguishable from a complete one on the phone.
        broken = AccessoryExport(
            owned_beacon=owned_beacon(identifier=OTHER_BEACON_ID, privateKey=MISSING),
            naming_record=naming_record(identifier=OTHER_NAMING_ID, associatedBeacon=OTHER_BEACON_ID),
        )

        with pytest.raises(ExportError):
            build(accessory(), broken)


class TestPurity:
    def test_the_caller_s_records_are_not_modified(self):
        # The caller may still be holding these - showing them in a list, exporting them again -
        # and normalisation happens on a copy.
        beacon = owned_beacon(identifier=BEACON_ID.lower())
        before = dict(beacon)

        build(accessory(owned_beacon=beacon, naming_record=naming_record(associatedBeacon=BEACON_ID.lower())))

        assert beacon == before

    def test_the_same_input_produces_the_same_bytes(self):
        assert build(accessory()).entries == build(accessory()).entries
