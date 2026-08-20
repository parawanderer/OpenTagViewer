"""
An Apple account that is not Apple, installed on the Python side of the bridge.

**Why this exists rather than another Java fake.** Every screen test replaces
:class:`ICloudService` with ``FakeICloudService``, which is right for testing screens and means
the real implementation - the one that crosses into Python, converts objects, parses JSON and maps
reason strings - never runs. A bug lived in exactly that gap: ``openFor`` checked its result with
``made.toJava(Object.class)``, which throws for any Python object, so the entire iCloud flow was
dead on every device while the suite stayed green. Everything external in this app is behind
Python, so a fake on the Java side of the bridge skips the bridge.

**Debug source set only.** Chaquopy compiles ``src/<variant>/python`` alongside
``src/main/python``, so this is in the debug APK the instrumented tests run against and in no
release build. Nothing in ``main`` imports it; it is installed from a test, at runtime.

**What it replaces is the network, and nothing else.** The two module functions patched here are
where ``exporter.icloud`` talks to Apple. Above them, everything is the shipping code:
``ICloudSession`` drives the flow, builds the JSON, decides what each failure means - and the
``Candidate`` objects handed back are the real dataclasses, so the plists Java receives are
rendered by the same code that renders a real account's.
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from types import SimpleNamespace
from typing import Any

from exporter import icloud

_originals: dict[str, Any] = {}

#: The device whose passcode unlocks the keychain, as the escrow record describes it.
A_SERIAL = "F2LX9Q"

#: What the fake refuses, so the rejected-passcode path can be driven for real.
THE_RIGHT_PASSCODE = "123456"


def _keyMaterial(length: int) -> bytes:
    """Bytes of the right shape. Not a key, and not pretending to be one."""
    return bytes(range(1, length + 1))


def anOwnedBeaconRecord(name: str) -> dict[str, Any]:
    """
    An accessory's record, in the shape :func:`to_owned_beacon_plist` produces.

    Rendered to XML by the bridge and then parsed by the app, so the field names here are the
    ones the Android side reads - this is the document both sides have to agree about.
    """
    return {
        "batteryLevel": 1,
        "identifier": name,
        "model": "",
        "pairingDate": datetime(2025, 2, 27, 20, 3, 32, tzinfo=timezone.utc),
        "privateKey": {"key": {"data": _keyMaterial(28)}},
        "productId": 21760,
        "secondarySharedSecret": {"key": {"data": _keyMaterial(32)}},
        "sharedSecret": {"key": {"data": _keyMaterial(32)}},
        "stableIdentifier": ["2001~#001234a12345aaac~#A02BCDEFG1AB"],
        "systemVersion": "2.0.73",
        "vendorId": 76,
    }


def aNamingRecord(beaconId: str, name: str, emoji: str | None) -> dict[str, Any]:
    record: dict[str, Any] = {
        "identifier": f"naming-{beaconId}",
        "associatedBeacon": beaconId,
        "name": name,
    }
    if emoji is not None:
        record["emoji"] = emoji
    return record


class _FakeEscrowRecord:
    """One recoverable device, in the attributes the bridge reads off a real record."""

    def __init__(self, serial: str, name: str, model: str, modelClass: str) -> None:
        self.serial = serial
        self.device_name = name
        self.device_model = model
        self.device_model_class = modelClass
        self.escrowed_at = datetime(2024, 3, 12, tzinfo=timezone.utc)

    def describe(self) -> str:
        return f"{self.device_name}, {self.device_model}, serial {self.serial}"


class _FakeSession:
    """The keychain session: recovering from an escrow record, and joining as a peer."""

    def __init__(self, client: _FakeClient) -> None:
        self._client = client

    async def recover(self, record: Any, passcode: str) -> Any:
        self._client.unlockedWith.append((record.serial, passcode))

        if passcode != THE_RIGHT_PASSCODE:
            # The real one raises RecoveryError, which the bridge maps to a rejected passcode.
            # Raised from here so that mapping is exercised rather than assumed.
            from findmy.keychain.recovery import RecoveryError

            raise RecoveryError("That passcode was not accepted.")

        return SimpleNamespace(peer_id=f"peer-for-{record.serial}")

    async def join(self, peer: Any, *, passcode: str, device: Any, os_version: str) -> Any:
        self._client.joinedWith = SimpleNamespace(
            peer=peer, passcode=passcode, device=device, os_version=os_version)

        return SimpleNamespace(
            peer=SimpleNamespace(
                peer_id="peer-ours", to_json=lambda: {"peer_id": "peer-ours"}),
            bottle=SimpleNamespace(entropy=bytes(72)),
            label="OpenTagViewer",
            shares=2)


class _FakeClient:
    """The Find My client, with the sockets left out and the calls recorded."""

    def __init__(self) -> None:
        self.session = _FakeSession(self)
        self.unlockedWith: list[tuple[str, str]] = []
        self.resumedAs: list[Any] = []
        self.renamedWith: list[tuple[str, dict[str, Any]]] = []
        self.joinedWith: Any = None
        self.closed = False

    async def __aenter__(self) -> _FakeClient:
        return self

    async def __aexit__(self, *_: Any) -> bool:
        self.closed = True
        return False

    async def recovery_options(self, *, refresh: bool = False) -> Any:
        return SimpleNamespace(
            recoverable=[
                _FakeEscrowRecord(A_SERIAL, "Shane’s iPhone", "iPhone15,2", "iPhone"),
                _FakeEscrowRecord("C02XK", "Work MacBook", "MacBookPro18,3", "Mac"),
            ],
            viability_is_trustworthy=True)

    async def resume(self, peer: Any, **_: Any) -> list[Any]:
        self.resumedAs.append(peer)
        return []

    async def rename(self, accessory: str, **changes: Any) -> Any:
        self.renamedWith.append((accessory, changes))
        return SimpleNamespace(identifier=accessory)


#: The client the last :func:`install` handed out, so a test can ask what reached it.
theClient: _FakeClient | None = None


def _fetched() -> Any:
    """
    What one account holds, as the real dataclasses.

    Real ones on purpose: the bridge turns these into the JSON Java parses, so using the shipping
    types means the field names, the label fallback and the plist rendering are all the ones a
    real account would go through.
    """
    return icloud.Fetched(
        candidates=[
            icloud.Candidate(
                beacon_id="a-bike-tag",
                name="Bike",
                emoji="🚲",
                has_alignment=True,
                owned_beacon=anOwnedBeaconRecord("a-bike-tag"),
                naming_record=aNamingRecord("a-bike-tag", "Bike", "🚲"),
                key_alignment_record={"primaryKeyIndex": 12, "secondaryKeyIndex": 12},
            ),
            # No naming record, which CloudKit really does hold for an accessory nobody named.
            icloud.Candidate(
                beacon_id="a-nameless-tag",
                name=None,
                emoji=None,
                has_alignment=False,
                owned_beacon=anOwnedBeaconRecord("a-nameless-tag"),
                naming_record=None,
                key_alignment_record=None,
            ),
        ],
        skipped=[icloud.Skipped("an-ipad", "it is one of your own devices")],
    )


def install() -> None:
    """
    Point ``exporter.icloud`` at the fake account, and remember what was there.

    Idempotent: installing twice keeps the first set of originals, so an uninstall still puts the
    real functions back rather than a fake one.
    """
    global theClient

    theClient = _FakeClient()

    if not _originals:
        _originals["open_client"] = icloud.open_client
        _originals["fetch"] = icloud.fetch

    async def open_client(account: Any, identity: Any = None) -> _FakeClient:
        return theClient

    async def fetch(client: Any) -> Any:
        return _fetched()

    icloud.open_client = open_client
    icloud.fetch = fetch


def uninstall() -> None:
    """Put the real functions back. Safe to call without a matching install."""
    global theClient

    for name, original in _originals.items():
        setattr(icloud, name, original)

    _originals.clear()
    theClient = None


def anAccount() -> Any:
    """
    An account object shaped like the one the app signs in with.

    ``openSession`` guards on the two private attributes FindMy.py's account carries, and a join
    reads the identity and serial off the async half - rule 11's single source of truth, which is
    why they are here rather than invented further down.
    """
    return SimpleNamespace(
        _asyncacc=SimpleNamespace(
            serial="0PENTAGVIEWR",
            identity=SimpleNamespace(
                model="iPhone17,1", os_name="iPhone OS", os_version="18.1",
                os_build="22B83", cfnetwork="1568.100.1", darwin="24.1.0")),
        _evt_loop=asyncio.new_event_loop())


def whatReachedTheAccount() -> dict[str, Any]:
    """What the fake was asked to do, for a test to assert on from Java."""
    if theClient is None:
        return {}

    return {
        "unlockedWith": [f"{serial}:{passcode}" for serial, passcode in theClient.unlockedWith],
        "resumeCount": len(theClient.resumedAs),
        "renamedWith": [f"{who}:{sorted(what.items())}" for who, what in theClient.renamedWith],
        "joined": theClient.joinedWith is not None,
        "joinedSerial": (
            getattr(theClient.joinedWith.device, "serial", None)
            if theClient.joinedWith is not None else None),
        "closed": theClient.closed,
    }
