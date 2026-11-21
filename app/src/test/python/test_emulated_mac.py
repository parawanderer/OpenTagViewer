"""
The two Python halves of signing in as the emulated Mac.

Grand Slam's `hsc` 409 with no `au` is read as a second factor by the pinned FindMy.py itself
(`_normalize_2fa_status`, parawanderer/FindMy.py#9), which tests it through a full offline SRP
exchange. The app's runtime wrapper for it is gone; `TestHsc409` only pins that the library still
has it.
"""

from __future__ import annotations

import uuid
from types import SimpleNamespace

from findmy.reports import RemoteAnisetteProvider

import icloud_bridge
import main


class Bridge:
    """What Java hands Python as `localAnisette`, as far as these tests need."""

    def __init__(self, router: str | None = None) -> None:
        if router is not None:
            self.router = lambda: router

    def otp(self) -> str:
        return "b3Rw"

    def machine(self) -> str:
        return "bWFjaGluZQ=="


class TestTheRoutingNumber:
    def test_it_is_the_one_java_reports(self) -> None:
        provider = main.LocalAnisetteProvider(Bridge(router="12345678"), "https://example.invalid")
        assert provider.router == "12345678"

    def test_a_bridge_without_one_keeps_the_librarys(self) -> None:
        provider = main.LocalAnisetteProvider(Bridge(), "https://example.invalid")
        assert provider.router == RemoteAnisetteProvider("https://example.invalid").router


class TestHsc409:
    def test_the_pinned_findmy_reads_it_as_a_second_factor(self) -> None:
        """
        Since 2026-10-12 Apple asks for a code with `hsc` 409 and no `au` (#248). The app used to
        patch that in at runtime; the pin now carries it. If this fails, the pin moved to a
        FindMy.py without it, and every sign-in that needs a code skips the code and fails.
        """
        from findmy.reports.account import _normalize_2fa_status

        status = {"ec": 0, "hsc": 409}
        _normalize_2fa_status(status)
        assert status["au"] == "trustedDeviceSecondaryAuth"


MAC_DEVICE_ID = "00006000-0123456789ABCDEF"


class TestWhatCloudKitIsToldTheDeviceIs:
    def test_a_device_id_that_is_already_a_uuid_is_passed_through_untouched(self) -> None:
        account = SimpleNamespace(device_uuid="9F1C2D3E-4B5A-4C6D-8E7F-001122334455")
        assert icloud_bridge._forCloudKit(account) is account

    def test_a_mac_device_id_gets_a_uuid_and_everything_else_is_the_accounts(self) -> None:
        account = SimpleNamespace(device_uuid=MAC_DEVICE_ID, serial="0PENTAGVTEST")

        view = icloud_bridge._forCloudKit(account)

        # What the CloudKit client does with it, and what failed before.
        uuid.UUID(view.device_uuid)
        assert view.device_uuid == view.device_uuid.upper()
        assert view.serial == "0PENTAGVTEST"

    def test_it_is_the_same_whichever_anisette_the_session_restored_onto(self) -> None:
        """A Mac session on its own Mac, and the same session fallen back to a remote server."""
        onTheMac = SimpleNamespace(device_uuid=MAC_DEVICE_ID, _anisette=SimpleNamespace(
            _bridge=SimpleNamespace(cloudKitDeviceUuid=lambda: "00112233-4455-6677-8899-AABBCCDDEEFF")))
        onAServer = SimpleNamespace(device_uuid=MAC_DEVICE_ID, _anisette=SimpleNamespace())

        assert icloud_bridge._forCloudKit(onTheMac).device_uuid \
            == icloud_bridge._forCloudKit(onAServer).device_uuid

    def test_it_is_stable(self) -> None:
        first = icloud_bridge._forCloudKit(SimpleNamespace(device_uuid=MAC_DEVICE_ID))
        again = icloud_bridge._forCloudKit(SimpleNamespace(device_uuid=MAC_DEVICE_ID))

        uuid.UUID(first.device_uuid)
        assert first.device_uuid == again.device_uuid

    def test_a_method_reached_through_the_view_acts_on_the_real_account(self) -> None:
        """request_pet swaps login state on `self` - it must be the account's self, not the view's."""
        class Account:
            device_uuid = MAC_DEVICE_ID
            state = "before"

            def change(self) -> None:
                self.state = "after"

        real = Account()
        icloud_bridge._forCloudKit(real).change()
        assert real.state == "after"
