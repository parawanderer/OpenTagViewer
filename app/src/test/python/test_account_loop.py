"""
The account's event loop on its own thread - issue #126.

**Against the real `AppleAccount`, not a fake.** Everything here depends on two private details
of the pinned FindMy.py: that the sync wrapper drives its async account through
`self._evt_loop.run_until_complete`, and that the report fetcher reaches Apple through
`self._account.fetch_raw_reports`, looked up on the instance. A fake would keep both names after
a rename and keep passing (rule 3). Only the network is replaced: `fetch_raw_reports` and
`request_pet` are swapped for coroutines that record when they ran.
"""

from __future__ import annotations

import asyncio
import gc
import json
import os
import threading
import time
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest

from findmy import FindMyAccessory
from findmy.reports import AppleAccount, RemoteAnisetteProvider

import account_loop
import main

# Long enough that a walk of several requests takes visibly longer than one request does, and
# short enough that the suite does not notice.
REQUEST_SECONDS = 0.05


class Apple:
    """
    Stands in for Apple's side of the two calls that matter, and remembers how they overlapped.

    `request_pet` is the dangerous one to run beside a fetch: it swaps the account's login state
    out for the length of a round trip. So the thing to prove is that it never runs while a report
    request is in flight - and, the point of the issue, that it does not wait for the whole walk.
    """

    def __init__(self) -> None:
        self.requests = 0
        self.inFlight = 0
        self.overlapped = False
        self.firstRequest = threading.Event()
        self.requestsWhenPetAsked: int | None = None

    async def fetch_raw_reports(self, devices):
        self.inFlight += 1
        try:
            self.firstRequest.set()
            await asyncio.sleep(REQUEST_SECONDS)
            self.requests += 1
            return []
        finally:
            self.inFlight -= 1

    async def request_pet(self):
        if self.inFlight:
            self.overlapped = True
        self.requestsWhenPetAsked = self.requests
        await asyncio.sleep(REQUEST_SECONDS)
        if self.inFlight:
            self.overlapped = True
        return "pet"


@pytest.fixture
def apple():
    return Apple()


@pytest.fixture
def account(apple):
    """A real account, attached the way `main` attaches one, with Apple replaced."""
    made = AppleAccount(RemoteAnisetteProvider("https://example.invalid"))

    # Before attaching, as the real method is: attach wraps whatever it finds there.
    made._asyncacc.fetch_raw_reports = apple.fetch_raw_reports
    made._asyncacc.request_pet = apple.request_pet

    assert account_loop.attach(made)
    yield made
    main.closeAccount(made)


def anAccessoryWithAWalkAhead() -> FindMyAccessory:
    """
    An accessory with no alignment, paired long enough ago that finding it takes several requests.

    Apple answers nothing here, so `fetch_location` walks the whole history - the first-import
    case the issue is about, in miniature.
    """
    return FindMyAccessory(
        master_key=os.urandom(28),
        skn=os.urandom(32),
        sks=os.urandom(32),
        paired_at=datetime.now(tz=timezone.utc) - timedelta(days=10),
    )


def fetchingInTheBackground(account, interleaved: bool):
    """Start a location fetch on another thread, the way Java's Rx pool would."""
    failures: list[BaseException] = []

    def fetch():
        try:
            if interleaved:
                with account_loop.interleaving():
                    account.fetch_location(anAccessoryWithAWalkAhead())
            else:
                account.fetch_location(anAccessoryWithAWalkAhead())
        except BaseException as e:  # noqa: BLE001 - reported to the test, not swallowed
            failures.append(e)

    thread = threading.Thread(target=fetch)
    thread.start()
    return thread, failures


class TestAnICloudCallDuringALongFetch:
    def test_it_waits_for_one_request_rather_than_the_whole_walk(self, account, apple):
        fetching, failures = fetchingInTheBackground(account, interleaved=True)
        assert apple.firstRequest.wait(5)

        assert account.request_pet() == "pet"
        fetching.join(30)

        assert not failures
        assert apple.requests > 2, "the walk was too short to show anything"
        assert apple.requestsWhenPetAsked is not None
        assert apple.requestsWhenPetAsked < apple.requests, (
            "request_pet ran only once the whole walk had finished - which is issue #126")

    def test_it_never_runs_while_a_request_is_in_flight(self, account, apple):
        """
        `request_pet` rewrites the login state across an await, so a report request running
        beside it would read a session that is half somebody else's.
        """
        fetching, failures = fetchingInTheBackground(account, interleaved=True)
        assert apple.firstRequest.wait(5)

        account.request_pet()
        fetching.join(30)

        assert not failures
        assert not apple.overlapped

    def test_outside_interleaving_a_call_still_holds_the_account_for_its_whole_length(
            self, account, apple):
        """
        Anything not known to be separable keeps the exclusion it always had. Also proves this
        test's own setup can tell the difference - the walk really is long enough to be waited
        behind.
        """
        fetching, failures = fetchingInTheBackground(account, interleaved=False)
        assert apple.firstRequest.wait(5)

        account.request_pet()
        fetching.join(30)

        assert not failures
        assert apple.requestsWhenPetAsked == apple.requests


class TestTheLoopItself:
    def test_it_runs_on_a_thread_of_its_own(self, account):
        attached = account._evt_loop

        assert isinstance(attached, account_loop.AccountLoop)
        assert attached.is_running()
        assert threading.current_thread() is not attached._thread

    def test_attaching_twice_is_harmless(self, account):
        attached = account._evt_loop

        assert account_loop.attach(account)
        assert account._evt_loop is attached

    def test_calls_from_two_threads_at_once_both_complete(self, account, apple):
        """The failure the Java lock existed to prevent: "This event loop is already running"."""
        results: list[str] = []
        threads = [threading.Thread(target=lambda: results.append(account.request_pet()))
                   for _ in range(4)]

        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(10)

        assert results == ["pet"] * 4

    def test_a_sync_call_from_the_loop_thread_fails_instead_of_hanging(self, account):
        async def callingBackIn():
            return account.request_pet()

        with pytest.raises(RuntimeError, match="own event loop thread"):
            account._evt_loop.run_until_complete(callingBackIn())

    def test_an_account_without_the_internals_is_left_alone(self):
        class Moved:
            pass

        assert not account_loop.attach(Moved())

    def test_a_loop_that_is_already_running_is_not_taken(self):
        account = AppleAccount(RemoteAnisetteProvider("https://example.invalid"))

        async def attachFromInside():
            account._evt_loop = asyncio.get_running_loop()
            return account_loop.attach(account)

        loop = asyncio.new_event_loop()
        try:
            assert not loop.run_until_complete(attachFromInside())
        finally:
            loop.close()


class TestClosing:
    def test_closing_stops_the_thread_and_hands_the_loop_back(self, apple):
        account = AppleAccount(RemoteAnisetteProvider("https://example.invalid"))
        account_loop.attach(account)
        attached = account._evt_loop

        assert main.closeAccount(account)

        assert not attached._thread.is_alive()
        assert account._evt_loop is attached.loop
        assert not account._evt_loop.is_running()

    def test_an_account_dropped_without_closing_does_not_leave_its_thread_behind(self):
        """A failed sign-in, or a code screen walked away from, never reaches `closeAccount`."""
        account = AppleAccount(RemoteAnisetteProvider("https://example.invalid"))
        account_loop.attach(account)
        thread = account._evt_loop._thread

        del account
        gc.collect()
        thread.join(5)

        assert not thread.is_alive()

    def test_a_call_waiting_its_turn_is_told_the_account_went_away(self, account, apple):
        fetching, _ = fetchingInTheBackground(account, interleaved=False)
        assert apple.firstRequest.wait(5)

        waiting: list[BaseException] = []

        def askForAPet():
            try:
                account.request_pet()
            except BaseException as e:  # noqa: BLE001
                waiting.append(e)

        asker = threading.Thread(target=askForAPet)
        asker.start()
        time.sleep(REQUEST_SECONDS)

        account._evt_loop.shutDown()
        asker.join(10)
        fetching.join(10)

        assert not asker.is_alive(), "a waiting call hung when the account closed"
        assert len(waiting) == 1
        assert isinstance(waiting[0], RuntimeError)
        assert "closed" in str(waiting[0])

        # Hands the loop back so the fixture can close the session on it, as `closeAccount`
        # does in the app - which closes before it detaches, never after.
        account_loop.detach(account)

    def test_a_call_after_closing_fails_instead_of_hanging(self, account):
        account._evt_loop.shutDown()

        with pytest.raises(RuntimeError, match="closed"):
            account._evt_loop.run_until_complete(asyncio.sleep(0))

        account_loop.detach(account)


class TestWhereTheAppAttachesIt:
    """`main` has to attach every account it hands Java, or the iCloud flow refuses to open."""

    def test_a_restored_account_comes_back_attached(self):
        from findmy.reports.state import LoginState

        stored = AppleAccount(RemoteAnisetteProvider("https://ani.example.com")).to_json()
        stored["login"]["state"] = LoginState.LOGGED_IN.value

        restored = main.getAccount(json.dumps(stored))
        try:
            assert isinstance(restored._evt_loop, account_loop.AccountLoop)
        finally:
            main.closeAccount(restored)

    def test_a_new_sign_in_is_attached_before_it_logs_in(self, monkeypatch):
        """Before, because the loop has to be idle to be handed over - see `attach`."""
        from findmy.reports.state import LoginState

        loggedInOn: list[str] = []

        def building(*args, **kwargs):
            made = AppleAccount(RemoteAnisetteProvider("https://ani.example.com"))

            async def login(username, password):
                loggedInOn.append(threading.current_thread().name)
                return LoginState.LOGGED_IN

            made._asyncacc.login = login
            return made

        monkeypatch.setattr(main, "AppleAccount", building)
        monkeypatch.setattr(main, "_anisetteProvider", lambda *a, **k: object())
        monkeypatch.setattr(main.app_identity, "identityForNewSession", lambda _: {})
        monkeypatch.setattr(main.app_identity, "deviceIdsForNewSession", lambda _: {})

        signedIn = main.loginSync("someone@example.com", "hunter2", "https://ani.example")
        try:
            assert loggedInOn == ["findmy-account-loop"]
        finally:
            main.closeAccount(signedIn["account"])


class TestWhenTheInternalsMove:
    """Degrading, never raising: a failed attach in `getAccount` would sign the user out."""

    def test_an_account_without_fetch_raw_reports_still_gets_its_thread(self):
        account = SimpleNamespace(_asyncacc=SimpleNamespace(), _evt_loop=asyncio.new_event_loop())

        assert account_loop.attach(account)
        try:
            assert account._evt_loop.is_running()
            assert not account._evt_loop._turnsPerRequest
        finally:
            account._evt_loop.shutDown()

    def test_without_it_a_fetch_inside_interleaving_still_takes_a_whole_turn(self, apple):
        """Otherwise the fetch would run beside `request_pet` with no exclusion at all."""
        made = AppleAccount(RemoteAnisetteProvider("https://example.invalid"))

        async def closing():
            pass

        # The real sync wrapper over an async account that has lost the method.
        made._asyncacc = SimpleNamespace(request_pet=apple.request_pet, close=closing)

        async def walk(devices):
            # The fetcher's own loop over requests, standing in for a method FindMy.py renamed.
            for _ in range(10):
                await apple.fetch_raw_reports(devices)
            return []

        assert account_loop.attach(made)
        try:
            fetching_failures: list[BaseException] = []

            def fetch():
                try:
                    with account_loop.interleaving():
                        made._evt_loop.run_until_complete(walk([]))
                except BaseException as e:  # noqa: BLE001
                    fetching_failures.append(e)

            fetching = threading.Thread(target=fetch)
            fetching.start()
            assert apple.firstRequest.wait(5)

            made.request_pet()
            fetching.join(30)

            assert not fetching_failures
            assert not apple.overlapped
            assert apple.requestsWhenPetAsked == apple.requests
        finally:
            main.closeAccount(made)
