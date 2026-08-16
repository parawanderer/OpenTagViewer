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

import identity
import main
from findmy.reports import RemoteAnisetteProvider
from findmy.reports.anisette import CLIENT_IDENTITY, CLIENT_SERIAL, DeviceIdentity


class Bridge:
    """A local-Anisette bridge that works."""

    def ensureReady(self):
        return True

    def describe(self):
        return "a fake"

    def otp(self):
        return "otp"

    def machine(self):
        return "machine"


class Unavailable:
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

    def test_the_identity_matches_the_one_adi_is_initialised_with(self):
        """
        The whole reason `DeviceIdentity` was asked for upstream.

        `AdiDeviceIdentity.CLIENT_INFO` on the Java side sends this exact string when it
        provisions ADI. FindMy.py used to send a MacBook Pro 18,3 on macOS 13.4.1 from its own
        defaults — one app, one session, two different Macs, differing even in the OS *name*.
        If this assertion ever fails, the two halves have drifted apart again.
        """
        composed = (
            f"<{identity.APP_IDENTITY.model}> "
            f"<{identity.APP_IDENTITY.os_name};{identity.APP_IDENTITY.os_version};"
            f"{identity.APP_IDENTITY.os_build}>"
        )

        assert composed == "<MacBookPro13,2> <macOS;13.1;22C65>", (
            "This must stay identical to AdiDeviceIdentity.CLIENT_INFO in the Java sources."
        )

    def test_the_identity_is_not_the_librarys(self):
        assert identity.APP_IDENTITY != CLIENT_IDENTITY

    def test_a_new_login_uses_it_on_both_transports(self):
        """
        Local and remote must present the same machine.

        The fallback from local Anisette to a server is automatic, so if the two disagreed a
        user would silently become a second device without doing anything to cause it.
        """
        for bridge in (Bridge(), Unavailable(), None):
            provider = main._anisetteProvider(
                "https://example.invalid",
                bridge,
                serial=identity.APP_SERIAL,
                identity=identity.APP_IDENTITY,
            )

            assert provider.serial == identity.APP_SERIAL
            assert provider.identity == identity.APP_IDENTITY


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
        previous = RemoteAnisetteProvider(
            "https://example.invalid",
            serial=identity.APP_SERIAL,
            identity=identity.APP_IDENTITY,
        )

        carried = identity.identityForRestore(previous)
        replacement = main.LocalAnisetteProvider(Bridge(), "https://example.invalid", **carried)

        assert replacement.serial == identity.APP_SERIAL
        assert replacement.identity == identity.APP_IDENTITY

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
