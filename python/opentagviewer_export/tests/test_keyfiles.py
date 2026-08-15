"""
Reading self-generated tags out of whatever file the user has.

Two things are being tested. That each format is read correctly - and that a file none of them
fits produces a message naming what *would* work, because the person holding an unreadable file
cannot see this code and has no other way to find out.
"""

from __future__ import annotations

import base64
import json

import pytest

from opentagviewer_export import KeyFileError, parse_key_file
from opentagviewer_export.keyfiles import PRIVATE_KEY_LENGTH

KEY_A = bytes(range(PRIVATE_KEY_LENGTH))
KEY_B = bytes(range(100, 100 + PRIVATE_KEY_LENGTH))


def b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


class TestMaclessHaystack:
    def devices(self, **overrides) -> str:
        device = {
            "id": 1234,
            "name": "bike",
            "privateKey": b64(KEY_B),
            "additionalKeys": [b64(KEY_A)],
        }
        device.update(overrides)
        return json.dumps([device])

    def test_reads_a_device(self):
        tag, = parse_key_file(self.devices())

        assert tag.name == "bike"
        assert tag.identifier == "1234"
        assert "Macless Haystack" in tag.source_format

    def test_keeps_the_earlier_keys_and_the_current_one(self):
        # `additionalKeys` are the earlier keys and `privateKey` the current. All of them are
        # searched when the tag is located, so dropping either half loses sightings.
        tag, = parse_key_file(self.devices())

        assert tag.private_keys == [KEY_A, KEY_B]

    def test_reads_a_device_with_no_earlier_keys(self):
        tag, = parse_key_file(self.devices(additionalKeys=[]))

        assert tag.private_keys == [KEY_B]

    def test_reads_several_devices(self):
        two = json.dumps([
            {"id": 1, "name": "one", "privateKey": b64(KEY_A)},
            {"id": 2, "name": "two", "privateKey": b64(KEY_B)},
        ])

        assert [tag.name for tag in parse_key_file(two)] == ["one", "two"]

    def test_reads_a_single_device_not_in_a_list(self):
        tag, = parse_key_file(json.dumps({"id": 9, "name": "solo", "privateKey": b64(KEY_A)}))

        assert tag.private_keys == [KEY_A]

    def test_falls_back_to_the_filename_when_a_device_is_unnamed(self):
        tag, = parse_key_file(self.devices(name=""), filename="/tmp/wallet.json")

        assert tag.name == "wallet"


class TestFindMyPyOwnFormat:
    def accessory(self, **overrides) -> str:
        document = {
            "type": "custom_rolling_key_accessory",
            "private_keys": [KEY_A.hex(), KEY_B.hex()],
            "name": "keys",
            "identifier": "abc",
        }
        document.update(overrides)
        return json.dumps(document)

    def test_reads_hex_keys(self):
        tag, = parse_key_file(self.accessory())

        assert tag.private_keys == [KEY_A, KEY_B]
        assert tag.name == "keys"
        assert tag.identifier == "abc"

    def test_refuses_one_with_no_keys(self):
        with pytest.raises(KeyFileError, match="no `private_keys`"):
            parse_key_file(self.accessory(private_keys=[]))

    def test_points_an_apple_paired_accessory_somewhere_useful(self):
        # Same file extension, same library, completely different thing - and the difference is
        # not obvious from outside, so the message says where those actually come from.
        exported = json.dumps({"type": "accessory", "master_key": "00", "skn": "00", "sks": "00"})

        with pytest.raises(KeyFileError, match="sign in and pick"):
            parse_key_file(exported)


class TestOpenHaystackKeyFiles:
    KEYS_FILE = (
        "Private key: {private}\n"
        "Advertisement key: {advertisement}\n"
        "Hashed adv key: {hashed}\n"
    )

    def keys_file(self, **overrides) -> str:
        values = {"private": b64(KEY_A), "advertisement": b64(KEY_B), "hashed": b64(KEY_B)}
        values.update(overrides)
        return self.KEYS_FILE.format(**values)

    def test_reads_the_private_key(self):
        tag, = parse_key_file(self.keys_file(), filename="Backpack.keys")

        assert tag.private_keys == [KEY_A]
        assert tag.name == "Backpack"

    def test_carries_the_stated_advertisement_key_for_a_caller_to_check(self):
        # Nothing here can tell a private key from a public one: both are 28 bytes. A caller with
        # the maths can derive one from the other and catch a swap, which is why this is kept.
        tag, = parse_key_file(self.keys_file())

        assert tag.advertisement_key == KEY_B

    def test_tolerates_the_labels_being_written_differently(self):
        written_differently = "PRIVATE KEY:{private}\n".format(private=b64(KEY_A))

        tag, = parse_key_file(written_differently)

        assert tag.private_keys == [KEY_A]

    def test_a_file_with_only_a_public_key_is_not_read_as_a_tag(self):
        with pytest.raises(KeyFileError, match="Nothing in that file"):
            parse_key_file("Advertisement key: {key}\n".format(key=b64(KEY_B)))


class TestBareKeys:
    def test_reads_one_key_per_line(self):
        tag, = parse_key_file(f"{b64(KEY_A)}\n{b64(KEY_B)}\n")

        assert tag.private_keys == [KEY_A, KEY_B]

    def test_reads_hex_as_well_as_base64(self):
        tag, = parse_key_file(KEY_A.hex())

        assert tag.private_keys == [KEY_A]

    def test_skips_blank_lines_and_comments(self):
        tag, = parse_key_file(f"# my tag\n\n{b64(KEY_A)}\n\n")

        assert tag.private_keys == [KEY_A]

    def test_a_file_where_only_some_lines_are_keys_is_not_read_at_all(self):
        # More likely a format this does not know than a list with a typo. Reading the half that
        # parses produces a tag that locates sometimes, which is worse than not reading it.
        with pytest.raises(KeyFileError, match="Nothing in that file"):
            parse_key_file(f"{b64(KEY_A)}\nsome heading\n{b64(KEY_B)}\n")


class TestWhatItSaysWhenItCannot:
    def test_names_every_format_it_understands(self):
        with pytest.raises(KeyFileError) as raised:
            parse_key_file("nothing like a key at all\n")

        message = str(raised.value)
        assert "Macless Haystack" in message
        assert "OpenHaystack" in message
        assert "FindMy.py" in message
        assert "28 bytes" in message

    def test_says_so_plainly_when_the_file_is_empty(self):
        with pytest.raises(KeyFileError, match="empty"):
            parse_key_file("   \n")

    def test_broken_json_is_reported_as_broken_json(self):
        # Rather than falling through to "not a key file", which would send someone looking in the
        # wrong place entirely.
        with pytest.raises(KeyFileError, match="does not parse"):
            parse_key_file('[{"privateKey": "abc",}]')

    def test_a_key_of_the_wrong_length_says_what_the_right_one_is(self):
        with pytest.raises(KeyFileError, match="28"):
            parse_key_file(json.dumps([{"id": 1, "privateKey": b64(b"\x00" * 16)}]))

    def test_a_32_byte_key_is_recognised_as_the_wrong_kind_of_secret(self):
        with pytest.raises(KeyFileError, match="shared secret"):
            parse_key_file(json.dumps([{"id": 1, "privateKey": b64(b"\x00" * 32)}]))

    def test_json_that_is_neither_format_lists_what_it_did_contain(self):
        with pytest.raises(KeyFileError, match="neither a Macless Haystack"):
            parse_key_file(json.dumps({"tag": "something", "keys": []}))


class TestEncodings:
    def test_accepts_bytes_as_well_as_text(self):
        tag, = parse_key_file(f"{b64(KEY_A)}\n".encode())

        assert tag.private_keys == [KEY_A]

    def test_a_hex_key_is_not_mistaken_for_base64(self):
        # A hex string is also valid base64 - it decodes to nonsense of a different length rather
        # than failing - so the hex reading is tried first when the text is entirely hex.
        tag, = parse_key_file(KEY_A.hex())

        assert tag.private_keys == [KEY_A]
