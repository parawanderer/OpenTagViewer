"""
What the app presents itself as to Apple, and — the harder half — when it does not.

This becomes a row in the user's Apple account device list, next to a *Remove from Account*
button and the words "If you do not recognise this device". Rule 11.

**The rule that shapes all of it: a new identity is for a new session only.** Apple binds a
session to the identity that established it, so re-identifying an existing one costs that user
a sign-in and leaves a second entry they never asked for. Someone already signed in keeps what
they have, unrecognisable name and all, because the alternative is worse for them.

Tested rather than trusted because every failure here is silent and remote: nothing throws, the
login succeeds or does not, and the evidence is in somebody else's Apple account.
"""

from __future__ import annotations

import json

import identity
import main
from findmy.reports import RemoteAnisetteProvider
from findmy.reports.anisette import CLIENT_IDENTITY, CLIENT_SERIAL, DeviceIdentity

# What Java sends for each of its two profiles. Copies, and only usable as copies: nothing here
# can reach the Java enum, so these say "given this shape, do that". That the shapes are the
# ones Java actually produces is asserted on device, by IdentityBridgeTest.
LEGACY_MAC = {
    "model": "MacBookPro13,2",
    "os_name": "macOS",
    "os_version": "13.1",
    "os_build": "22C65",
    "cfnetwork": "1404.0.5",
    "darwin": "22.2.0",
}

IPHONE = {
    "model": "iPhone15,2",
    "os_name": "iPhone OS",
    "os_version": "17.4",
    "os_build": "21E219",
    "cfnetwork": "1494.0.7",
    "darwin": "23.4.0",
}


class Bridge:
    """A local-Anisette bridge that works."""

    def __init__(self, profile=None):
        self._profile = IPHONE if profile is None else profile

    def ensureReady(self):
        return True

    def describe(self):
        return "a fake"

    def otp(self):
        return "otp"

    def machine(self):
        return "machine"

    def hardwareProfileJson(self):
        return json.dumps(self._profile)


class Unavailable(Bridge):
    """Local Anisette failed - but it still knows which machine this install is."""

    def ensureReady(self):
        return False

    def unavailableReason(self):
        return "no libraries"


class TestTheIdentityANewSessionPresents:
    def test_the_serial_is_the_apps_own_and_not_the_librarys(self):
        assert identity.APP_SERIAL == "0PENTAGVIEWR"
        assert identity.APP_SERIAL != CLIENT_SERIAL

    def test_the_serial_is_the_shape_apple_accepts(self):
        assert len(identity.APP_SERIAL) == 12
        assert identity.APP_SERIAL.isalnum()
        assert identity.APP_SERIAL.upper() == identity.APP_SERIAL

    def test_the_serial_is_not_the_exporters(self):
        # Two installs, two entries, each removable without breaking the other.
        assert identity.APP_SERIAL != "0PENTAGXPORT"
        assert identity.APP_SERIAL[:5] == "0PENT"

    def test_the_machine_is_whichever_one_java_says_this_install_is(self):
        """
        The whole reason `DeviceIdentity` was asked for upstream, and the reason this is not a
        constant any more.

        Java sends the same six values in the client info it provisions ADI under. Which six
        depends on the install — one that predates the choice keeps the Mac, a fresh one is an
        iPhone — so a copy on this side would be right for one of them and wrong for the other.
        """
        assert identity.hardwareProfile(Bridge(LEGACY_MAC)) == DeviceIdentity(**LEGACY_MAC)
        assert identity.hardwareProfile(Bridge(IPHONE)) == DeviceIdentity(**IPHONE)

    def test_neither_profile_is_the_librarys(self):
        assert DeviceIdentity(**LEGACY_MAC) != CLIENT_IDENTITY
        assert DeviceIdentity(**IPHONE) != CLIENT_IDENTITY

    def test_a_new_login_uses_it_on_both_transports(self):
        """
        Local and remote must present the same machine.

        The fallback from local Anisette to a server is automatic, so if the two disagreed a
        user would silently become a second device without doing anything to cause it. Note
        `Unavailable` still answers the profile question: that is the point of it not going
        through `ensureReady`.
        """
        for bridge in (Bridge(IPHONE), Unavailable(IPHONE)):
            provider = main._anisetteProvider(
                "https://example.invalid",
                bridge,
                **identity.identityForNewSession(bridge),
            )

            assert provider.serial == identity.APP_SERIAL
            assert provider.identity == DeviceIdentity(**IPHONE)

    def test_a_legacy_install_signing_in_again_presents_the_mac_it_provisioned_with(self):
        """
        The case a constant could not have got right.

        Somebody who installed before profiles existed, signs out, and signs back in. Their ADI
        is provisioned as a MacBookPro13,2 and is not re-provisioned, so the login has to claim
        the same thing — even though every fresh install alongside them now claims an iPhone.
        """
        bridge = Bridge(LEGACY_MAC)

        kwargs = identity.identityForNewSession(bridge)

        assert kwargs["identity"] == DeviceIdentity(**LEGACY_MAC)
        assert kwargs["identity"].platform == "<MacBookPro13,2> <macOS;13.1;22C65>"

    def test_the_serial_is_the_apps_even_for_a_legacy_install(self):
        """
        The serial is a label and the machine is not, so they are not gated together.

        A new sign-in gets a new device-list entry whatever happens, so there is nothing to
        preserve by withholding a recognisable name from it.
        """
        assert identity.identityForNewSession(Bridge(LEGACY_MAC))["serial"] == identity.APP_SERIAL


class TestWhenJavaCannotBeAsked:
    """
    Every one of these ends in a login that works and a device-list entry that is wrong.

    That is the deliberate trade: a mismatched identity is a bad row in a list, and refusing to
    sign in is an app that does not work. All of them print, because none of them should happen.
    """

    def test_no_bridge_at_all_imposes_nothing(self):
        assert identity.hardwareProfile(None) is None
        assert identity.identityForNewSession(None) == {"serial": identity.APP_SERIAL}

    def test_a_bridge_that_throws_does_not_take_the_login_with_it(self):
        class Broken(Bridge):
            def hardwareProfileJson(self):
                raise RuntimeError("no such method")

        assert identity.hardwareProfile(Broken()) is None

    def test_something_that_is_not_json_is_not_guessed_at(self):
        class Garbage(Bridge):
            def hardwareProfileJson(self):
                return "not json"

        assert identity.hardwareProfile(Garbage()) is None

    def test_a_renamed_field_is_refused_rather_than_half_filled(self):
        """
        The failure this check exists for, and it is genuinely silent without it.

        `DeviceIdentity.from_json` fills a missing key from FindMy.py's own identity, so a
        rename on either side would produce a MacBookPro13,2 on macOS 13.1 with FindMy's
        CFNetwork — a release that does not exist, sent to Apple as though it did.
        """
        renamed = dict(IPHONE)
        renamed["osName"] = renamed.pop("os_name")

        assert DeviceIdentity.from_json(renamed).os_name == CLIENT_IDENTITY.os_name, (
            "from_json stopped back-filling; this test no longer describes the risk"
        )
        assert identity.hardwareProfile(Bridge(renamed)) is None

    def test_an_extra_field_is_tolerated(self):
        # Java adding a field this version has no use for must not stop it signing in.
        assert identity.hardwareProfile(Bridge({**IPHONE, "chip": "A16"})) == DeviceIdentity(
            **IPHONE
        )


class TestARestoredSessionKeepsWhatItHad:
    """
    The half that protects people who are already signed in.

    An account established before any of this was bound to FindMy.py's defaults. Handing it the
    app's identity now would present Apple with a different machine on an existing session.
    """

    def test_a_provider_with_no_identity_asked_for_gets_the_librarys(self):
        # Which is what an account file written before this change restores to, and what the
        # library promises never to move.
        provider = main._anisetteProvider("https://example.invalid")

        assert provider.serial == CLIENT_SERIAL
        assert provider.identity == CLIENT_IDENTITY

    def test_the_local_provider_defaults_to_no_opinion(self):
        """
        `LocalAnisetteProvider` must not bake the app's identity into itself.

        It did, briefly, and that was the bug: it is constructed on the *restore* path too, so
        an upgrading user's next request would have gone out under a new serial.
        """
        provider = main.LocalAnisetteProvider(Bridge(), "https://example.invalid")

        assert provider.serial == CLIENT_SERIAL
        assert provider.identity == CLIENT_IDENTITY

    def test_a_restored_identity_is_carried_across_unchanged(self):
        established = DeviceIdentity(
            model="MacBookPro18,3", os_name="Mac OS X", os_version="13.4.1",
            os_build="22F8", cfnetwork="1408.0.4", darwin="22.5.0",
        )
        previous = RemoteAnisetteProvider(
            "https://example.invalid", serial="0FINDMYPY001", identity=established,
        )

        carried = identity.identityForRestore(previous)
        replacement = main.LocalAnisetteProvider(Bridge(), "https://example.invalid", **carried)

        assert replacement.serial == "0FINDMYPY001"
        assert replacement.identity == established

    def test_a_restored_new_style_account_keeps_the_apps_identity_too(self):
        """Someone who signed in *after* this shipped must not revert on the next restore."""
        established = DeviceIdentity(**IPHONE)
        previous = RemoteAnisetteProvider(
            "https://example.invalid",
            serial=identity.APP_SERIAL,
            identity=established,
        )

        carried = identity.identityForRestore(previous)
        replacement = main.LocalAnisetteProvider(Bridge(), "https://example.invalid", **carried)

        assert replacement.serial == identity.APP_SERIAL
        assert replacement.identity == established

    def test_a_restore_never_asks_java_what_this_install_is(self):
        """
        The bridge is not consulted on the restore path, and must not be.

        An install can be `LEGACY_MAC` while the session stored on it was established under
        FindMy.py's defaults — the profile describes the *install*, the stored identity
        describes the *session*, and on a restore only the session's answer is right.
        """
        class Loud(Bridge):
            def hardwareProfileJson(self):
                raise AssertionError("a restore asked Java which machine this is")

        established = DeviceIdentity(**LEGACY_MAC)
        previous = RemoteAnisetteProvider(
            "https://example.invalid", serial=CLIENT_SERIAL, identity=established,
        )

        carried = identity.identityForRestore(previous)
        replacement = main.LocalAnisetteProvider(Loud(), "https://example.invalid", **carried)

        assert replacement.serial == CLIENT_SERIAL
        assert replacement.identity == established

    def test_reading_an_identity_off_something_that_has_none_asks_for_nothing(self):
        # A library rename must degrade to "keep the default", not to a crash on every restore.
        assert identity.identityForRestore(object()) == {}


class TestTheLibraryDefaultIsStillWhereWeLeftIt:
    """
    Existing sessions depend on FindMy.py's default never moving, and the library says so.

    If it ever does, every account that never asked for an identity silently becomes a
    different machine — the exact harm this design avoids, arriving from underneath.
    """

    def test_the_default_serial_has_not_moved(self):
        assert CLIENT_SERIAL == "0FINDMYPY001"

    def test_the_default_identity_has_not_moved(self):
        assert CLIENT_IDENTITY.model == "MacBookPro18,3"
        assert CLIENT_IDENTITY.os_name == "Mac OS X"
        assert CLIENT_IDENTITY.os_version == "13.4.1"
        # Deliberately a character short of a real macOS build (13.4.1 is 22F82). Kept wrong
        # because every existing session is bound to it; correcting it would cost a re-login.
        assert CLIENT_IDENTITY.os_build == "22F8"
