"""
Turning a key file into something exportable.

The checks here are the ones that need maths, and so cannot live in the pure parsing package: a
private key and a public one are both 28 bytes, and only deriving one from the other tells them
apart.
"""

from __future__ import annotations

import pytest
from findmy import KeyPair

from exporter.custom_tags import (
    CustomTagError,
    PreparedTag,
    check_advertisement_key,
    suggested_identifier,
    suggested_name,
)
from opentagviewer_export import CUSTOM_ACCESSORY_TYPE, ParsedTag

PRIVATE = bytes(range(28))
OTHER_PRIVATE = bytes(range(100, 128))

ADVERTISEMENT = KeyPair(private_key=PRIVATE).adv_key_bytes


class TestCheckingAStatedAdvertisementKey:
    def test_passes_when_the_keys_agree(self):
        check_advertisement_key(ParsedTag(private_keys=[PRIVATE], advertisement_key=ADVERTISEMENT))

    def test_passes_when_the_file_stated_none(self):
        # Most formats do not, and that is not a problem - only an unchecked case.
        check_advertisement_key(ParsedTag(private_keys=[PRIVATE]))

    def test_fails_when_they_disagree(self):
        # The likeliest real mistake: the fields swapped, or two tags' files mixed up. It would
        # otherwise export cleanly and produce a tag that never appears, with nothing to explain it.
        wrong = KeyPair(private_key=OTHER_PRIVATE).adv_key_bytes

        with pytest.raises(CustomTagError, match="does not produce the advertisement key"):
            check_advertisement_key(ParsedTag(private_keys=[PRIVATE], advertisement_key=wrong))

    def test_passes_when_any_of_the_tags_keys_derives_it(self):
        # A tag carries several keys and the file may state the advertisement key of any of them.
        tag = ParsedTag(private_keys=[OTHER_PRIVATE, PRIVATE], advertisement_key=ADVERTISEMENT)

        check_advertisement_key(tag)


class TestSuggestingAName:
    def test_keeps_the_name_the_file_carried(self):
        assert suggested_name(ParsedTag(private_keys=[PRIVATE], name="bike")) == "bike"

    def test_derives_one_from_the_public_key_when_the_file_had_none(self):
        # The advertisement key is broadcast by the tag continuously and is what the network is
        # queried by, so a name derived from it leaks nothing that standing near the tag would not.
        suggested = suggested_name(ParsedTag(private_keys=[PRIVATE]))

        assert suggested == f"Tag {ADVERTISEMENT[:3].hex().upper()}"

    def test_the_same_tag_suggests_the_same_name_twice(self):
        assert suggested_name(ParsedTag(private_keys=[PRIVATE])) == suggested_name(ParsedTag(private_keys=[PRIVATE]))

    def test_different_tags_suggest_different_names(self):
        one = suggested_name(ParsedTag(private_keys=[PRIVATE]))
        other = suggested_name(ParsedTag(private_keys=[OTHER_PRIVATE]))

        assert one != other

    def test_no_private_key_material_reaches_the_name(self):
        suggested = suggested_name(ParsedTag(private_keys=[PRIVATE]))

        assert PRIVATE.hex()[:6].upper() not in suggested


class TestSuggestingAnIdentifier:
    def test_keeps_the_one_the_file_carried(self):
        assert suggested_identifier(ParsedTag(private_keys=[PRIVATE], identifier="1234")) == "1234"

    def test_derives_one_from_the_public_key_rather_than_from_the_name(self):
        # It becomes the filename in the bundle, and a name is exactly what two tags collide on.
        assert suggested_identifier(ParsedTag(private_keys=[PRIVATE], name="bike")).startswith("tag-")


class TestRendering:
    def test_produces_the_mapping_findmy_reads_back(self):
        prepared = PreparedTag(tag=ParsedTag(private_keys=[PRIVATE]), name="bike", identifier="bike-1")

        mapping = prepared.to_export().mapping

        assert mapping["type"] == CUSTOM_ACCESSORY_TYPE
        assert mapping["private_keys"] == [PRIVATE.hex()]
        assert mapping["name"] == "bike"
        assert mapping["identifier"] == "bike-1"

    def test_keeps_every_key_and_their_order(self):
        # Each key is the one in use at a different moment, so a locate asks about all of them.
        prepared = PreparedTag(
            tag=ParsedTag(private_keys=[OTHER_PRIVATE, PRIVATE]),
            name="bike",
            identifier="bike-1",
        )

        assert prepared.to_export().mapping["private_keys"] == [OTHER_PRIVATE.hex(), PRIVATE.hex()]
