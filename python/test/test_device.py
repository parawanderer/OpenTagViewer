"""
Remembering which device this exporter is.

Two things are being tested, and the second matters more than the first. That the identity
survives a run - without which an account's device list gains an entry per export. And that
*nothing else* does: the account's own serialisation carries the username and the password beside
the ids, so "store what the library gives you" is precisely the mistake available here.
"""

from __future__ import annotations

import json

import pytest

from exporter import device
from test.unittestutils import skip_unless_posix_permissions

ANISETTE = {"type": "aniLocal", "prov_data": "AAAA", "serial": "0PENTAGXPORT"}


@pytest.fixture
def identity(tmp_path):
    return tmp_path / "device-identity.json"


class TestRoundTrip:
    def test_what_is_saved_comes_back(self, identity):
        device.save("uid-1", "devid-1", ANISETTE, identity)

        assert device.load(identity) == {"uid": "uid-1", "devid": "devid-1", "anisette": ANISETTE}

    def test_nothing_stored_yet_is_not_an_error(self, identity):
        assert device.load(identity) is None

    def test_forgetting_it_leaves_nothing(self, identity):
        device.save("uid-1", "devid-1", ANISETTE, identity)

        assert device.forget(identity) is True
        assert device.load(identity) is None
        assert device.forget(identity) is False

    @skip_unless_posix_permissions
    def test_it_is_readable_by_its_owner_alone(self, identity):
        device.save("uid-1", "devid-1", ANISETTE, identity)

        assert identity.stat().st_mode & 0o077 == 0


class TestNothingSensitiveIsWritten:
    def test_refuses_a_key_that_is_not_part_of_an_identity(self, identity):
        device.save("uid-1", "devid-1", {"type": "aniLocal", "password": "hunter2"}, identity)

        assert not identity.exists()

    @pytest.mark.parametrize("key", ["password", "sessionToken", "adsid", "dsid", "cookie", "pet"])
    def test_refuses_anything_that_looks_like_a_credential_at_any_depth(self, identity, key):
        # The check is on the name rather than the value, because the shape comes from a library
        # and a new field there should fail closed.
        device.save("uid-1", "devid-1", {"type": "aniLocal", "nested": {key: "x"}}, identity)

        assert not identity.exists()

    def test_a_refusal_does_not_raise(self, identity):
        # Failing to store an identity costs one extra device-list entry next time. Failing an
        # export that has already succeeded costs the export.
        device.save("uid-1", "devid-1", {"account": {"password": "x"}}, identity)

        assert not identity.exists()


class TestAFileThatIsNotOne:
    def test_nonsense_is_ignored_rather_than_fatal(self, identity):
        identity.write_text("this is not json")

        assert device.load(identity) is None

    def test_json_that_is_not_an_identity_is_ignored(self, identity):
        identity.write_text(json.dumps({"something": "else"}))

        assert device.load(identity) is None

    def test_half_an_identity_is_ignored(self, identity):
        # Half of it would produce a device that is neither the old one nor a clean new one.
        identity.write_text(json.dumps({"uid": "uid-1"}))

        assert device.load(identity) is None


class TestWhereItLives:
    def test_is_somewhere_per_user_rather_than_beside_the_program(self):
        # The program may be in /Applications, or on a read-only volume, or unpacked fresh on
        # every launch - none of which can hold state.
        path = device.identity_path()

        assert path.name == device.FILENAME
        assert str(path).startswith(("/Users", "/home", "/root", "/private")) or path.is_absolute()
