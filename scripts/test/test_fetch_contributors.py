"""
What a run fired by a merge is allowed to call success.

**Because the run that credits somebody is the one most likely to come back stale.**
`/stats/contributors` is computed asynchronously and cached; merging a pull request invalidates
that cache, so the run triggered by the merge gets either a 202 it has to wait out or a cached
200 from before the merge. The second is indistinguishable from success - nothing changes, no
pull request opens, and the workflow is green having credited nobody.

`--expect-contributor` is what makes that visible, and this is the predicate behind it.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import fetch_contributors as fc  # noqa: E402


def listing(*logins: str) -> list[dict]:
    return [{"login": login, "profileUrl": "", "avatar": None, "commits": 1} for login in logins]


def test_somebody_who_is_in_the_list_is_not_missing():
    assert not fc.missing_from(listing("ubrt", "parawanderer"), "ubrt")


def test_somebody_who_is_absent_is_missing():
    """The whole point: a stale cache produces a list without the person just merged."""
    assert fc.missing_from(listing("parawanderer"), "ubrt")


def test_the_comparison_ignores_case():
    """
    GitHub logins are case-insensitive and the API is not consistent about which case it hands
    back, so a case-sensitive check would fail runs at random.
    """
    assert not fc.missing_from(listing("Fayupable"), "fayupable")
    assert not fc.missing_from(listing("fayupable"), "Fayupable")


def test_expecting_nobody_never_fails():
    """The scheduled and manual runs pass no expectation, and must not be able to fail here."""
    assert not fc.missing_from(listing("parawanderer"), None)
    assert not fc.missing_from([], None)


@pytest.mark.parametrize("login", sorted(fc.EXCLUDED_LOGINS))
def test_an_excluded_login_is_never_missing(login):
    """
    <b>An excluded account was never going to be in the list, so its absence is not a failure.</b>

    Without this, merging a pull request authored by one of the tool accounts in EXCLUDED_LOGINS
    would fail this workflow every single time - correct by the letter, useless in practice, and
    the sort of permanently-red run that teaches people to ignore a red run.
    """
    assert not fc.missing_from(listing("parawanderer"), login)
    assert not fc.missing_from(listing("parawanderer"), login.upper())


def test_the_retry_budget_outlasts_a_cold_cache():
    """
    <b>The budget has to be long enough to be worth having.</b>

    A merge is exactly when the cache is cold, so the run whose purpose is crediting somebody is
    the one most likely to meet a 202. At the previous six retries three seconds apart it gave up
    after eighteen seconds - and gave up silently, keeping the old list and exiting 0.

    Pinned as a total rather than as the two constants, so either can be tuned without a test
    failing over arithmetic that did not change the answer.
    """
    assert fc.STATS_RETRIES * fc.STATS_RETRY_DELAY_S >= 60


def test_a_cold_cache_is_waited_out_rather_than_failed(monkeypatch):
    """
    202 is not an error, and the caller must not see one until the budget is spent.

    Asserted on the number of attempts as well as the result: a loop that happened to return on
    its first pass would pass an assertion that only checked the value.
    """
    attempts = []

    class Response:
        def __init__(self, status, body):
            self.status, self._body = status, body

        def read(self):
            return self._body

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

    def fake_request(url, accept="application/vnd.github+json"):
        attempts.append(url)
        if len(attempts) < 3:
            return Response(202, b"")
        return Response(200, b'[{"author": {"login": "ubrt"}, "total": 3, "weeks": []}]')

    monkeypatch.setattr(fc, "_request", fake_request)
    monkeypatch.setattr(fc.time, "sleep", lambda _seconds: None)

    stats = fc.fetch_stats()

    assert len(attempts) == 3, "it gave up or never retried"
    assert stats[0]["author"]["login"] == "ubrt"


def test_an_empty_body_is_retried_too(monkeypatch):
    """
    <b>A 200 with an empty body means the same thing as a 202 and does not say so.</b>

    GitHub answers that way while computing as well, and `json.loads(b"")` would raise - turning
    a cold cache into a crash rather than a wait.
    """
    attempts = []

    class Response:
        def __init__(self, status, body):
            self.status, self._body = status, body

        def read(self):
            return self._body

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

    def fake_request(url, accept="application/vnd.github+json"):
        attempts.append(url)
        if len(attempts) < 2:
            return Response(200, b"   \n")
        return Response(200, b"[]")

    monkeypatch.setattr(fc, "_request", fake_request)
    monkeypatch.setattr(fc.time, "sleep", lambda _seconds: None)

    assert fc.fetch_stats() == []
    assert len(attempts) == 2


def test_giving_up_raises_rather_than_returning_nothing(monkeypatch):
    """
    <b>Exhausting the budget must not look like "no contributors".</b>

    An empty list would be written to the manifest as the truth, wiping the credits page. The
    RuntimeError is what sends main() down the keep-what-we-have path instead.
    """
    class Response:
        status = 202

        def read(self):
            return b""

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

    monkeypatch.setattr(fc, "_request", lambda *_args, **_kwargs: Response())
    monkeypatch.setattr(fc.time, "sleep", lambda _seconds: None)
    monkeypatch.setattr(fc, "STATS_RETRIES", 3)

    with pytest.raises(RuntimeError):
        fc.fetch_stats()
