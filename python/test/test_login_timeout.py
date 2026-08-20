"""
How long the exporter is prepared to wait for one request.

FindMy.py's default is five seconds per request, which is a desktop assumption about a single
call. This makes many: a sign-in is several round trips, and escrow recovery and the CloudKit
fetches after it are heavier than the sign-in. A link slow enough to spend five seconds on any one
of them fails partway through with a bare timeout that names no cause.

**Asserted on what the call passes, not on FindMy.py's internals.** The timeout ends up on a
private session attribute with no accessor, and a test reaching in there breaks on a rename that
changed nothing real. What can actually regress here is this module forgetting to pass it - which
is the whole bug, since the default is silent.
"""

from __future__ import annotations

from typing import Any

import pytest

from exporter import device, icloud


@pytest.fixture
def built(monkeypatch):
    """Record what `make_account` hands to the library, instead of building a real account."""
    seen: dict[str, Any] = {}

    class RecordingAccount:
        def __init__(self, provider, **kwargs):
            seen["provider"] = provider
            seen["kwargs"] = kwargs

    monkeypatch.setattr(icloud, "AsyncAppleAccount", RecordingAccount)

    return seen


@pytest.fixture
def no_stored_identity(monkeypatch):
    monkeypatch.setattr(device, "load", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(icloud.device, "load", lambda *_args, **_kwargs: None)


@pytest.fixture
def a_stored_identity(monkeypatch):
    """An install that has run before, which is the path that is easy to miss."""
    stored = {"uid": "a-uid", "devid": "a-devid", "anisette": None}
    monkeypatch.setattr(icloud.device, "load", lambda *_args, **_kwargs: stored)

    return stored


class TestTheAccount:
    def test_a_first_run_gets_the_raised_timeout(self, built, no_stored_identity):
        icloud.make_account(provider=object())

        assert built["kwargs"]["timeout"] == icloud.LOGIN_TIMEOUT_SECONDS

    def test_a_later_run_gets_it_too(self, built, a_stored_identity):
        # Two constructions of the same object, and only one of them was reached by the fix that
        # first added a keyword here. The second is the one nearly every export takes.
        icloud.make_account(provider=_JsonableProvider())

        assert built["kwargs"]["timeout"] == icloud.LOGIN_TIMEOUT_SECONDS

    def test_it_is_longer_than_the_library_default(self):
        from findmy.util.http import DEFAULT_TIMEOUT

        # If upstream ever raises its own default past this, passing ours would be a downgrade
        # rather than a fix, and nothing else here would notice.
        assert icloud.LOGIN_TIMEOUT_SECONDS > DEFAULT_TIMEOUT


class TestTheAnisetteProvider:
    """
    Its own session, so raising the account's does nothing for this one.

    The Anisette fetch happens inside the login but from the provider's own client. Asserted
    through `to_json`, which is the only place the value is visible without reaching into a
    private attribute - it is carried there precisely so a restored session keeps it.
    """

    def test_a_remote_server_gets_the_raised_timeout(self):
        provider = icloud._make_provider("https://ani.example.invalid", None, None)

        assert provider.to_json()["timeout"] == icloud.LOGIN_TIMEOUT_SECONDS

    def test_a_restored_session_keeps_it(self):
        # `to_json` omits the timeout when it equals the default, so a session restored from a
        # blob written without one silently goes back to five seconds.
        original = icloud._make_provider("https://ani.example.invalid", None, None)

        restored = type(original).from_json(original.to_json())

        assert restored.to_json()["timeout"] == icloud.LOGIN_TIMEOUT_SECONDS


class _JsonableProvider:
    """Stands in for an Anisette provider, which `make_account` serialises into the state blob."""

    def to_json(self):
        return {"type": "aniLocal"}
