"""
Which route an export takes, and why.

The decision matters more than it looks: one route asks for an Apple ID password and registers a
device on the account, and the other asks for neither. Getting it wrong in the quiet direction -
signing somebody in who did not need to - is the failure worth testing for.
"""

from __future__ import annotations

import pytest

from exporter import source


@pytest.fixture
def mac(monkeypatch):
    """Pretend to be a Mac of a given version, with or without the Find My cache."""
    def _mac(version: tuple[int, int] | None, *, has_cache: bool = True) -> None:
        monkeypatch.setattr(source, "MACOS_VER", version)
        monkeypatch.setattr(source.os.path, "isdir", lambda _path: has_cache)
    return _mac


class TestDetecting:
    def test_an_old_enough_mac_reads_its_own_files(self, mac):
        # No Apple ID password, no device on the account, no network. Signing in is the bigger ask
        # of the two and should not be made of somebody who does not need to make it.
        mac((14, 7))

        assert source.detect().kind == source.LOCAL

    def test_a_newer_mac_goes_to_icloud(self, mac):
        # macOS 15 stopped letting anything read the BeaconStore key out of the keychain, so the
        # local route cannot decrypt anything at all - it is not merely untested there.
        mac((15, 0))

        route = source.detect()

        assert route.kind == source.ICLOUD
        assert "keychain" in route.reason

    def test_anything_that_is_not_a_mac_goes_to_icloud(self, mac):
        mac(None)

        route = source.detect()

        assert route.kind == source.ICLOUD
        assert "not a Mac" in route.reason

    def test_an_old_mac_with_no_find_my_cache_goes_to_icloud(self, mac):
        # A Mac that was never signed into iCloud with Find My on has nothing local to read.
        mac((14, 7), has_cache=False)

        route = source.detect()

        assert route.kind == source.ICLOUD
        assert "no Find My cache" in route.reason

    def test_every_route_explains_itself(self, mac):
        # "It asked me for my Apple ID password" and "it did not" is exactly the difference
        # somebody will want explained, so no branch may return a bare answer.
        for version in [(14, 7), (15, 0), None]:
            mac(version)
            assert source.detect().reason.strip()


class TestBeingAsked:
    def test_icloud_can_be_demanded_on_a_mac_that_could_read_locally(self, mac):
        mac((14, 7))

        assert source.resolve(source.ICLOUD).kind == source.ICLOUD

    def test_local_is_honoured_where_it_works(self, mac):
        mac((14, 7))

        route = source.resolve(source.LOCAL)

        assert route.is_local
        assert "you asked" in route.reason

    def test_asking_for_local_where_it_cannot_work_is_answered_not_obeyed(self, mac):
        # Obeying would fail deep inside the keychain with a message about a missing key, which
        # says nothing about the actual reason.
        mac((15, 0))

        route = source.resolve(source.LOCAL)

        assert route.kind == source.ICLOUD
        assert "asked for" in route.reason
        assert "keychain" in route.reason

    def test_auto_is_whatever_detection_said(self, mac):
        mac((14, 7))

        assert source.resolve(source.AUTO).kind == source.detect().kind


class TestReadingNoAccountAtAll:
    """
    `--source none` is for a bundle of self-generated tags, which are in no Apple account.

    They come off disk with `--add-keys`, and until this existed the CLI still signed in first to
    fetch a list nothing was going to be taken from - an Apple ID password, a device registered on
    the account, and a keychain unlock, to export tags that never touched any of it.
    """

    def test_it_reads_nothing_and_says_so(self, mac):
        mac((14, 7))  # a Mac that could have read locally: what was asked for wins

        route = source.resolve(source.NONE)

        assert route.reads_nothing
        assert not route.is_local
        assert "you asked" in route.reason

    def test_no_other_route_reads_nothing(self, mac):
        for version in [(14, 7), (15, 0), None]:
            mac(version)
            assert not source.detect().reads_nothing
            for requested in [source.AUTO, source.ICLOUD, source.LOCAL]:
                assert not source.resolve(requested).reads_nothing

    def test_it_has_to_be_asked_for_by_name(self, mac):
        # Never something detection can land on: a run that reads no account is a thing to choose,
        # not something to be given because a machine looked a certain way.
        for version in [(14, 7), (15, 0), None]:
            mac(version)
            assert source.detect().kind != source.NONE
