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

import json
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
    assert out == [report]


def test_narrow_window_uses_the_normal_history_fetch():
    """Once aligned, fetches go back to normal - no extra round trip per call."""
    now = datetime.now(tz=timezone.utc)
    history = [FakeReport(now), FakeReport(now - timedelta(hours=2))]
    accessory = FakeAccessory(width=96)  # about a day for an AirTag
    account = FakeAccount(history=history)

    out = main._fetchReportsForAccessory(account, accessory, now - timedelta(hours=24), now)

    assert account.fetch_history_calls == 1
    assert account.fetch_location_calls == 0
    assert out == history


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
    assert out == []


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
    assert out == history


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
# Guard against the test environment drifting from what the app ships
# --------------------------------------------------------------------------

def test_pinned_versions_match_the_app_build():
    """
    These tests only mean anything if they run against the same library version
    Chaquopy installs into the APK. Keeps requirements.txt honest.
    """
    import re

    gradle = (Path(__file__).resolve().parents[3] / "build.gradle.kts").read_text(encoding="utf-8")
    requirements = (Path(__file__).resolve().parent / "requirements.txt").read_text(encoding="utf-8")

    installed = dict(re.findall(r'install\("([A-Za-z_][\w.-]*)==([\d.]+)"\)', gradle))
    required = dict(re.findall(r'^([A-Za-z_][\w.-]*)==([\d.]+)', requirements, re.MULTILINE))

    assert installed, "could not find any pinned pip installs in build.gradle.kts"

    for package, version in installed.items():
        assert package in required, (
            f"{package} is installed by Chaquopy but not pinned in requirements.txt"
        )
        assert required[package] == version, (
            f"{package} is {version} in build.gradle.kts but {required[package]} in requirements.txt"
        )
