"""
A sign-in that succeeds but leaves the app with nothing it can locate with.

Since 2026-10-12 Apple issues the Find My token (`searchPartyToken`) only to a sign-in
whose Anisette machine was minted by a Mac or an iPhone. A remote Anisette server still
completes the sign-in, so without the check in `loginSync` the app stores a session
that looks signed in and then fails every location fetch - issue #311.
"""

from __future__ import annotations

import pytest

from findmy.reports.state import LoginState

import main


class FakeSignedIn:
    """An account whose `login` succeeds, carrying a chosen set of service tokens."""

    def __init__(self, tokens: dict | None) -> None:
        self.service_tokens = tokens
        self.login_state = LoginState.LOGGED_IN

    def login(self, email: str, password: str):
        return LoginState.LOGGED_IN


class FakeTokensThatCannotBeRead:
    """An account whose tokens raise when read, as they do in the wrong login state."""

    def __init__(self) -> None:
        self.login_state = LoginState.LOGGED_IN

    def login(self, email: str, password: str):
        return LoginState.LOGGED_IN

    @property
    def service_tokens(self):
        raise RuntimeError("tokens unavailable")


@pytest.fixture
def signedIn(monkeypatch):
    """
    `loginSync`, with the Anisette and identity machinery stubbed out, for a sign-in
    that succeeds. Mirrors the `signingIn` fixture in conftest, which drives the
    failure path; this one drives the success path, where the token is checked.
    """
    import main as main_module

    def attempt(account):
        monkeypatch.setattr(main_module, "AppleAccount", lambda *a, **k: account)
        monkeypatch.setattr(main_module, "_anisetteProvider", lambda *a, **k: object())
        monkeypatch.setattr(main_module.app_identity, "identityForNewSession", lambda _: {})
        monkeypatch.setattr(main_module.app_identity, "deviceIdsForNewSession", lambda _: {})

        return account, main_module.loginSync(
            "someone@example.com", "hunter2", "https://ani.example"
        )

    return attempt


class TestASignInWithNoFindMyToken:
    def test_it_is_refused_with_its_own_reason(self, signedIn):
        """The server token alone is not enough: only `searchPartyToken` locates."""
        _, answer = signedIn(FakeSignedIn({"mmeAuthToken": "some-token"}))

        assert answer["reason"] == main.REASON_NO_FIND_MY_TOKEN

    def test_no_account_is_handed_over(self, signedIn):
        """
        Nothing is stored from a sign-in that cannot locate. Handing Java the account
        is the bad write behind issues #43 and #119, and this one would fail every
        fetch it was kept for.
        """
        _, answer = signedIn(FakeSignedIn({"mmeAuthToken": "some-token"}))

        assert "account" not in answer

    def test_the_detail_names_what_is_missing(self, signedIn):
        """The report page gets the token name, not just the reason code."""
        _, answer = signedIn(FakeSignedIn({}))

        assert "searchPartyToken" in answer["error"]

    def test_an_empty_token_is_no_token(self, signedIn):
        _, answer = signedIn(FakeSignedIn({"searchPartyToken": ""}))

        assert answer["reason"] == main.REASON_NO_FIND_MY_TOKEN

    def test_no_tokens_at_all_is_no_token(self, signedIn):
        _, answer = signedIn(FakeSignedIn({}))

        assert answer["reason"] == main.REASON_NO_FIND_MY_TOKEN

    def test_tokens_that_cannot_be_read_are_no_token(self, signedIn):
        """
        A session that cannot prove its token is not one to store, whatever the cause
        of the unreadable tokens.
        """
        _, answer = signedIn(FakeTokensThatCannotBeRead())

        assert answer["reason"] == main.REASON_NO_FIND_MY_TOKEN


class TestASignInWithTheToken:
    def test_it_is_handed_over_as_before(self, signedIn):
        account, answer = signedIn(
            FakeSignedIn({"searchPartyToken": "the-token", "mmeAuthToken": "some-token"})
        )

        assert answer.get("account") is account
        assert answer["loginState"] == LoginState.LOGGED_IN.value
        assert "reason" not in answer
        assert "error" not in answer
