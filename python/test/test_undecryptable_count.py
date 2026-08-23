"""
Records that never decrypted, and why the exporter has to mention them.

**`fetch` builds its "not exportable" list by walking what came back**, so anything
`decrypt_records` dropped was absent from that list and from the candidates at once - it
disappeared between two lines and the user saw a shorter table with nothing to explain it.

This is the residue of issue #89's fix. That bug was *one unreadable item ends the whole export*,
fixed by skipping. Skipping silently is the other half of the same mistake, and it was reported by
the same person who found #89's cause - who had to count raw records by hand to notice.

**A count here is not automatically wrong**, which is the reason it is reported as a number and a
caveat rather than raised: a zone legitimately holds records belonging to other parties.
"""

from __future__ import annotations

import asyncio

import pytest

from exporter import icloud


class FakeDecrypted(list):
    """Stands in for FindMy.py's `DecryptedRecords`, which is a list carrying a tally."""

    def __init__(self, records=(), *, skipped=None, first_miss=None):
        super().__init__(records)
        self.skipped = dict(skipped or {})
        self.first_miss = first_miss

    @property
    def skipped_total(self) -> int:
        return sum(self.skipped.values())


@pytest.fixture
def account(monkeypatch):
    """`fetch` with its Apple half replaced, returning whatever tally a test wants."""
    def _fetch(*, skipped=None, first_miss=None):
        decrypted = FakeDecrypted(skipped=skipped, first_miss=first_miss)

        monkeypatch.setattr(icloud, "decrypt_records", lambda *_a, **_k: decrypted)
        monkeypatch.setattr(icloud, "group_records", lambda _d: [])

        return decrypted

    return _fetch


def run_fetch(client):
    """Driven with `asyncio.run`, as the rest of this suite does - there is no pytest-asyncio."""
    return asyncio.run(icloud.fetch(client))


class FakeClient:
    """The three calls `fetch` makes before it starts counting."""

    class _Store:
        async def zone_record_defaults(self, _keys):
            return {}

    def __init__(self):
        self.store = self._Store()

    async def zone_keys(self):
        return {}

    async def records(self):
        return []


class TestTheCountReachesTheCaller:
    def test_a_clean_read_reports_nothing(self, account):
        account()

        fetched = run_fetch(FakeClient())

        assert fetched.undecryptable == 0
        assert fetched.first_miss is None

    def test_every_reason_is_counted_not_just_the_first(self, account):
        # Two different failure kinds. The tally used to be logged as a whole and dropped, so a
        # caller that guessed from one of them would undercount the other.
        account(skipped={"MissingKeyError": 4, "PCSError": 2})

        fetched = run_fetch(FakeClient())

        assert fetched.undecryptable == 6

    def test_the_first_miss_is_carried(self, account):
        # The count alone cannot tell "somebody else's records" from "we are comparing keys in
        # the wrong encoding", and those lead in opposite directions.
        account(skipped={"MissingKeyError": 1}, first_miss="no key held for ABC")

        fetched = run_fetch(FakeClient())

        assert fetched.first_miss == "no key held for ABC"

    def test_it_does_not_become_a_not_exportable_row(self, account):
        # `skipped` is per accessory and names one. These have no beacon id to name - the id is
        # inside the thing that would not open - so folding them in would invent rows.
        account(skipped={"MissingKeyError": 3})

        fetched = run_fetch(FakeClient())

        assert fetched.skipped == []
        assert fetched.undecryptable == 3

    def test_it_is_not_raised(self, account):
        # A zone holds other parties' records legitimately, so this must not fail an export that
        # otherwise worked.
        account(skipped={"MissingKeyError": 99})

        fetched = run_fetch(FakeClient())

        assert fetched.undecryptable == 99
