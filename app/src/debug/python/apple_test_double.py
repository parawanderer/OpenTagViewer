"""
A signed-in Apple account that is not Apple, installed on the Python side of the bridge.

**The other half of `icloud_test_double`, and it exists for the same reason.** Everything this
app does against Apple happens behind Python, so a fake on the Java side of the bridge skips the
bridge - and two bugs shipped through exactly that gap in the iCloud path while the whole suite
stayed green.

**What this one unblocks is the map.** ``MapsActivity`` draws its stored locations from a stream
that is *zipped* with ``PythonAuthService.restoreAccount``, so a session that will not restore
disposes the drawing side before it emits. That is correct behaviour - somebody whose session is
unusable is sent to sign in - but it means no test could reach the drawing at all, and the app's
main screen had one thin "it starts" assertion. A restorable session is the missing piece.

**Restoring needs no network, which is what makes this small.** ``getAccount`` calls FindMy.py's
``AppleAccount.from_json`` and nothing else; the network only appears later, at fetch time. So
what is replaced here is the deserialisation and the fetch, and everything between them - the
service, the request building, the storage, the drawing - is the shipping code.

**Debug source set only.** Chaquopy compiles ``src/<variant>/python`` alongside
``src/main/python``, so this is in the debug APK the instrumented tests run against and in no
release build. Nothing in ``main`` imports it; it is installed from a test, at runtime.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

import main

_originals: dict[str, Any] = {}


class _FakeReport:
    """
    One location report, in the attributes :func:`main._serializeReports` reads off a real one.

    Sortable, because that function sorts them - a real ``LocationReport`` orders by timestamp
    and anything standing in for one has to as well.
    """

    def __init__(
            self,
            timestamp: datetime,
            latitude: float,
            longitude: float,
            confidence: int = 2,
            horizontal_accuracy: int = 83,
            status: int = 144) -> None:
        self.timestamp = timestamp
        self.latitude = latitude
        self.longitude = longitude
        self.confidence = confidence
        self.horizontal_accuracy = horizontal_accuracy
        self.status = status
        self.description = "Wi-Fi"

    def __lt__(self, other: Any) -> bool:
        return self.timestamp < other.timestamp


class _FakeAccessory:
    """
    A tag whose key window is already narrow, standing in for a `FindMyAccessory`.

    Narrow on purpose: a wide one sends the fetch down the alignment probe, which is a different
    code path with its own tests. What is wanted here is the ordinary case.
    """

    def __init__(self, beaconId: str) -> None:
        self._id = beaconId

    def get_min_index(self, _dt: Any) -> int:
        return 0

    def get_max_index(self, _dt: Any) -> int:
        return 96  # a day of keys, at one every fifteen minutes

    def to_json(self) -> dict[str, Any]:
        return {"type": "accessory", "id": self._id}


class _FakeAccount:
    """The account object `getAccount` hands back, with the sockets left out."""

    def __init__(self) -> None:
        self.fetchedFor: list[str] = []

    # `main` only ever reads this to decide whether a restore succeeded, and only inside the
    # real getAccount - which is replaced. Present so anything else that looks is not surprised.
    @property
    def login_state(self) -> Any:
        from findmy.reports import LoginState

        return LoginState.LOGGED_IN

    def fetch_location(self, accessory: Any) -> list[_FakeReport]:
        self.fetchedFor.append(getattr(accessory, "_id", "?"))
        return list(_reports)

    def fetch_location_history(self, accessory: Any) -> list[_FakeReport]:
        self.fetchedFor.append(getattr(accessory, "_id", "?"))
        return list(_reports)


#: What every fetch returns. Replaced by :func:`install`.
_reports: list[_FakeReport] = []

#: The account the last :func:`install` handed out, so a test can ask what was fetched.
theAccount: _FakeAccount | None = None


def aReportFrom(hoursAgo: float, latitude: float, longitude: float) -> _FakeReport:
    """A report at a time relative to now, which is what the app's windows are measured from."""
    return _FakeReport(
        timestamp=datetime.now(timezone.utc) - timedelta(hours=hoursAgo),
        latitude=latitude,
        longitude=longitude)


def install(latitude: float = 52.370216, longitude: float = 4.895168,
            hoursAgo: float = 2.0) -> None:
    """
    Make any stored session restore, and make every fetch return one report.

    Idempotent: installing twice keeps the first set of originals, so an uninstall still puts the
    real functions back rather than a fake one.
    """
    global theAccount, _reports

    theAccount = _FakeAccount()
    _reports = [aReportFrom(hoursAgo, latitude, longitude)]

    if not _originals:
        _originals["getAccount"] = main.getAccount
        _originals["accessoryFromJson"] = main.accessoryFromJson

    def getAccount(serializedAccountData: str, anisetteServerUrl: Any = None,
                   localAnisette: Any = None) -> Any:
        # Whatever was stored, however unparseable. The point is to get past the restore, not
        # to test it - assertAnisetteIsSupported and from_json have their own tests.
        return theAccount

    def accessoryFromJson(accessoryJson: str) -> Any:
        # The beacon id is not in the JSON the app stores, so this cannot recover it. The tests
        # that care about which tag was fetched read the request list, not this.
        return _FakeAccessory(accessoryJson[:24])

    main.getAccount = getAccount
    main.accessoryFromJson = accessoryFromJson


def installWithNothingToReport() -> None:
    """The same, but every fetch comes back empty - a tag nobody has walked past."""
    global _reports

    install()
    _reports = []


def uninstall() -> None:
    """Put the real functions back. Safe to call without a matching install."""
    global theAccount, _reports

    for name, original in _originals.items():
        setattr(main, name, original)

    _originals.clear()
    theAccount = None
    _reports = []


def howManyFetches() -> int:
    """How many accessories were fetched for, for a test to assert on from Java."""
    return 0 if theAccount is None else len(theAccount.fetchedFor)
