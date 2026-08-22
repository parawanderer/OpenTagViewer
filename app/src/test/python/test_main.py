"""Tests for the Chaquopy bridge module (app/src/main/python/main.py).

These run on plain CPython - no Android, no emulator, no Apple account - and
cover the parts of the FindMy 0.9.x migration that are pure logic:

  * converting an exported plist to the accessory JSON the new library needs
  * the client-side time filtering that replaced 0.7.6's server-side windowing
  * the alignment probe that keeps the first fetch after an upgrade from
    hammering Apple with hundreds of requests
"""

from datetime import datetime, timedelta, timezone
from pathlib import Path

import asyncio
import json
import re

import pytest

import main

RESOURCES = Path(__file__).resolve().parents[1] / "resources"
BEACON_PLISTS = sorted(RESOURCES.glob("*/OwnedBeacons/*.plist"))


def _read_plist_xml(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class FakeReport:
    """Minimal stand-in for findmy LocationReport - only what _serialize/_filter touch."""

    def __init__(self, timestamp, latitude=52.0, longitude=4.0):
        self.timestamp = timestamp
        self.latitude = latitude
        self.longitude = longitude
        self.confidence = 2
        self.horizontal_accuracy = 10
        self.status = 0

    # sorted() is called on these in _serializeReports
    def __lt__(self, other):
        return self.timestamp < other.timestamp


def _ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


# --------------------------------------------------------------------------
# convertPlistToJson
# --------------------------------------------------------------------------

@pytest.mark.skipif(not BEACON_PLISTS, reason="no redacted beacon fixture available")
@pytest.mark.parametrize("plist_path", BEACON_PLISTS, ids=lambda p: p.parent.parent.name)
def test_convertPlistToJson_produces_restorable_json(plist_path):
    """The exact path a pre-0.9 beacon takes during the lazy backfill."""
    result = main.convertPlistToJson(_read_plist_xml(plist_path))

    assert result is not None, "a real exported plist must convert"
    parsed = json.loads(result)
    assert parsed.get("type") == "accessory"

    # Must survive the DB round trip: this JSON is stored in accessory_json and
    # read back via from_json on every subsequent fetch.
    from findmy import FindMyAccessory
    restored = FindMyAccessory.from_json(parsed)
    assert restored is not None


# --------------------------------------------------------------------------
# accessoryFromJson
#
# Two kinds of tag now reach the fetch path, and they are sibling classes rather than
# a base and a subclass: an Apple-paired accessory derives its keys from a master key
# and two secrets, a self-generated one carries a plain list and derives nothing.
# FindMy.py has no factory that reads both, so the dispatch is ours - which makes
# "does an existing user's stored tag still load" a question worth asking out loud.
# --------------------------------------------------------------------------

_CUSTOM_ACCESSORY = {
    "type": "custom_rolling_key_accessory",
    # Two 28-byte private keys, which is the size FindMy.py's KeyPair expects.
    "private_keys": ["11" * 28, "22" * 28],
    "name": "A tag nobody paired",
    "identifier": "openhaystack-1",
}


@pytest.mark.skipif(not BEACON_PLISTS, reason="no redacted beacon fixture available")
def test_an_apple_paired_tag_still_loads():
    """
    The upgrade case, and the one with something to lose.

    Every beacon an existing user has is stored as this. If the dispatch got it wrong they
    would all stop locating at once, on a build that installed cleanly.
    """
    stored = main.convertPlistToJson(_read_plist_xml(BEACON_PLISTS[0]))

    accessory = main.accessoryFromJson(stored)

    assert isinstance(accessory, main.FindMyAccessory)
    # Round-trips through the same door it came out of, which is what the fetch path does
    # after every call to persist the updated alignment.
    assert main.accessoryFromJson(json.dumps(accessory.to_json())) is not None


def test_a_self_generated_tag_loads_as_the_other_kind():
    accessory = main.accessoryFromJson(json.dumps(_CUSTOM_ACCESSORY))

    assert isinstance(accessory, main.FixedRollingKeyPairAccessory)
    assert accessory.identifier == "openhaystack-1"
    assert accessory.name == "A tag nobody paired"


def test_a_self_generated_tag_survives_the_round_trip_the_fetch_path_makes():
    """
    `getLastReports` writes `to_json()` back to the database after every fetch, so a tag that
    reads but does not re-read would work once and then be unreadable - a failure that only
    appears on the *second* refresh.
    """
    once = main.accessoryFromJson(json.dumps(_CUSTOM_ACCESSORY))
    twice = main.accessoryFromJson(json.dumps(once.to_json()))

    assert twice.identifier == once.identifier
    assert list(twice.keys_between(
        datetime(2026, 1, 1, tzinfo=timezone.utc),
        datetime(2026, 1, 2, tzinfo=timezone.utc),
    )) == list(once.keys_between(
        datetime(2026, 1, 1, tzinfo=timezone.utc),
        datetime(2026, 1, 2, tzinfo=timezone.utc),
    ))


def test_both_kinds_offer_everything_the_fetch_path_uses():
    """
    The reason the rest of the fetch path never learns which kind it has.

    If a future library version moved one of these off one of the two classes, the fetch would
    fail at runtime for that kind only - and only for whoever owns that kind of tag.
    """
    for accessoryType in main.ACCESSORY_TYPES.values():
        for method in ("keys_between", "get_min_index", "get_max_index",
                       "update_alignment", "to_json", "from_json"):
            assert hasattr(accessoryType, method), f"{accessoryType.__name__} lost {method}"


def test_an_unknown_type_is_refused_rather_than_guessed_at():
    """
    Loud, because the quiet alternative is worse.

    Guessing would fetch against keys from a different derivation, which finds nothing and
    reads as a tag that is simply out of range rather than as a bug.
    """
    with pytest.raises(ValueError) as raised:
        main.accessoryFromJson(json.dumps({"type": "something_from_the_future"}))

    assert "something_from_the_future" in str(raised.value)


@pytest.mark.parametrize("blob", ['{"no": "type"}', '"a string"', "null", "[]"])
def test_json_that_is_not_an_accessory_is_refused(blob):
    with pytest.raises(ValueError):
        main.accessoryFromJson(blob)


def test_convertPlistToJson_returns_none_on_garbage():
    """Failure must be None, not an exception - the caller retries later."""
    assert main.convertPlistToJson("not a plist at all") is None


def test_convertPlistToJson_returns_none_on_empty():
    assert main.convertPlistToJson("") is None


# --------------------------------------------------------------------------
# _filterReportsByTimeRange
#
# 0.9.x removed the server-side time window, so this filtering is now the only
# thing enforcing the range the Java side asked for.
# --------------------------------------------------------------------------

def test_filter_includes_reports_inside_the_window():
    now = datetime.now(tz=timezone.utc)
    inside = FakeReport(now - timedelta(hours=1))
    out = main._filterReportsByTimeRange([inside], _ms(now - timedelta(hours=24)), _ms(now))
    assert out == [inside]


def test_filter_excludes_reports_before_the_window():
    now = datetime.now(tz=timezone.utc)
    old = FakeReport(now - timedelta(days=30))
    out = main._filterReportsByTimeRange([old], _ms(now - timedelta(hours=24)), _ms(now))
    assert out == []


def test_filter_excludes_reports_after_the_window():
    now = datetime.now(tz=timezone.utc)
    future = FakeReport(now + timedelta(hours=2))
    out = main._filterReportsByTimeRange([future], _ms(now - timedelta(hours=24)), _ms(now))
    assert out == []


def test_filter_boundaries_are_inclusive():
    """A report exactly on either edge must be kept, not silently dropped."""
    now = datetime.now(tz=timezone.utc).replace(microsecond=0)
    start = now - timedelta(hours=24)

    at_start = FakeReport(start)
    at_end = FakeReport(now)

    out = main._filterReportsByTimeRange([at_start, at_end], _ms(start), _ms(now))
    assert out == [at_start, at_end]


def test_filter_skips_reports_without_a_timestamp():
    now = datetime.now(tz=timezone.utc)
    out = main._filterReportsByTimeRange([FakeReport(None)], _ms(now - timedelta(hours=1)), _ms(now))
    assert out == []


def test_filter_with_no_bounds_keeps_everything():
    now = datetime.now(tz=timezone.utc)
    reports = [FakeReport(now - timedelta(days=d)) for d in range(5)]
    assert main._filterReportsByTimeRange(reports, None, None) == reports


# --------------------------------------------------------------------------
# _serializeReports
# --------------------------------------------------------------------------

def test_serializeReports_shape_matches_java_expectations():
    now = datetime.now(tz=timezone.utc)
    items = main._serializeReports([FakeReport(now)])

    assert len(items) == 1
    item = items[0]
    # PythonAppleService.mapResults reads exactly these keys.
    for key in ("publishedAt", "description", "timestamp", "confidence",
                "latitude", "longitude", "horizontalAccuracy", "status"):
        assert key in item, f"missing {key} - would break mapResults on the Java side"

    # published_at no longer exists in 0.9.x, so it mirrors timestamp.
    assert item["publishedAt"] == item["timestamp"]
    assert item["description"] == ""


def test_serializeReports_sorts_chronologically():
    now = datetime.now(tz=timezone.utc)
    unsorted_reports = [
        FakeReport(now),
        FakeReport(now - timedelta(hours=5)),
        FakeReport(now - timedelta(hours=2)),
    ]
    items = main._serializeReports(unsorted_reports)
    timestamps = [i["timestamp"] for i in items]
    assert timestamps == sorted(timestamps)


# --------------------------------------------------------------------------
# _narrowAlignmentIfNeeded
#
# Guards the issue #30 concern: an unaligned accessory searches its whole
# lifetime of key indices, which at ~290 keys per request is hundreds of calls
# to Apple on the first fetch after upgrading.
# --------------------------------------------------------------------------

class FakeAccessory:
    """Reports a key-index width, and can narrow once a fetch has "found" something."""

    def __init__(self, width):
        self._width = width
        self.narrowed = False

    def get_min_index(self, _dt):
        return 0

    def get_max_index(self, _dt):
        return 0 if self.narrowed else self._width


class FakeAccount:
    def __init__(self, latest=None, history=None, on_fetch=None):
        self.fetch_location_calls = 0
        self.fetch_history_calls = 0
        self._latest = latest
        self._history = history if history is not None else []
        self._on_fetch = on_fetch

    def fetch_location(self, accessory):
        self.fetch_location_calls += 1
        if self._on_fetch:
            self._on_fetch(accessory)
        return self._latest

    def fetch_location_history(self, accessory):
        self.fetch_history_calls += 1
        return self._history


def test_wide_window_fetches_latest_instead_of_history():
    """
    An unaligned tag must not trigger a full-history key search. Asking for the latest
    location narrows the window as a side effect and still returns something useful.
    """
    now = datetime.now(tz=timezone.utc)
    report = FakeReport(now)
    accessory = FakeAccessory(width=50_000)
    account = FakeAccount(latest=report, on_fetch=lambda acc: setattr(acc, "narrowed", True))

    out = main._fetchReportsForAccessory(account, accessory, now - timedelta(hours=24), now)

    assert account.fetch_location_calls == 1
    assert account.fetch_history_calls == 0, "must not also search the whole history"
    assert out.reports == [report]
    assert not out.bounded_to_window, "the probe ignores the window, and says so"


def test_narrow_window_uses_the_normal_history_fetch():
    """Once aligned, fetches go back to normal - no extra round trip per call."""
    now = datetime.now(tz=timezone.utc)
    history = [FakeReport(now), FakeReport(now - timedelta(hours=2))]
    accessory = FakeAccessory(width=96)  # about a day for an AirTag
    account = FakeAccount(history=history)

    out = main._fetchReportsForAccessory(account, accessory, now - timedelta(hours=24), now)

    assert account.fetch_history_calls == 1
    assert account.fetch_location_calls == 0
    assert out.reports == history
    assert out.bounded_to_window, "a history fetch honours the requested window"


def test_wide_window_with_no_reports_does_not_then_search_the_history():
    """
    The regression this design exists to avoid: a tag with no recent reports used to
    traverse the entire range for the probe, narrow nothing, then traverse it again for
    the history fetch - double the work in the worst case.
    """
    now = datetime.now(tz=timezone.utc)
    accessory = FakeAccessory(width=50_000)          # never narrows
    account = FakeAccount(latest=None)

    out = main._fetchReportsForAccessory(account, accessory, now - timedelta(hours=24), now)

    assert account.fetch_location_calls == 1
    assert account.fetch_history_calls == 0, "must not traverse the same empty range twice"
    assert out.reports == []


class _FakeAirtag:
    """Only what getLastReports touches after the fetch."""

    def to_json(self):
        return {"aligned": True}


class _FakeRequest:
    def __init__(self, beacon_id):
        self._id = beacon_id

    def getBeaconId(self):
        return self._id

    def getAccessoryJson(self):
        return "{}"


class _FakeRequestList:
    """Stands in for the Java List<AccessoryRequest> the bridge is handed."""

    def __init__(self, requests):
        self._requests = requests

    def size(self):
        return len(self._requests)

    def get(self, i):
        return self._requests[i]


def _runGetLastReports(monkeypatch, fetch_result, hours_back=24):
    # The dispatch, not the library class. These tests are about what getLastReports does with
    # an accessory, not about reading one - and patching FindMy.py's own from_json used to let
    # them feed it a blob with no "type", which the real thing has always rejected.
    monkeypatch.setattr(main, "accessoryFromJson", lambda _json: _FakeAirtag())
    monkeypatch.setattr(
        main, "_fetchReportsForAccessory", lambda *args, **kwargs: fetch_result)

    return main.getLastReports(
        account=object(),
        idToAccessoryData=_FakeRequestList([_FakeRequest("beacon-1")]),
        hoursBack=hours_back)


def test_a_newly_imported_tag_keeps_a_location_older_than_the_window(monkeypatch):
    """
    The bug this fixes. A tag with no alignment record never honours the requested window -
    the probe walks backwards until it finds anything at all - so filtering its result to the
    last 24 hours threw away the only location the app had just successfully found.

    A tag that had sat in a drawer for two days came back from an import reading "no last
    location known", despite the fetch having located it.
    """
    now = datetime.now(tz=timezone.utc)
    two_days_old = FakeReport(now - timedelta(days=2))

    out = _runGetLastReports(
        monkeypatch, main.AccessoryFetch([two_days_old], bounded_to_window=False))

    assert len(out["beacon-1"]["reports"]) == 1, \
        "the latest known location must survive, however old it is"


def test_an_aligned_tag_keeps_the_older_sightings_it_paid_to_fetch(monkeypatch):
    """
    **Reports from outside the window are kept, not dropped.**

    This used to assert the opposite, on the reasoning that the window is what makes "the last
    24 hours" true. That reasoning does not survive what the window actually is: a *key* range,
    not a time range. Keys roll every fifteen minutes, so asking for the last hour asks Apple
    about a few key indices, and Apple answers with every report it holds for them - which
    routinely includes sightings from before the window opened.

    Those reports have already been searched for, downloaded and decrypted by the time the
    filter ran. Dropping them threw that work away and left a hole in stored history that the
    history screen would later pay to fetch all over again. See `getLastReports`.

    `getReportsForDateRange` does the same, for the same reason. Narrowing to the day somebody
    is looking at is a display concern and happens in `HistoryViewActivity`, which filters the
    remote answer before drawing it and reads the local half through a day-bounded query.
    """
    now = datetime.now(tz=timezone.utc)
    recent = FakeReport(now - timedelta(hours=1))
    older = FakeReport(now - timedelta(days=2))

    out = _runGetLastReports(
        monkeypatch, main.AccessoryFetch([recent, older], bounded_to_window=True))

    assert len(out["beacon-1"]["reports"]) == 2, (
        "an older sighting for the same keys costs nothing extra and fills in history"
    )


def test_keeping_older_sightings_does_not_change_the_latest_position(monkeypatch):
    """
    The property that made widening safe, pinned separately so it stays true.

    The map draws the newest report, so adding older ones to the answer must not move the pin.
    If this ever fails, widening has become visible to the user rather than being purely
    additive to stored history.
    """
    now = datetime.now(tz=timezone.utc)
    newest = FakeReport(now - timedelta(hours=1))
    older = FakeReport(now - timedelta(days=2))

    out = _runGetLastReports(
        monkeypatch, main.AccessoryFetch([older, newest], bounded_to_window=True))

    timestamps = [report["timestamp"] for report in out["beacon-1"]["reports"]]

    assert max(timestamps) == _ms(now - timedelta(hours=1)), (
        "the newest report - the one the map draws - must be unaffected"
    )


def test_unknown_width_falls_back_to_the_history_fetch():
    """If the width cannot be determined, behave exactly as before this optimisation."""
    class BrokenAccessory:
        def get_min_index(self, _dt):
            raise ValueError("nope")

        def get_max_index(self, _dt):
            raise ValueError("nope")

    now = datetime.now(tz=timezone.utc)
    history = [FakeReport(now)]
    account = FakeAccount(history=history)

    out = main._fetchReportsForAccessory(account, BrokenAccessory(), now - timedelta(hours=24), now)

    assert account.fetch_history_calls == 1
    assert out.reports == history


# --------------------------------------------------------------------------
# Key alignment records
#
# The whole point: without one, an accessory starts at index 0 from its pairing
# date and the first fetch searches the tag's entire history. With one, it starts
# where macOS last observed the key index.
# --------------------------------------------------------------------------

def _alignment_plist_bytes(days_ago: int, index: int) -> bytes:
    """A KeyAlignmentRecord in the shape macOS writes it."""
    import plistlib
    observed = (datetime.now(tz=timezone.utc) - timedelta(days=days_ago)).replace(
        tzinfo=None, microsecond=0)
    return plistlib.dumps({
        "lastIndexObservationDate": observed,
        "lastIndexObserved": index,
    })


@pytest.mark.skipif(not BEACON_PLISTS, reason="no redacted beacon fixture available")
def test_alignment_record_collapses_the_first_fetch_key_search():
    """An old tag with an alignment record must not search its whole history."""
    from findmy import FindMyAccessory

    plist_path = BEACON_PLISTS[0]
    now = datetime.now(tz=timezone.utc)
    start = now - timedelta(hours=24)

    without = FindMyAccessory.from_plist(plist_path)
    unaligned_keys = without.get_max_index(now) - without.get_min_index(start) + 1

    # Same accessory, but macOS observed its index yesterday.
    import io as _io
    with_alignment = FindMyAccessory.from_plist(
        plist_path, _io.BytesIO(_alignment_plist_bytes(days_ago=1, index=50_000)))
    aligned_keys = with_alignment.get_max_index(now) - with_alignment.get_min_index(start) + 1

    assert aligned_keys < unaligned_keys, (
        f"alignment record did not narrow the search "
        f"({aligned_keys} vs {unaligned_keys} keys)"
    )
    # Apple accepts ~290 keys per request; a day's drift either side should stay small.
    assert aligned_keys < 1000, f"expected a bounded search, got {aligned_keys} keys"


@pytest.mark.skipif(not BEACON_PLISTS, reason="no redacted beacon fixture available")
def test_convertPlistToJson_accepts_an_alignment_record():
    plist_xml = _read_plist_xml(BEACON_PLISTS[0])
    alignment_xml = _alignment_plist_bytes(days_ago=1, index=50_000).decode("utf-8")

    result = main.convertPlistToJson(plist_xml, alignment_xml)

    assert result is not None
    parsed = json.loads(result)
    assert parsed.get("type") == "accessory"


@pytest.mark.skipif(not BEACON_PLISTS, reason="no redacted beacon fixture available")
def test_convertPlistToJson_still_works_without_an_alignment_record():
    """Exports predating format 0.0.2 have none, and must keep importing."""
    plist_xml = _read_plist_xml(BEACON_PLISTS[0])
    assert main.convertPlistToJson(plist_xml) is not None
    assert main.convertPlistToJson(plist_xml, None) is not None


@pytest.mark.skipif(not BEACON_PLISTS, reason="no redacted beacon fixture available")
def test_convertPlistToJson_survives_a_corrupt_alignment_record():
    """A bad alignment record must not cost us the beacon entirely."""
    plist_xml = _read_plist_xml(BEACON_PLISTS[0])
    result = main.convertPlistToJson(plist_xml, "this is not a plist")
    # from_plist raises on a malformed alignment plist, so we return None and the caller
    # retries later rather than storing something wrong.
    assert result is None


# --------------------------------------------------------------------------
# assertAnisetteIsSupported
#
# Remote is the only provider that works on Android; local needs the unicorn CPU
# emulator, which Chaquopy cannot build. Catching it here means a clear message
# rather than a NotImplementedError from inside the stub package.
# --------------------------------------------------------------------------

def _account_blob(anisette):
    return json.dumps({"type": "account", "anisette": anisette})


def test_remote_anisette_is_supported():
    blob = _account_blob({"type": "aniRemote", "url": "https://ani.example.com"})
    assert main.assertAnisetteIsSupported(blob) is None


def test_local_anisette_is_rejected_with_a_readable_reason():
    reason = main.assertAnisetteIsSupported(_account_blob({"type": "aniLocal"}))
    assert reason is not None
    assert "aniLocal" in reason
    assert "remote" in reason.lower()


def test_missing_anisette_config_is_rejected():
    assert main.assertAnisetteIsSupported(json.dumps({"type": "account"})) is not None


def test_unreadable_account_blob_is_rejected():
    assert main.assertAnisetteIsSupported("not json") is not None


def test_getAccount_refuses_local_anisette():
    """The guard must actually be wired into the restore path."""
    assert main.getAccount(_account_blob({"type": "aniLocal"})) is None


# --------------------------------------------------------------------------
# A session Apple has stopped accepting
#
# It deserializes perfectly and is completely unusable: every fetch fails its state
# check before a request is made. Returning it as a success is how the app came up
# showing stale pins and spinners that stopped, with the reason only in the log.
# Issue #43.
# --------------------------------------------------------------------------

def _storedAccount(loggedIn: bool) -> str:
    from findmy.reports import AppleAccount, RemoteAnisetteProvider
    from findmy.reports.state import LoginState

    account = AppleAccount(RemoteAnisetteProvider("https://ani.example.com"))
    stored = account.to_json()

    # A fresh account is LOGGED_OUT, which is exactly the shape an invalidated session
    # restores to. The logged-in case is the same blob with the one field moved, so the
    # two tests differ in nothing else.
    stored["login"]["state"] = (
        LoginState.LOGGED_IN.value if loggedIn else LoginState.LOGGED_OUT.value
    )
    return json.dumps(stored)


def test_a_session_apple_no_longer_accepts_is_a_failed_restore():
    assert main.getAccount(_storedAccount(loggedIn=False)) is None


def test_a_session_that_still_works_restores_normally():
    """
    The other half, and the one that matters more.

    Reporting a working session as failed would sign people out for no reason - a far worse
    bug than the one above, and the obvious way to get this wrong.
    """
    assert main.getAccount(_storedAccount(loggedIn=True)) is not None


# --------------------------------------------------------------------------
# Guard against the test environment drifting from what the app ships
# --------------------------------------------------------------------------

# The four files spell the same pin four ways - `...FindMy.py@<sha>`, `?rev=<sha>#<sha>`, and
# TOML's `{ git = "...FindMy.py", rev = "<sha>" }` - so this matches a full sha anywhere on a
# line that names the repository, rather than one exact syntax. Deliberately not "any 40-hex
# string in the file": uv.lock is full of other packages' revisions.
_FINDMY_SHA = re.compile(r"FindMy\.py[^\n]{0,80}?([0-9a-f]{40})")


def test_the_whole_repository_pins_one_findmy():
    """
    One repository, one FindMy.py - in the app build, the bridge tests, and the exporter.

    **Because `opentagviewer_export` is shared code that runs under both.** Chaquopy packages it
    into the APK, where it executes against the pin in `app/build.gradle.kts`, and the desktop
    exporter runs the same files from source against `python/uv.lock`. Two commits of one library
    behind one package is a bug waiting for the first API difference, and it would break exactly
    one of the two consumers.

    The exporter tracked the *branch*, so this drifted by construction: every `uv lock` picked up
    whatever had been pushed since. A build was still reproducible - the lockfile records what it
    resolved - but "which FindMy.py does this repository use" had two answers.

    Three files, one sha. Moving it means moving all three.
    """
    root = Path(__file__).resolve().parents[3].parent

    sources = {
        "app/build.gradle.kts": root / "app" / "build.gradle.kts",
        "app/src/test/python/requirements.txt": Path(__file__).resolve().parent
        / "requirements.txt",
        "python/pyproject.toml": root / "python" / "pyproject.toml",
        "python/uv.lock": root / "python" / "uv.lock",
    }

    found = {}
    for name, path in sources.items():
        assert path.is_file(), f"{name} is missing - this test no longer checks what it thinks"
        shas = set(_FINDMY_SHA.findall(path.read_text(encoding="utf-8")))
        assert shas, f"{name} does not pin FindMy.py to a commit"
        assert len(shas) == 1, f"{name} names more than one FindMy.py commit: {sorted(shas)}"
        found[name] = shas.pop()

    assert len(set(found.values())) == 1, (
        "the repository pins more than one FindMy.py:\n"
        + "\n".join(f"  {name}: {sha}" for name, sha in sorted(found.items()))
    )


def test_pinned_versions_match_the_app_build():
    """
    These tests only mean anything if they run against the same library the APK ships.

    **Every install, not just the `name==version` ones.** This used to match only that
    shape, so the line installing FindMy from a git branch was invisible to it - and
    requirements.txt said `FindMy==0.9.8` from PyPI while the app built the fork, for as
    long as both were true. That is not a version skew, it is a different library: the
    fork's Anisette providers take `serial=` and PyPI's do not, so a bridge test could
    pass on code the app is unable to run.
    """
    import re

    gradle = (Path(__file__).resolve().parents[3] / "build.gradle.kts").read_text(encoding="utf-8")
    requirements = (Path(__file__).resolve().parent / "requirements.txt").read_text(encoding="utf-8")

    # Comments first: the block above these installs explains what to put here using an
    # `install("FindMy==<x>")` of its own, and a scan that cannot tell code from prose
    # fails on the documentation telling you how to fix it.
    #
    # Whole comment lines only. Splitting each line on "//" also splits `https://`, which
    # silently truncates the one install this test exists to check - the failure being
    # that everything passes. That is the same blindness as the bug being fixed here, so
    # it is worth the two extra characters of care.
    code = "\n".join(
        line for line in gradle.splitlines() if not line.lstrip().startswith("//")
    )

    # Every string literal handed to install(). The unicorn stub is passed as a file path
    # rather than a literal, so it is not caught here and does not need to be.
    installed = re.findall(r'install\("([^"]+)"\)', code)

    assert installed, "could not find any pinned pip installs in build.gradle.kts"

    required_lines = [
        line.strip() for line in requirements.splitlines()
        if line.strip() and not line.startswith("#")
    ]

    for spec in installed:
        if spec.startswith("git+"):
            # Compared whole, including the ref: a bare URL, or one at a branch, means the
            # two sides can silently diverge again the moment somebody pushes to it.
            assert "@" in spec.rsplit("/", 1)[-1], (
                f"{spec} installs from git without pinning a commit - two builds of this"
                " repo would ship different Python"
            )
            assert spec in required_lines, (
                f"build.gradle.kts installs {spec}, which requirements.txt does not"
            )
        else:
            package, _, version = spec.partition("==")
            assert version, f"{spec} in build.gradle.kts is not pinned to a version"
            assert f"{package}=={version}" in required_lines, (
                f"{package} is {version} in build.gradle.kts but not in requirements.txt"
            )


# --------------------------------------------------------------------------
# Describing a failed sign-in
#
# The screen showed "Login failed:" and nothing after the colon, because the failure
# people actually hit is a connection timeout and `str(TimeoutError())` is "".
# --------------------------------------------------------------------------

class TestDescribingAFailedLogin:
    def test_an_exception_with_no_message_still_says_something(self):
        """The bug exactly: several asyncio errors carry no message at all."""
        assert main.describeLoginFailure(TimeoutError()) == "TimeoutError"
        assert main.describeLoginFailure(asyncio.CancelledError()) == "CancelledError"

    def test_a_message_is_kept_and_named(self):
        described = main.describeLoginFailure(ValueError("that password is wrong"))

        assert "that password is wrong" in described
        assert "ValueError" in described

    @pytest.mark.parametrize("blank", ["", "   ", "\n"])
    def test_a_whitespace_only_message_counts_as_none(self, blank):
        assert main.describeLoginFailure(RuntimeError(blank)) == "RuntimeError"

    def test_nothing_ever_describes_itself_as_empty(self):
        for error in (TimeoutError(), asyncio.CancelledError(), OSError(), Exception()):
            assert main.describeLoginFailure(error).strip()


class TestClassifyingAFailedLogin:
    @pytest.mark.parametrize("error", [
        TimeoutError(),
        asyncio.TimeoutError(),
        asyncio.CancelledError(),
        ConnectionRefusedError(),
        OSError("network is unreachable"),
    ])
    def test_not_reaching_apple_is_a_network_failure(self, error):
        assert main.classifyLoginFailure(error) == main.REASON_NETWORK

    def test_anything_else_is_left_unclassified(self):
        """
        Deliberately not guessed at. Telling somebody to check their connection when their
        password was wrong sends them to fix the wrong thing.
        """
        assert main.classifyLoginFailure(ValueError("bad password")) == main.REASON_UNKNOWN

    def test_a_library_error_is_recognised_by_its_module(self):
        class ClientConnectorError(Exception):
            pass

        ClientConnectorError.__module__ = "aiohttp.client_exceptions"

        assert main.classifyLoginFailure(ClientConnectorError()) == main.REASON_NETWORK


# --------------------------------------------------------------------------
# The two flags that pace the silent-tag backoff.
#
# Java reads `wideSearch` and `exhaustedWideSearch` off each per-beacon dict to
# decide whether a tag is going quiet. A missing key reads as False, so leaving
# them off a code path does not fail - it silently turns the backoff off, and
# every empty answer counts as a healthy one. That is exactly what happened:
# they were emitted from `getReports` only, and Java calls `getLastReports`.
# --------------------------------------------------------------------------

class _FakeAirtagOfWidth:
    """An accessory whose key-index window is a chosen width."""

    def __init__(self, width, narrows_to=None):
        self._width = width
        self._narrows_to = narrows_to
        self._fetched = False

    def get_min_index(self, _dt):
        return 0

    def get_max_index(self, _dt):
        if self._fetched and self._narrows_to is not None:
            return self._narrows_to
        return self._width

    def markFetched(self):
        self._fetched = True

    def to_json(self):
        return {"aligned": True}


def _runGetReports(monkeypatch, reports, start, end):
    """The ranged variant, which the history screen calls one day at a time."""
    monkeypatch.setattr(main, "accessoryFromJson", lambda _json: _FakeAirtag())
    # The ranged variant has its own fetch helper, and it answers with a plain list rather
    # than an AccessoryFetch - there is no window-versus-probe distinction to report here.
    monkeypatch.setattr(main, "_fetchReportsInRange", lambda *args, **kwargs: reports)

    return main.getReports(
        account=object(),
        idToAccessoryData=_FakeRequestList([_FakeRequest("beacon-1")]),
        unixStartMs=_ms(start),
        unixEndMs=_ms(end))


def test_the_ranged_fetch_keeps_sightings_from_either_side_of_the_day(monkeypatch):
    """
    **Anything fetched is stored, whichever fetch found it.**

    The history screen asks for one day, but the search underneath is over key indices, so
    Apple hands back sightings either side of it. They cost the same to find and decrypt as the
    in-range ones, and dropping them meant the next day the user stepped to paid to fetch them
    again.

    Narrowing to the day on screen happens in `HistoryViewActivity`, which filters this answer
    before drawing it - so widening here fills the cache without changing what is shown.
    """
    day_start = datetime(2026, 3, 14, tzinfo=timezone.utc)
    day_end = day_start + timedelta(days=1)

    the_day_before = FakeReport(day_start - timedelta(hours=3))
    during_the_day = FakeReport(day_start + timedelta(hours=9))
    the_day_after = FakeReport(day_end + timedelta(hours=2))

    out = _runGetReports(
        monkeypatch, [the_day_before, during_the_day, the_day_after], day_start, day_end)

    assert len(out["beacon-1"]["reports"]) == 3, (
        "reports either side of the requested day are still worth storing"
    )


def _runGetLastReportsWith(monkeypatch, airtag, reports, hours_back=24):
    monkeypatch.setattr(main, "accessoryFromJson", lambda _json: airtag)

    def fetch(_account, accessory, _start, _end):
        if hasattr(accessory, "markFetched"):
            accessory.markFetched()
        return main.AccessoryFetch(reports=reports, bounded_to_window=True)

    monkeypatch.setattr(main, "_fetchReportsForAccessory", fetch)

    return main.getLastReports(
        account=object(),
        idToAccessoryData=_FakeRequestList([_FakeRequest("beacon-1")]),
        hoursBack=hours_back)["beacon-1"]


def test_getlastreports_reports_whether_the_search_was_expensive(monkeypatch):
    """
    The regression. These keys were only ever written by `getReports`, which Java never calls,
    so the live path reported nothing and every fruitless scan looked like a successful one.
    """
    held = _runGetLastReportsWith(
        monkeypatch, _FakeAirtagOfWidth(50_000), reports=[])

    assert "wideSearch" in held, "Java cannot pace the backoff without this key"
    assert "exhaustedWideSearch" in held
    assert held["wideSearch"] is True


def test_an_aligned_tag_over_a_day_is_never_an_expensive_search(monkeypatch):
    """
    **The sharp edge, and the reason this is a test rather than a comment.**

    `wideSearch` is derived from the width of the key-index range the fetch would search - not
    from whether the accessory has an alignment record. For an *aligned* tag those are the same
    question only because the app asks for a short window: keys rotate every 15 minutes, so 24
    hours is ~96 indices against a threshold of 2000.

    That leaves about twenty times' headroom, and it is entirely accidental. Raising
    RefreshPolicy's cap past ~21 days would make every healthy tag's ordinary refresh look like
    an expensive search, and they would all start accruing strikes and being asked less often -
    the exact bug @parawanderer spotted in the database, reintroduced by a change nowhere near
    this file.
    """
    indices_per_day = 4 * 24  # a key every 15 minutes

    held = _runGetLastReportsWith(
        monkeypatch, _FakeAirtagOfWidth(indices_per_day), reports=[])

    assert held["wideSearch"] is False, (
        "a day of an aligned tag's keys must not count as an expensive search; "
        f"{indices_per_day} indices against a threshold of "
        f"{main._ALIGNMENT_PROBE_THRESHOLD_INDICES}")

    assert main._ALIGNMENT_PROBE_THRESHOLD_INDICES > indices_per_day * 7, (
        "the threshold no longer covers even a week-long window, so ordinary refreshes of "
        "healthy tags will be counted against them")


def test_a_tag_that_answers_is_not_penalised_however_wide_the_search_was(monkeypatch):
    """Finding something settles it. Java resets everything on a non-empty answer."""
    found = FakeReport(datetime.now(timezone.utc))

    held = _runGetLastReportsWith(
        monkeypatch, _FakeAirtagOfWidth(50_000, narrows_to=10), reports=[found])

    assert held["exhaustedWideSearch"] is False
    assert held["reports"], "the report itself must still come back"


def test_only_months_of_empty_history_counts_as_exhausted(monkeypatch):
    """
    Wide is not the same as dead.

    A tag with no alignment record is wide on its first fetch and is perfectly healthy - it just
    has not been located yet. Giving up needs the far larger _DEAD_TAG_WIDTH_INDICES and a
    search that failed to narrow anything.
    """
    merely_wide = _runGetLastReportsWith(
        monkeypatch, _FakeAirtagOfWidth(5_000), reports=[])

    assert merely_wide["wideSearch"] is True
    assert merely_wide["exhaustedWideSearch"] is False, (
        "an unaligned tag must not be given up on for being unaligned")

    truly_dead = _runGetLastReportsWith(
        monkeypatch, _FakeAirtagOfWidth(50_000), reports=[])

    assert truly_dead["exhaustedWideSearch"] is True


def test_a_search_that_narrowed_is_not_exhausted_even_with_no_reports(monkeypatch):
    """
    Narrowing means something was found to align against, so the tag is broadcasting.

    Without this the probe's own success would be read as failure whenever its report fell
    outside the requested window.
    """
    held = _runGetLastReportsWith(
        monkeypatch, _FakeAirtagOfWidth(50_000, narrows_to=10), reports=[])

    assert held["exhaustedWideSearch"] is False
