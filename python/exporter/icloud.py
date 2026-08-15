"""
Reading accessories out of an iCloud account, ready to export.

The half of the exporter that talks to Apple. Everything user-facing is a callback, so the CLI
and the windowed wizard drive the same flow with their own prompts rather than each implementing
it - and nothing here prints or reads from a terminal.

**Read-only.** Signing in, recovering keys and fetching records leave nothing behind on the
account: no escrow record is created and no peer joins the trust circle. FindMy.py has a second
passcode flow that *does* write - it enrols a new recovery record - and this never calls it.
Exporting is a read.

**Nothing is persisted.** FindMy.py's own examples save the account to `account.json`, password
included, and that is not copied here. The Apple ID password and the device passcode are used
inside one call each and dropped. A run starts from nothing, every time.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Awaitable, Callable, Sequence

from findmy import (
    AsyncAppleAccount,
    LocalAnisetteProvider,
    LoginState,
    RemoteAnisetteProvider,
    SmsSecondFactorMethod,
    TrustedDeviceSecondFactorMethod,
)
from findmy.accessory import _extract_serial_from_stable_id  # noqa: PLC2701 - see _candidate
from findmy.cloudkit.beacons import (
    AsyncBeaconStore,
    DecryptedRecord,
    can_be_located,
    decrypt_records,
    group_records,
    to_beacon_naming_plist,
    to_key_alignment_plist,
    to_owned_beacon_plist,
)
from findmy.cloudkit.client import AsyncCloudKitClient
from findmy.icloud import AsyncFindMyClient
from findmy.keychain.session import AsyncKeychainSession

from exporter.identity import CLOUDKIT_DEVICE_NAME, EXPORTER_SERIAL
from opentagviewer_export import AccessoryExport
from opentagviewer_export.hardware import identify

logger = logging.getLogger(__name__)

# How far back a locate would search, for pricing an accessory before asking about it. A week is
# what `fetch_location` covers by default.
_LOCATE_WINDOW = timedelta(days=7)


class ExportSourceError(Exception):
    """Raised when the account cannot produce what an export needs."""


@dataclass(frozen=True)
class Candidate:
    """
    One accessory that could be exported, with what a person needs to choose about it.

    :param beacon_id: Its identifier, which is also where its files go in the bundle.
    :param name: What the owner called it, or None if it has no naming record.
    :param emoji: The emoji they chose, if any.
    :param has_alignment: Whether a key alignment record came with it. Without one, whoever
        imports this bundle searches the tag's whole key history on first locate.
    :param owned_beacon: Its record, rendered as the plist the bundle carries.
    """

    beacon_id: str
    name: str | None
    emoji: str | None
    has_alignment: bool
    owned_beacon: dict[str, Any]
    naming_record: dict[str, Any] | None
    key_alignment_record: dict[str, Any] | None
    hardware: str | None = None
    serial_number: str | None = None
    paired_at: datetime | None = None

    @property
    def label(self) -> str:
        """How to show it in a list, when it may have no name of its own."""
        shown = self.name or "unnamed"
        return f"{self.emoji} {shown}" if self.emoji else shown

    @property
    def details(self) -> str:
        """
        Everything else known about it, for telling one from another.

        **This is what an accessory with no name has instead of one.** "unnamed" three times over
        is not a list anybody can choose from, and all of this is on the record already: what kind
        of thing it is, the serial Find My shows for it, and when it was paired - which is often
        the one a person recognises, because they remember buying it.
        """
        parts = [
            self.hardware,
            f"serial {self.serial_number}" if self.serial_number else None,
            f"paired {self.paired_at:%Y-%m-%d}" if self.paired_at else None,
        ]

        return ", ".join(part for part in parts if part)


@dataclass(frozen=True)
class Skipped:
    """An accessory that was found and will not be exported, and why."""

    beacon_id: str
    reason: str


@dataclass(frozen=True)
class Fetched:
    """What one account holds: what can be exported, and what was set aside."""

    candidates: list[Candidate]
    skipped: list[Skipped]


def make_account(anisette_url: str | None = None, libs_path: str | None = None) -> AsyncAppleAccount:
    """
    Build an account that presents this exporter's identity.

    :param anisette_url: A remote Anisette server, or None to run Anisette locally. Local is the
        default because it keeps the login between this machine and Apple; a server sees the
        headers, and a session is bound to whichever established it.
    :param libs_path: Where to cache Apple's ADI libraries, so a later run does not fetch them
        again. They are downloaded on first use when this is None.
    """
    provider = (
        LocalAnisetteProvider(libs_path=libs_path, serial=EXPORTER_SERIAL)
        if anisette_url is None
        else RemoteAnisetteProvider(anisette_url, serial=EXPORTER_SERIAL)
    )

    return AsyncAppleAccount(provider)


async def log_in(
    account: AsyncAppleAccount,
    email: str,
    password: str,
    *,
    choose_second_factor: Callable[[Sequence[str]], Awaitable[int]],
    get_code: Callable[[], Awaitable[str]],
) -> None:
    """
    Sign in, dealing with two-factor authentication if the account asks for it.

    :param choose_second_factor: Given the methods as text, returns which one to use. Awaited,
        because asking is drawn on a terminal and drawing happens inside this same event loop.
    :param get_code: Returns the code the user received. Awaited, as above.
    :raises ExportSourceError: If signing in does not end signed in.
    """
    state = await account.login(email, password)

    if state == LoginState.REQUIRE_2FA:
        methods = await account.get_2fa_methods()
        if not methods:
            raise ExportSourceError(
                "Apple asked for a verification code but offered no way to send one.",
            )

        chosen = methods[await choose_second_factor([_describe_factor(m) for m in methods])]
        await chosen.request()
        state = await chosen.submit(await get_code())

    if state != LoginState.LOGGED_IN:
        raise ExportSourceError(f"Signing in ended at {state} rather than signed in.")


def _describe_factor(method: object) -> str:
    """Name one second-factor method for a person choosing between them."""
    if isinstance(method, TrustedDeviceSecondFactorMethod):
        return "a trusted device"
    if isinstance(method, SmsSecondFactorMethod):
        return f"SMS to {method.phone_number}"
    return type(method).__name__


async def open_client(account: AsyncAppleAccount) -> AsyncFindMyClient:
    """
    Open a Find My client that reports this exporter's identity to CloudKit as well.

    Assembled by hand rather than through `AsyncFindMyClient.open`, which builds the CloudKit
    client with the library's own device name. Rule 11: every path has to send the same identity,
    and a second name here would be a second device in the user's list.
    """
    session = await AsyncKeychainSession.open(account)
    try:
        store = AsyncBeaconStore(
            account,
            client=AsyncCloudKitClient(
                account,
                device_name=CLOUDKIT_DEVICE_NAME,
                device_serial=EXPORTER_SERIAL,
            ),
        )
    except Exception:
        await session.close()
        raise

    return AsyncFindMyClient(account, session, store)


async def fetch(client: AsyncFindMyClient) -> Fetched:
    """
    Fetch and decrypt the account's accessories, as the records a bundle carries.

    Not `client.accessories()`, which assembles `FindMyAccessory` objects: those are a derived
    view holding what key derivation consumes, and the bundle needs the records themselves -
    battery level, emoji, product and vendor ids, everything descriptive that the derived view
    drops. Requires keys, so `unlock` or `use_keys` first.
    """
    zone_keys = await client.zone_keys()
    records = await client.records()

    decrypted = decrypt_records(
        records,
        zone_keys,
        default_master_keys=await client.store.zone_record_defaults(zone_keys),
    )

    candidates: list[Candidate] = []
    skipped: list[Skipped] = []

    for group in group_records(decrypted):
        beacon = group.beacon

        if not can_be_located(beacon):
            # No private key, so it is one of the owner's own devices rather than an accessory.
            # Named rather than dropped quietly: "fewer tags than expected" and "some of those
            # were never tags" look identical from outside.
            skipped.append(Skipped(beacon.name, _why_not_locatable(beacon)))
            continue

        candidates.append(_candidate(group.beacon, group.naming, group.alignment))

    return Fetched(candidates=candidates, skipped=skipped)


def _why_not_locatable(beacon: DecryptedRecord) -> str:
    """Say what a discarded record appears to be, with the evidence rather than a verdict."""
    model = beacon.values.get("model")
    secondary = [
        name
        for name in ("sharedSecret2", "secureLocationsSharedSecret")
        if isinstance(beacon.values.get(name), bytes)
    ]

    # An accessory carries `sharedSecret2`; an iPhone, iPad or Mac carries the other. And a
    # device's model is an identifier like `iPad13,18`, where an AirTag's is empty.
    looks_like_a_device = "secureLocationsSharedSecret" in secondary or bool(model)

    return (
        "has no private key, so it cannot be located"
        + (f" - looks like one of your own devices ({model or 'no model'})" if looks_like_a_device else "")
    )


def _candidate(
    beacon: DecryptedRecord,
    naming: DecryptedRecord | None,
    alignment: DecryptedRecord | None,
) -> Candidate:
    """Render one group of records into the plists a bundle carries."""
    stable = beacon.values.get("stableIdentifier")
    stable = [stable] if isinstance(stable, str) else None
    paired_at = beacon.values.get("pairingDate")

    return Candidate(
        beacon_id=beacon.name,
        name=naming.values.get("name") if naming else None,
        emoji=naming.values.get("emoji") if naming else None,
        has_alignment=alignment is not None,
        owned_beacon=to_owned_beacon_plist(beacon),
        naming_record=to_beacon_naming_plist(naming) if naming else None,
        key_alignment_record=to_key_alignment_plist(alignment) if alignment else None,
        hardware=identify(beacon.values),
        # The library's, rather than a second implementation of the same parsing: the AirTag and
        # third-party forms are a split, but the AirPods one hex-encodes its serial, and getting
        # that subtly wrong would show somebody a serial that is not theirs.
        serial_number=_extract_serial_from_stable_id(stable),
        paired_at=paired_at if isinstance(paired_at, datetime) else None,
    )


def to_export(candidate: Candidate, *, name: str | None = None) -> AccessoryExport:
    """
    Turn a candidate into something the bundle writer takes.

    :param name: A name to give an accessory that has none. **Required for one that has no naming
        record**, and refused for one that has: the importer needs a naming record for every
        accessory, but inventing a name for a record of unknown kind manufactures a tag the user
        does not own - in a bundle whose whole purpose is to be imported and believed.
    :raises ExportSourceError: If a nameless accessory was not given a name.
    """
    naming = candidate.naming_record

    if naming is None:
        if not name or not name.strip():
            raise ExportSourceError(
                f"Accessory {candidate.beacon_id} has no name of its own, and every accessory in a"
                " bundle needs one - the importer drops any it cannot pair with a naming record."
                " Give it a name, or leave it out of the export.",
            )

        naming = _synthesised_naming_record(candidate.beacon_id, name.strip())

    return AccessoryExport(
        owned_beacon=candidate.owned_beacon,
        naming_record=naming,
        key_alignment_record=candidate.key_alignment_record,
    )


def _synthesised_naming_record(beacon_id: str, name: str) -> dict[str, Any]:
    """
    Build a naming record for an accessory that arrived without one.

    Its identifier is generated, because there is no record to take one from - and the importer
    matches entry names against a version-4 UUID specifically, so it has to be one of those.
    """
    return {
        "identifier": str(uuid.uuid4()).upper(),
        "associatedBeacon": beacon_id,
        "name": name,
        # No emoji and no roleId: this record is this program's invention, and the fields it
        # cannot know are better absent than guessed.
        "cloudKitMetadata": b"",
    }


def locate_span(candidate: Candidate, accessory: Any) -> int:
    """
    How many key indices locating this accessory would search, for pricing it before asking.

    An accessory carrying an alignment record spans a few hundred; one without spans its whole
    history, because the lower bound collapses to its pairing date. That is the difference between
    a second and several minutes, and it is worth showing at the decision rather than explaining
    afterwards.
    """
    now = datetime.now(tz=timezone.utc)
    return accessory.get_max_index(now) - accessory.get_min_index(now - _LOCATE_WINDOW)
