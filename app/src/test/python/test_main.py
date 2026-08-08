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
    def __init__(self, width):
        self._width = width
        self.narrowed = False

    def get_min_index(self, _dt):
        return 0

    def get_max_index(self, _dt):
        return 0 if self.narrowed else self._width


class FakeAccount:
    def __init__(self, on_fetch=None):
        self.fetch_location_calls = 0
        self._on_fetch = on_fetch

    def fetch_location(self, accessory):
        self.fetch_location_calls += 1
        if self._on_fetch:
            self._on_fetch(accessory)
        return None


def test_probe_runs_when_the_key_window_is_wide():
    """A two-year-old tag is ~70k indices - exactly the case worth probing."""
    accessory = FakeAccessory(width=70_000)
    account = FakeAccount(on_fetch=lambda acc: setattr(acc, "narrowed", True))
    now = datetime.now(tz=timezone.utc)

    probed = main._narrowAlignmentIfNeeded(account, accessory, now - timedelta(hours=24), now)

    assert probed is True
    assert account.fetch_location_calls == 1


def test_probe_skipped_when_the_window_is_already_narrow():
    """An aligned accessory must not pay an extra request on every fetch."""
    accessory = FakeAccessory(width=96)  # ~1 day for an AirTag
    account = FakeAccount()
    now = datetime.now(tz=timezone.utc)

    probed = main._narrowAlignmentIfNeeded(account, accessory, now - timedelta(hours=24), now)

    assert probed is False
    assert account.fetch_location_calls == 0


def test_probe_failure_is_not_fatal():
    """A failed probe must fall through to the normal fetch, not abort it."""
    class ExplodingAccount:
        def fetch_location(self, accessory):
            raise RuntimeError("anisette server unreachable")

    accessory = FakeAccessory(width=70_000)
    now = datetime.now(tz=timezone.utc)

    assert main._narrowAlignmentIfNeeded(ExplodingAccount(), accessory, now - timedelta(hours=24), now) is False


# --------------------------------------------------------------------------
# assertAnisetteIsSupported
#
# Remote is the only provider that works on Android; local needs the unicorn CPU
# emulator, which Chaquopy cannot build. Catching that here means a clear message
# instead of a NotImplementedError from inside the stub package.
# --------------------------------------------------------------------------

def _account_blob(anisette):
    return json.dumps({"type": "account", "anisette": anisette})


def test_remote_anisette_is_supported():
    blob = _account_blob({"type": "aniRemote", "url": "https://ani.example.com"})
    assert main.assertAnisetteIsSupported(blob) is None


def test_local_anisette_is_rejected_with_a_readable_reason():
    blob = _account_blob({"type": "aniLocal"})
    reason = main.assertAnisetteIsSupported(blob)

    assert reason is not None
    assert "aniLocal" in reason
    assert "remote" in reason.lower()


def test_missing_anisette_config_is_rejected():
    reason = main.assertAnisetteIsSupported(json.dumps({"type": "account"}))
    assert reason is not None


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


def test_probe_survives_an_accessory_that_cannot_report_indices():
    class BrokenAccessory:
        def get_min_index(self, _dt):
            raise ValueError("nope")

        def get_max_index(self, _dt):
            raise ValueError("nope")

    account = FakeAccount()
    now = datetime.now(tz=timezone.utc)

    assert main._narrowAlignmentIfNeeded(account, BrokenAccessory(), now - timedelta(hours=24), now) is False
    assert account.fetch_location_calls == 0
