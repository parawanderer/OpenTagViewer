"""
Self-generated accessories in a bundle: their own directory, and their own format version.

A tag whose keys were never in an Apple account cannot be written as a plist - the layout is built
around the inputs to Apple's key derivation, and this kind has none of them. So it goes in as JSON,
and the bundle says so.
"""

from __future__ import annotations

import json

import pytest
import yaml

from opentagviewer_export import (
    CUSTOM_ACCESSORY_TYPE,
    EXPORT_FORMAT_VERSION,
    EXPORT_FORMAT_VERSION_WITH_CUSTOM,
    AccessoryExport,
    CustomAccessoryExport,
    ExportError,
    build_export,
)
from opentagviewer_export.tests.records import BEACON_ID, alignment_record, naming_record, owned_beacon

VIA = "OpenTagViewer.app:1.1.0"
EXPORTED_AT_MS = 1786282770586

KEY_A = bytes(range(28)).hex()
KEY_B = bytes(range(100, 128)).hex()


def custom(**overrides) -> CustomAccessoryExport:
    mapping = {
        "type": CUSTOM_ACCESSORY_TYPE,
        "private_keys": [KEY_A, KEY_B],
        "name": "bike",
        "identifier": "bike-1234",
    }
    mapping.update(overrides)
    return CustomAccessoryExport(mapping=mapping)


def paired() -> AccessoryExport:
    return AccessoryExport(
        owned_beacon=owned_beacon(),
        naming_record=naming_record(),
        key_alignment_record=alignment_record(),
    )


def build(*accessories, **overrides):
    arguments = {"via": VIA, "source_user": "someone", "exported_at_ms": EXPORTED_AT_MS}
    arguments.update(overrides)
    return build_export(list(accessories), **arguments)


class TestLayout:
    def test_is_written_as_json_in_its_own_directory(self):
        bundle = build(custom())

        assert set(bundle.entries) == {"OPENTAGVIEWER.yml", "CustomAccessories/bike-1234.json"}

    def test_carries_the_mapping_findmy_reads_back(self):
        bundle = build(custom())

        written = json.loads(bundle.entries["CustomAccessories/bike-1234.json"])
        assert written == {
            "type": CUSTOM_ACCESSORY_TYPE,
            "private_keys": [KEY_A, KEY_B],
            "name": "bike",
            "identifier": "bike-1234",
        }

    def test_a_name_with_an_emoji_survives(self):
        bundle = build(custom(name="bike \N{BICYCLE}"))

        written = json.loads(bundle.entries["CustomAccessories/bike-1234.json"].decode("utf-8"))
        assert written["name"] == "bike \N{BICYCLE}"

    def test_an_identifier_that_would_escape_the_bundle_is_made_safe(self):
        # These come out of files other tools wrote, so unlike the UUIDs elsewhere there is no
        # shape to insist on - only that it names a file inside the bundle and nothing outside it.
        bundle = build(custom(identifier="../../etc/passwd"))

        assert set(bundle.entries) == {"OPENTAGVIEWER.yml", "CustomAccessories/.._.._etc_passwd.json"}

    @pytest.mark.parametrize("identifier", ["", "   ", "///", "...", None, 7])
    def test_refuses_an_identifier_that_cannot_name_a_file(self, identifier):
        with pytest.raises(ExportError, match="identifier"):
            build(custom(identifier=identifier))


class TestTheFormatVersion:
    def test_says_0_0_3_when_the_bundle_carries_one(self):
        document = yaml.safe_load(build(custom()).entries["OPENTAGVIEWER.yml"])

        assert document["version"] == EXPORT_FORMAT_VERSION_WITH_CUSTOM

    def test_stays_0_0_2_when_it_does_not(self):
        # So an export of Apple-paired tags from this version is byte-identical to one from the
        # version before, and the version means "what a reader has to understand".
        document = yaml.safe_load(build(paired()).entries["OPENTAGVIEWER.yml"])

        assert document["version"] == EXPORT_FORMAT_VERSION

    def test_a_mixed_bundle_says_0_0_3(self):
        document = yaml.safe_load(build(paired(), custom()).entries["OPENTAGVIEWER.yml"])

        assert document["version"] == EXPORT_FORMAT_VERSION_WITH_CUSTOM


class TestMixedBundles:
    def test_both_kinds_are_written_side_by_side(self):
        # The format is chosen per accessory, not per bundle: a bundle holding one of each is
        # normal and has to stay readable.
        bundle = build(paired(), custom())

        assert f"OwnedBeacons/{BEACON_ID}.plist" in bundle.entries
        assert "CustomAccessories/bike-1234.json" in bundle.entries

    def test_two_custom_accessories_with_the_same_identifier_are_refused(self):
        with pytest.raises(ExportError, match="twice"):
            build(custom(), custom(name="other"))


class TestWhatItRefuses:
    def test_requires_a_name(self):
        # A paired accessory has a naming record the owner wrote. This has whatever the file it
        # came from carried, which is often nothing - and an unnamed tag arrives in the app as a
        # row with nothing to tell it apart.
        with pytest.raises(ExportError, match="must be given a name"):
            build(custom(name=None))

    @pytest.mark.parametrize("blank", ["", "   "])
    def test_refuses_a_blank_name(self, blank):
        with pytest.raises(ExportError, match="must be given a name"):
            build(custom(name=blank))

    def test_refuses_a_mapping_with_no_keys(self):
        with pytest.raises(ExportError, match="private_keys"):
            build(custom(private_keys=[]))

    def test_refuses_a_key_that_is_not_hex(self):
        # It would fail at *locate* time on the recipient's phone otherwise, which is a long way
        # from the export that produced it.
        with pytest.raises(ExportError, match="not readable as hex"):
            build(custom(private_keys=["not hex at all"]))

    def test_points_an_apple_paired_mapping_at_the_right_class(self):
        with pytest.raises(ExportError, match="AccessoryExport instead"):
            build(custom(type="accessory"))

    def test_refuses_a_mapping_of_an_unknown_type(self):
        with pytest.raises(ExportError, match="custom_rolling_key_accessory"):
            build(custom(type="something_else"))

    def test_refuses_something_that_is_neither_kind_of_accessory(self):
        with pytest.raises(ExportError, match="not something this can export"):
            build({"type": CUSTOM_ACCESSORY_TYPE})
