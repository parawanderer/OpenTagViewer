"""
The two Python halves of signing in as the emulated Mac.

**Against the real `AppleAccount`**, with only Grand Slam's answers replaced: the 409 shim works
by replacing `_gsa_request` on the instance, and a fake account would keep that name after a
rename (rule 3).

**Not covered here:** that the library, given `au`, then asks for a code. That decision is in
`_gsa_authenticate` after the SRP exchange, which needs Apple's half to reach. It is the line
`if au in ("secondaryAuth", "trustedDeviceSecondaryAuth")` in the pinned `account.py`.
"""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Iterator
from types import SimpleNamespace
from typing import Any

import pytest

from findmy.reports import AppleAccount, RemoteAnisetteProvider

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


@pytest.fixture
def account() -> Iterator[AppleAccount]:
    made = AppleAccount(RemoteAnisetteProvider("https://example.invalid"))
    yield made
    main.closeAccount(made)


def answersFromGrandSlam(account: AppleAccount, status: dict[str, Any]) -> list[dict]:
    """
    Make the account's Grand Slam exchange answer every request with `status`, without SRP: the
    password check itself needs Apple's half, and the shim only reads what comes back.
    """
    asked: list[dict] = []

    async def gsa_request(parameters: dict[str, Any]) -> dict[str, Any]:
        asked.append(parameters)
        return {"Status": dict(status)}

    account._asyncacc._gsa_request = gsa_request
    return asked


class TestHsc409WithNoAu:
    def test_a_complete_answered_409_without_au_is_read_as_a_second_factor(
            self, account: AppleAccount) -> None:
        asked = answersFromGrandSlam(account, {"ec": 0, "hsc": 409})
        main._treatHsc409AsSecondFactor(account)

        response = asyncio.run(account._asyncacc._gsa_request({"o": "complete"}))

        assert asked == [{"o": "complete"}]
        assert response["Status"]["au"] == "trustedDeviceSecondaryAuth"

    @pytest.mark.parametrize("status", [
        {"ec": 0, "hsc": 200},
        {"ec": 0, "hsc": 409, "au": "secondaryAuth"},
    ])
    def test_anything_else_is_left_as_apple_sent_it(
            self, account: AppleAccount, status: dict) -> None:
        answersFromGrandSlam(account, status)
        main._treatHsc409AsSecondFactor(account)

        response = asyncio.run(account._asyncacc._gsa_request({"o": "complete"}))

        assert response["Status"] == status

    def test_an_init_answered_409_is_not_touched(self, account: AppleAccount) -> None:
        answersFromGrandSlam(account, {"ec": 0, "hsc": 409})
        main._treatHsc409AsSecondFactor(account)

        response = asyncio.run(account._asyncacc._gsa_request({"o": "init"}))

        assert "au" not in response["Status"]


MAC_DEVICE_ID = "00006000-0123456789ABCDEF"


class TestWhatCloudKitIsToldTheDeviceIs:
    def test_a_device_id_that_is_already_a_uuid_is_passed_through_untouched(self) -> None:
        account = SimpleNamespace(device_uuid="9F1C2D3E-4B5A-4C6D-8E7F-001122334455")
        assert icloud_bridge._forCloudKit(account) is account

    def test_a_mac_device_id_gets_the_host_uuid_java_reports(self) -> None:
        bridge = SimpleNamespace(cloudKitDeviceUuid=lambda: "00112233-4455-6677-8899-AABBCCDDEEFF")
        account = SimpleNamespace(device_uuid=MAC_DEVICE_ID,
                                  _anisette=SimpleNamespace(_bridge=bridge), serial="0PENTAGVTEST")

        view = icloud_bridge._forCloudKit(account)

        assert view.device_uuid == "00112233-4455-6677-8899-AABBCCDDEEFF"
        # What the CloudKit client does with it, and what failed before.
        assert uuid.UUID(view.device_uuid).hex.upper() == "00112233445566778899AABBCCDDEEFF"
        # Everything else is the real account's.
        assert view.serial == "0PENTAGVTEST"

    def test_without_a_bridge_it_is_derived_and_stable(self) -> None:
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


class TestWhenTheInternalsMove:
    def test_an_account_without_gsa_request_is_left_alone(self, capsys) -> None:
        account = SimpleNamespace(_asyncacc=SimpleNamespace())
        main._treatHsc409AsSecondFactor(account)  # type: ignore[arg-type]
        assert "hsc 409 will not be read as 2FA" in capsys.readouterr().out
