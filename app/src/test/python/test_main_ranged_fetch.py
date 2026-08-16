"""Tests for the ranged history fetch in the Chaquopy bridge.

`fetch_location_history(accessory)` cannot honour a time range. FindMy 0.9.x dropped the range
parameters 0.7.6 had, and for a rolling-key accessory the call delegates to
`_fetch_accessory_reports(..., only_latest=True)`, which walks backwards from *now* and returns
as soon as the first batch of keys yields anything - roughly the last day.

So every day of history came back as today's reports, which the caller then filtered away to
nothing. The history screen showed one populated day and empty days behind it, for every tag,
with no error in logcat and nothing wrong on the Java side.

`_fetchReportsInRange` generates the window's keys itself, via `keys_between`, and asks for
those. These tests cover the parts that decide whether it is correct and whether it is safe to
point at Apple - nothing here touches the network.
"""

from datetime import datetime, timedelta, timezone

import pytest

import main


class FakeReport:
    """Minimal stand-in for a findmy LocationReport."""

    def __init__(self, timestamp):
        self.timestamp = timestamp

    def __lt__(self, other):
        return self.timestamp < other.timestamp


class FakeKey:
    """Stands in for a findmy KeyPair: needs only to be hashable and comparable."""

    def __init__(self, index):
        self.index = index

    def __hash__(self):
        return hash(self.index)

    def __eq__(self, other):
        return isinstance(other, FakeKey) and other.index == self.index

    def __repr__(self):
        return f"FakeKey({self.index})"


class FakeAccessory:
    """Yields one key per index in the window, and records alignment updates."""

    def __init__(self, indices, width=0):
        self._indices = list(indices)
        self._width = width
        self.narrowed = False
        self.keys_between_calls = []
        self.alignment_updates = []

    def keys_between(self, start, end):
        self.keys_between_calls.append((start, end))
        for index in self._indices:
            yield index, FakeKey(index)

    def get_min_index(self, _dt):
        return 0

    def get_max_index(self, _dt):
        return 0 if self.narrowed else self._width

    def update_alignment(self, dt, index):
        self.alignment_updates.append((dt, index))


class FakeAccount:
    """
    Records every batch of keys it is asked for, and answers from a per-index map.

    Failures are keyed by the chunk's first index rather than by call number, so a retry of a
    doomed chunk fails again - otherwise a retry test would pass by accident.
    """

    def __init__(self, reports_by_index=None, doomed_chunks=(), fail_first_calls=0,
                 on_fetch_location=None):
        self.requested_batches = []
        self.fetch_location_calls = 0
        self._reports_by_index = reports_by_index or {}
        self._doomed_chunks = set(doomed_chunks)
        self._fail_first_calls = fail_first_calls
        self._on_fetch_location = on_fetch_location

    def fetch_location(self, accessory):
        self.fetch_location_calls += 1
        if self._on_fetch_location:
            self._on_fetch_location(accessory)
        return None

    def fetch_location_history(self, keys):
        assert isinstance(keys, list), (
            "the ranged fetch must pass a list of keys - passing the accessory itself is "
            "exactly what returned the latest reports regardless of the range asked for"
        )
        self.requested_batches.append(list(keys))

        if len(self.requested_batches) <= self._fail_first_calls:
            raise TimeoutError("transient")

        if keys and keys[0].index in self._doomed_chunks:
            raise TimeoutError("this chunk always fails")

        return {key: self._reports_by_index.get(key.index, []) for key in keys}


def _at(index):
    """An AirTag steps its key index every 15 minutes."""
    return datetime(2026, 8, 9, tzinfo=timezone.utc) + timedelta(minutes=15 * index)


# --------------------------------------------------------------------------
# asking for the right keys
# --------------------------------------------------------------------------

def test_only_asks_for_the_keys_inside_the_window():
    accessory = FakeAccessory(indices=range(10, 20))
    account = FakeAccount()

    main._fetchReportsInRange(account, accessory, _at(10), _at(19))

    assert len(account.requested_batches) == 1
    assert [key.index for key in account.requested_batches[0]] == list(range(10, 20))


def test_returns_reports_from_every_batch():
    accessory = FakeAccessory(indices=range(0, 600))
    account = FakeAccount(reports_by_index={5: [FakeReport(1)], 400: [FakeReport(2)]})

    reports = main._fetchReportsInRange(account, accessory, _at(0), _at(599))

    # Three batches. A stop-at-the-first-hit fetch would have returned index 5 and never
    # looked at 400 - which is the bug this replaced.
    assert len(account.requested_batches) == 3
    assert sorted(report.timestamp for report in reports) == [1, 2]


def test_stays_under_apples_key_limit_per_request():
    accessory = FakeAccessory(indices=range(0, 600))
    account = FakeAccount()

    main._fetchReportsInRange(account, accessory, _at(0), _at(599))

    for batch in account.requested_batches:
        assert len(batch) <= 290, "Apple rejects requests carrying much more than 290 keys"


def test_with_no_keys_makes_no_requests():
    accessory = FakeAccessory(indices=[])
    account = FakeAccount()

    assert main._fetchReportsInRange(account, accessory, _at(0), _at(1)) == []
    assert account.requested_batches == []


# --------------------------------------------------------------------------
# alignment
# --------------------------------------------------------------------------

def test_aligns_using_the_index_each_key_came_from():
    """
    The piece the library cannot do for us. `_fetch_key_reports` is handed plain keys and has
    no idea which index each belongs to, so without this a ranged fetch would leave alignment
    untouched and every later fetch would search just as widely.
    """
    accessory = FakeAccessory(indices=[7, 8, 9])
    report = FakeReport(1234)
    account = FakeAccount(reports_by_index={8: [report]})

    main._fetchReportsInRange(account, accessory, _at(7), _at(9))

    assert accessory.alignment_updates == [(report.timestamp, 8)]


def test_establishes_alignment_before_generating_keys():
    """
    Ordering matters. An unaligned accessory spans its whole life, so keys_between would
    yield tens of thousands of keys for a single day of history.
    """
    accessory = FakeAccessory(indices=[1, 2], width=50_000)
    account = FakeAccount(on_fetch_location=lambda acc: setattr(acc, "narrowed", True))

    main._fetchReportsInRange(account, accessory, _at(1), _at(2))

    assert account.fetch_location_calls == 1
    assert accessory.keys_between_calls, "keys must be generated after alignment, not before"


def test_gives_up_when_alignment_cannot_be_established():
    """Nothing was found anywhere, so a ranged sweep would search the same empty range."""
    accessory = FakeAccessory(indices=range(0, 10_000), width=50_000)
    account = FakeAccount()  # never narrows

    reports = main._fetchReportsInRange(account, accessory, _at(0), _at(9_999))

    assert reports == []
    assert account.requested_batches == []
    assert accessory.keys_between_calls == []


# --------------------------------------------------------------------------
# not hammering Apple
# --------------------------------------------------------------------------

def test_caps_how_many_requests_it_will_make():
    """
    A history screen must not be able to fire hundreds of requests at Apple. That is the
    account-flagging risk from issue #30 arriving through a different door.
    """
    accessory = FakeAccessory(indices=range(0, 10_000))
    account = FakeAccount()

    main._fetchReportsInRange(account, accessory, _at(0), _at(9_999))

    assert len(account.requested_batches) == 8

    searched = [key.index for batch in account.requested_batches for key in batch]
    # Keeps the newest end of the window: Apple drops reports older than ~7 days anyway.
    assert searched[-1] == 9_999


def test_survives_one_failing_batch(monkeypatch):
    monkeypatch.setattr(main, "_RANGE_FETCH_RETRY_DELAY_SECONDS", 0)
    accessory = FakeAccessory(indices=range(0, 600))
    account = FakeAccount(
        reports_by_index={5: [FakeReport(1)], 400: [FakeReport(2)]},
        doomed_chunks=[0])

    reports = main._fetchReportsInRange(account, accessory, _at(0), _at(599))

    # The first batch is lost; a short result beats none.
    assert [report.timestamp for report in reports] == [2]


def test_retries_a_request_that_times_out(monkeypatch):
    """
    Apple's endpoint times out often enough to hit by hand, and a single day of history is a
    single request - so without a retry, one timeout emptied a whole day.
    """
    monkeypatch.setattr(main, "_RANGE_FETCH_RETRY_DELAY_SECONDS", 0)
    accessory = FakeAccessory(indices=range(0, 10))
    account = FakeAccount(reports_by_index={5: [FakeReport(1)]}, fail_first_calls=1)

    reports = main._fetchReportsInRange(account, accessory, _at(0), _at(9))

    assert len(account.requested_batches) == 2, "the failed request should have been retried"
    assert [report.timestamp for report in reports] == [1]


def test_gives_up_after_the_retry_rather_than_looping(monkeypatch):
    monkeypatch.setattr(main, "_RANGE_FETCH_RETRY_DELAY_SECONDS", 0)
    accessory = FakeAccessory(indices=range(0, 10))
    account = FakeAccount(doomed_chunks=[0])

    with pytest.raises(RuntimeError):
        main._fetchReportsInRange(account, accessory, _at(0), _at(9))

    assert len(account.requested_batches) == main._RANGE_FETCH_ATTEMPTS


def test_a_range_whose_every_request_failed_is_an_error_not_an_empty_day(monkeypatch):
    """
    The bug this fixes: one timed-out request returned [], the day rendered as "0 reports",
    and nothing distinguished that from a tag genuinely not being seen. An absence we cannot
    vouch for has to be an error, so the caller can skip the accessory instead.
    """
    monkeypatch.setattr(main, "_RANGE_FETCH_RETRY_DELAY_SECONDS", 0)
    accessory = FakeAccessory(indices=range(0, 99))
    account = FakeAccount(doomed_chunks=[0])

    with pytest.raises(RuntimeError, match="refusing to report an empty range"):
        main._fetchReportsInRange(account, accessory, _at(0), _at(98))


def test_a_genuinely_empty_range_is_not_an_error():
    """The other side of it: Apple answering "nothing here" is a real, reportable answer."""
    accessory = FakeAccessory(indices=range(0, 99))
    account = FakeAccount(reports_by_index={})

    assert main._fetchReportsInRange(account, accessory, _at(0), _at(98)) == []


# --------------------------------------------------------------------------
# the library contract this rests on
# --------------------------------------------------------------------------

# --------------------------------------------------------------------------
# telling Java the difference between "nothing there" and "could not find out"
# --------------------------------------------------------------------------

class FakeRequest:
    def __init__(self, beacon_id):
        self._beacon_id = beacon_id

    def getBeaconId(self):
        return self._beacon_id

    def getAccessoryJson(self):
        return "{}"


class FakeJavaList:
    """Stands in for the List<AccessoryRequest> Java passes across the bridge."""

    def __init__(self, items):
        self._items = list(items)

    def size(self):
        return len(self._items)

    def get(self, index):
        return self._items[index]


class StubAccessory:
    def to_json(self):
        return {}


@pytest.fixture
def stub_accessory(monkeypatch):
    """Reading an accessory needs real data; the tests here only care about failure handling."""
    monkeypatch.setattr(main, "accessoryFromJson", lambda _json: StubAccessory())


def test_getReports_reports_an_error_when_every_accessory_fails(monkeypatch, stub_accessory):
    """
    Java's mapResults only raises when this module returns None. A dict missing the beacon
    reads as "no reports for that day", so the history screen showed a confident "0 reports"
    for a day it had failed to fetch - with no error state and no Retry button, because as far
    as Java was concerned the call had succeeded.
    """
    monkeypatch.setattr(main, "_fetchReportsInRange", _raise)

    result = main.getReports(object(), FakeJavaList([FakeRequest("beacon-a")]), 0, 1)

    assert result is None


def test_getReports_still_returns_what_did_work(monkeypatch, stub_accessory):
    """A partial result is worth keeping: those accessories have fresh reports and alignment."""
    def fail_for_a(_account, _accessory, _start, _end):
        if fail_for_a.calls == 0:
            fail_for_a.calls += 1
            raise TimeoutError("first one fails")
        return []
    fail_for_a.calls = 0

    monkeypatch.setattr(main, "_fetchReportsInRange", fail_for_a)

    result = main.getReports(
        object(), FakeJavaList([FakeRequest("beacon-a"), FakeRequest("beacon-b")]), 0, 1)

    assert result is not None
    assert list(result) == ["beacon-b"]


def test_getLastReports_reports_an_error_when_every_accessory_fails(monkeypatch, stub_accessory):
    monkeypatch.setattr(main, "_fetchReportsForAccessory", _raise)

    result = main.getLastReports(object(), FakeJavaList([FakeRequest("beacon-a")]), 24)

    assert result is None


def test_no_accessories_at_all_is_not_an_error():
    """An empty request list is a valid, empty answer - not a failure."""
    assert main.getReports(object(), FakeJavaList([]), 0, 1) == {}
    assert main.getLastReports(object(), FakeJavaList([]), 24) == {}


def _raise(*_args, **_kwargs):
    raise TimeoutError("Apple timed out")


def test_findmy_still_exposes_what_the_ranged_fetch_relies_on():
    """
    The approach rests on two pieces of the installed library. If either changes shape, fail
    here rather than on a user's history screen. See rule 3 in AGENTS.md.
    """
    import inspect

    from findmy.accessory import FindMyAccessory
    from findmy.reports.account import AppleAccount

    assert hasattr(FindMyAccessory, "keys_between")
    assert hasattr(FindMyAccessory, "update_alignment")

    params = inspect.signature(AppleAccount.fetch_location_history).parameters
    assert "keys" in params, (
        "fetch_location_history no longer takes a keys argument, and the ranged fetch passes "
        "it a list of keys"
    )
