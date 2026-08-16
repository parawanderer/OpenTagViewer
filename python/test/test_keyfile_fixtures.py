"""
The sample key files in `test/resources/keyfiles`, read the way the exporter reads them.

They exist to be dropped on the wizard's **+ Add from key file…** button by hand, and are tested
here so they stay valid: a sample file that stopped parsing would be found by whoever was trying
the feature out, which is the worst possible time.

Nothing here is a real tag. The keys are fixed byte patterns and nothing has ever advertised them.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from exporter.custom_tags import CustomTagError, check_advertisement_key, suggested_name
from opentagviewer_export import parse_key_file
from opentagviewer_export.keyfiles import PRIVATE_KEY_LENGTH

KEYFILES = Path(__file__).parent / "resources" / "keyfiles"


def read(name: str):
    path = KEYFILES / name
    return parse_key_file(path.read_bytes(), filename=path.name)


class TestEverySampleParses:
    @pytest.mark.parametrize("name", [
        "macless-haystack-devices.json",
        "findmy-custom-accessory.json",
        "Wallet.keys",
        "bare-keys.txt",
    ])
    def test_reads_as_at_least_one_tag(self, name):
        tags = read(name)

        assert tags
        assert all(tag.private_keys for tag in tags)

    @pytest.mark.parametrize("name", [
        "macless-haystack-devices.json",
        "findmy-custom-accessory.json",
        "Wallet.keys",
        "bare-keys.txt",
    ])
    def test_every_key_is_the_right_length(self, name):
        for tag in read(name):
            assert all(len(key) == PRIVATE_KEY_LENGTH for key in tag.private_keys)

    @pytest.mark.parametrize("name", [
        "macless-haystack-devices.json",
        "findmy-custom-accessory.json",
        "Wallet.keys",
        "bare-keys.txt",
    ])
    def test_every_sample_can_be_named(self, name):
        # Either the file said, or one is derived from the public key. A tag with no name at all
        # cannot be exported, so a sample that produced one would be a broken sample.
        for tag in read(name):
            assert suggested_name(tag).strip()


class TestWhatEachFormatCarries:
    def test_macless_haystack_keeps_the_earlier_keys_and_the_current_one(self):
        tag, = read("macless-haystack-devices.json")

        assert len(tag.private_keys) == 3
        assert tag.name == "Bike (Macless Haystack)"
        assert tag.identifier == "1"

    def test_the_findmy_accessory_carries_its_own_name_and_identifier(self):
        # Both survive the parse. Only the name survives the export - see
        # `custom_tags.suggested_identifier` for why an identifier does not.
        tag, = read("findmy-custom-accessory.json")

        assert tag.name == "Keys (FindMy.py)"
        assert tag.identifier == "keys-findmy"

    def test_the_openhaystack_file_is_named_after_its_file(self):
        tag, = read("Wallet.keys")

        assert tag.name == "Wallet"
        assert tag.advertisement_key is not None

    def test_a_bare_list_is_one_tag_with_every_key_in_it(self):
        tag, = read("bare-keys.txt")

        assert tag.private_keys
        assert tag.name == "bare-keys"


class TestTheyAreFourDifferentTags:
    def test_all_four_can_go_in_one_bundle(self):
        """
        They used to be the same tag written four ways, and the exporter refused the duplicate.

        That refusal was correct - identifiers come from the tag's own public key, so the same
        keys are the same tag however the file spelled them. But a sample set nobody can import
        together is a poor sample set, so these are four distinct tags now.
        """
        from exporter.custom_tags import PreparedTag, suggested_identifier, suggested_name
        from opentagviewer_export import build_export

        prepared = [
            PreparedTag(tag=tag, name=suggested_name(tag), identifier=suggested_identifier(tag))
            for name in (
                "macless-haystack-devices.json", "findmy-custom-accessory.json",
                "Wallet.keys", "bare-keys.txt",
            )
            for tag in read(name)
        ]

        bundle = build_export(
            [tag.to_export() for tag in prepared],
            via="OpenTagViewer.cli:1.1.0", source_user="user", exported_at_ms=1786282770586,
        )

        written = [path for path in bundle.entries if path.startswith("CustomAccessories/")]
        assert len(written) == len(prepared) == 4


class TestTheCrossCheck:
    def test_a_real_openhaystack_file_passes(self):
        # Its advertisement key is derived from its private key rather than invented, so this
        # actually proves something.
        tag, = read("Wallet.keys")

        check_advertisement_key(tag)

    def test_the_swapped_sample_is_caught(self):
        # `Swapped.keys` has the public key in the private field - the likeliest real mistake, and
        # one nothing can see by looking, since both are 28 bytes. Without this check it exports
        # cleanly and produces a tag that never appears on the map.
        tag, = read("Swapped.keys")

        with pytest.raises(CustomTagError, match="does not produce the advertisement key"):
            check_advertisement_key(tag)
