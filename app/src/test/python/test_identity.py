"""
What the app presents itself as to Apple.

This is not a cosmetic string. It becomes a row in the user's Apple account device list, next to
a *Remove from Account* button and the words "If you do not recognise this device" - so a wrong
or inconsistent value gets the user to remove the thing that was working. Rule 11.

Tested here rather than trusted because the failure is silent and remote: nothing throws, the
login succeeds, and the only symptom is an entry the user cannot identify.
"""

from __future__ import annotations

import identity
import main
from findmy.reports import RemoteAnisetteProvider
from findmy.reports.anisette import CLIENT_SERIAL


class TestTheSerialTheAppPresents:
    def test_it_is_the_apps_own_and_not_the_librarys(self):
        # 0FINDMYPY001 names FindMy.py. A user reading their device list should see this app.
        assert identity.APP_SERIAL == "0PENTAGVIEWR"
        assert identity.APP_SERIAL != CLIENT_SERIAL

    def test_it_is_the_shape_apple_accepts(self):
        assert len(identity.APP_SERIAL) == 12
        assert identity.APP_SERIAL.isalnum()
        assert identity.APP_SERIAL.upper() == identity.APP_SERIAL

    def test_it_is_not_the_exporters(self):
        # Two installs, two entries, each removable without breaking the other. Sharing a prefix
        # is deliberate; sharing the whole serial would make them one device.
        assert identity.APP_SERIAL != "0PENTAGXPORT"
        assert identity.APP_SERIAL[:5] == "0PENT"


class TestEveryProviderPresentsIt:
    """
    Both providers, because which one produced a session is a transport detail.

    A user whose local Anisette fell back to a server must not thereby acquire a second entry in
    their device list - and that fallback is automatic, so it would happen without them doing
    anything to cause it.
    """

    def test_the_remote_provider_presents_it(self):
        provider = main._anisetteProvider("https://example.invalid")

        assert isinstance(provider, RemoteAnisetteProvider)
        assert provider.serial == identity.APP_SERIAL

    def test_the_local_provider_presents_it(self):
        class Bridge:
            def ensureReady(self):
                return True

            def describe(self):
                return "a fake"

            def otp(self):
                return "otp"

            def machine(self):
                return "machine"

        provider = main._anisetteProvider("https://example.invalid", Bridge())

        assert isinstance(provider, main.LocalAnisetteProvider)
        assert provider.serial == identity.APP_SERIAL

    def test_a_local_provider_that_falls_back_still_presents_it(self):
        class Unavailable:
            def ensureReady(self):
                return False

            def unavailableReason(self):
                return "no libraries"

        provider = main._anisetteProvider("https://example.invalid", Unavailable())

        assert isinstance(provider, RemoteAnisetteProvider)
        assert provider.serial == identity.APP_SERIAL


class TestTheKnownMismatch:
    """
    The part of the identity that does not agree with itself yet.

    Recorded rather than fixed: the second string is hardcoded inside FindMy.py and there is
    nowhere to pass one in, so a request to expose it has gone upstream. These tests exist so
    that the day the library grows the knob - or moves the value - something fails and says so,
    rather than the discrepancy quietly outliving the reason for it.
    """

    def test_the_two_client_info_strings_still_disagree(self):
        ours, theirs = identity.KNOWN_IDENTITY_MISMATCH

        assert ours != theirs, (
            "If these now agree, the mismatch is fixed - delete KNOWN_IDENTITY_MISMATCH, this"
            " test, and docs/findmy-py-client-info-request.md."
        )

    def test_it_records_what_the_library_actually_sends(self):
        """
        Composed from the library's own constants, not scraped out of its source.

        The first version of this searched `anisette.py` for the finished string, and broke
        the moment the fork split the identity into parts - which it had already done at the
        commit the app pins, so the test failed on a library that had not changed its identity
        at all. Reading the values means a refactor is free and a *changed identity* is not.
        """
        from findmy.reports import anisette

        parts = ("CLIENT_MODEL", "CLIENT_OS", "CLIENT_OS_VERSION", "CLIENT_OS_BUILD")
        missing = [name for name in parts if not hasattr(anisette, name)]
        assert not missing, (
            f"findmy.reports.anisette has no {', '.join(missing)}. The installed FindMy is not"
            " the commit app/build.gradle.kts pins - check requirements.txt and your venv."
        )

        theirs_now = (
            f"<{anisette.CLIENT_MODEL}> "
            f"<{anisette.CLIENT_OS};{anisette.CLIENT_OS_VERSION};{anisette.CLIENT_OS_BUILD}>"
        )

        _, theirs_recorded = identity.KNOWN_IDENTITY_MISMATCH

        assert theirs_recorded == theirs_now, (
            f"FindMy.py now presents {theirs_now}, not {theirs_recorded}. Update"
            " KNOWN_IDENTITY_MISMATCH - and check whether it now agrees with"
            " AdiDeviceIdentity.CLIENT_INFO, in which case the mismatch is over."
        )
