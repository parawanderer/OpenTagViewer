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

import base64
import logging
import uuid
from io import BytesIO
from pathlib import Path
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Awaitable, Callable, Sequence

from findmy import (
    AsyncAppleAccount,
    InvalidCredentialsError,
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
from findmy.reports.anisette import BaseAnisetteProvider
from findmy.icloud import AsyncFindMyClient
from findmy.keychain.recovery import RecoveryError
from findmy.keychain.session import AsyncKeychainSession

from exporter import device
from exporter.identity import DEVICE_NAME, EXPORTER_SERIAL
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

    undecryptable: int = 0
    """
    How many records in the zone did not decrypt at all.

    **Counted separately from `skipped`, and not as a failure.** `skipped` is per accessory and
    names one: something was read, understood, and set aside for a stated reason. These were never
    read - they have no beacon id to name, because the id is inside what would not open.

    **A non-zero count is not necessarily wrong.** A zone legitimately holds records belonging to
    other parties, which this account has no key for and never should. So it is reported as a
    number and a caveat rather than as an error, and the only thing that would make it alarming is
    it being close to the number of tags somebody expected.

    It exists because it was invisible: FindMy.py counted these, logged the tally and dropped it,
    and `fetch` builds its `skipped` list by walking what came back - so anything lost here was
    absent from both lists at once. "Fewer tags than expected" and "some of those were never tags"
    look identical from outside, which is the same sentence the per-accessory list exists for.
    """

    first_miss: str | None = None
    """
    The first "no key held", in full, when there was one.

    The count alone cannot tell the two causes apart: records that genuinely belong to somebody
    else, and this client comparing keys in the wrong encoding. Those lead in opposite directions,
    and only the message says which.
    """


@dataclass(frozen=True)
class ClientIdentity:
    """
    Who a client says it is, in the two fields Apple shows the user.

    **There is more than one client now.** This module was written for the desktop exporter and
    read :mod:`exporter.identity` directly, which was right while it was the only caller. The
    Android app runs the same flow through Chaquopy and must present its own serial - two
    programs sharing one are one device to Apple, so removing either from the device list breaks
    the other. Rule 11 in AGENTS.md.

    Defaulted everywhere to the exporter's, so nothing on the desktop side changes.

    :param serial: `X-Apple-I-SRL-NO`, and the one field in the device-list row a person can
        actually read.
    :param device_name: What CloudKit is told this client is called. **Not the device-list name**,
        which needs the `postdata` announce Apple refuses without a push token - see
        :func:`remember`. Required rather than optional because `AsyncCloudKitClient` takes a
        string; a client that set nothing here would be named by the library instead, which is
        the same second-identity problem one layer down.
    """

    serial: str
    device_name: str


EXPORTER_IDENTITY = ClientIdentity(serial=EXPORTER_SERIAL, device_name=DEVICE_NAME)
"""The desktop exporter's, and the default for every entry point here."""


LOGIN_TIMEOUT_SECONDS = 30
"""
Seconds any one request may take, in place of FindMy.py's default of five.

**Five is a per-request cap, and this makes far more than one request.** Signing in is several
round trips, and what follows it - escrow recovery, then the CloudKit fetches that carry the
actual keys - is heavier than the login. A link slow enough to spend five seconds on any one of
them fails partway through with a bare timeout that names no cause.

Local ADI is the default here and generates its Anisette on this machine, so that part is not
waiting on anybody. It is Apple's own hosts that this is about - and `--anisette-url`, for anyone
who does point it at a server.

Thirty rather than a larger number because it is what the Android app settled on for the same
sign-in, and the two should not disagree about how patient this protocol needs somebody to be.
"""


def make_account(
    anisette_url: str | None = None,
    libs_path: str | None = None,
    *,
    provider: BaseAnisetteProvider | None = None,
    identity: ClientIdentity = EXPORTER_IDENTITY,
    identity_path: Path | None = None,
) -> AsyncAppleAccount:
    """
    Build an account that presents this exporter's identity - the same one as last time.

    **The identity is more than the serial.** `AsyncAppleAccount` invents a device UUID when it is
    not given one, so building a fresh account every run made every export look to Apple like a
    different machine that happened to share a serial - and an account's device list gained an
    entry per export. :mod:`exporter.device` keeps the two ids and the Anisette provisioning
    between runs so that stops happening. No credential is stored; see there.

    :param anisette_url: A remote Anisette server, or None to run Anisette locally. Local is the
        default because it keeps the login between this machine and Apple; a server sees the
        headers, and a session is bound to whichever established it.
    :param libs_path: Where to cache Apple's ADI libraries, so a later run does not fetch them
        again. They are downloaded on first use when this is None.
    :param provider: An Anisette provider to use instead of building one. **This is what makes
        the flow runnable on Android at all**: the local provider built below is FindMy.py's,
        which reaches the `anisette` package and therefore `unicorn`, a CPU emulator that cannot
        be built for Android. The app produces Anisette from Apple's own ADI libraries in-process
        and passes the result here. See AGENTS.md rule 4.
    :param identity: Who this client says it is. Defaults to the exporter's.
    :param identity_path: Where the device identity is kept between runs. Defaults to the
        desktop's per-platform location; the app passes its own storage.
    """
    stored = device.load(identity_path)

    if provider is None:
        provider = _make_provider(anisette_url, libs_path, stored, identity)

    if stored is None:
        return AsyncAppleAccount(
            provider, device_name=identity.device_name, timeout=LOGIN_TIMEOUT_SECONDS)

    # Only the ids are restored. The rest of the shape has to be there because the library reads
    # it, and every field of it is empty on purpose - this is a logged-out account that happens to
    # know what device it is.
    state: Any = {
        "type": "account",
        "ids": {"uid": stored["uid"], "devid": stored["devid"]},
        "account": {"username": None, "password": None, "info": None},
        "login": {"state": LoginState.LOGGED_OUT.value, "data": None},
        "anisette": provider.to_json(),
    }

    logger.info("Reusing the stored device identity, so this is not a new device to Apple")

    return AsyncAppleAccount(
        provider,
        state_info=state,
        device_name=identity.device_name,
        timeout=LOGIN_TIMEOUT_SECONDS,
    )


def _make_provider(
    anisette_url: str | None,
    libs_path: str | None,
    stored: dict[str, Any] | None,
    identity: ClientIdentity = EXPORTER_IDENTITY,
) -> LocalAnisetteProvider | RemoteAnisetteProvider:
    """Build the Anisette provider, restoring its provisioning data if there is any."""
    if anisette_url is not None:
        # Its own session, so raising the account's does nothing for this one: the Anisette fetch
        # happens inside the login but from the provider's own client. The local provider below
        # takes no timeout, because there is no HTTP in it to spend one on.
        return RemoteAnisetteProvider(
            anisette_url, serial=identity.serial, timeout=LOGIN_TIMEOUT_SECONDS)

    saved = (stored or {}).get("anisette")
    state_blob = None

    if isinstance(saved, dict) and saved.get("type") == "aniLocal" and saved.get("prov_data"):
        # Provisioning is what makes this the same machine to Apple's servers, so a fresh one is
        # part of what made every run a new device.
        try:
            state_blob = BytesIO(base64.b64decode(saved["prov_data"]))
        except (ValueError, TypeError):
            logger.warning("The stored Anisette provisioning could not be read; starting fresh")

    return LocalAnisetteProvider(
        libs_path=libs_path, serial=identity.serial, state_blob=state_blob)


def remember(account: AsyncAppleAccount, identity_path: Path | None = None) -> None:
    """
    Store this account's device identity, so the next export is the same device.

    Called once signing in has worked, rather than at the end: the device is registered by then,
    and an export that is abandoned afterwards should not leave an entry nothing can reuse.

    **This does not name the entry, and nothing here can.** Signing in registers the device by
    itself (findmy-export 01-authentication §13) and Apple synthesises its row from the client
    identity, so the name defaults to the claimed hardware - `MacBookPro`. The only call that sets
    a name is the `postdata` announce of Stage 2 §7, and Apple refuses it without a push token:

        HTTP 401, body {'ec': -800012, 'em': 'Push token is invalid.'}

    A push token is what makes a registered device trusted for verification codes, and §13 is
    explicit that this is "a boundary to hold rather than a gap to close" - an exporter that became
    a second factor for somebody's Apple ID would be a much larger thing to have compromised. So
    the announce was tried, refused, and removed rather than left failing on every fresh install.

    **The serial is the label instead**, which is what §13 designed it for: `X-Apple-I-SRL-NO` is
    sent during sign-in, needs no announce, and `0PENTAGXPORT` is the one field in that row a
    person can actually read. See :mod:`exporter.identity`.
    """
    # The account's own serialisation, with only the harmless parts taken out of it. It also
    # carries the username, the password and the login state - which is exactly why this picks
    # fields rather than handing the whole mapping to `device.save`.
    state = account.to_json()

    device.save(
        state["ids"]["uid"], state["ids"]["devid"], state["anisette"], identity_path)


MAX_LOGIN_ATTEMPTS = 3
"""
How many times an Apple ID and password may be offered in one run.

Small on purpose. **Apple locks an account after enough failed sign-ins**, and how many is not a
thing to establish by experiment on somebody's real account - the cost of being wrong is not a
failed export, it is an account they have to go and recover.
"""

MAX_CODE_ATTEMPTS = 3
"""How many times a verification code may be submitted before the sign-in is given up on."""

CODE_AGAIN = "again"
"""Ask for the code again. The one Apple already sent is still valid, so this is the usual answer."""

CODE_RESEND = "resend"
"""
Have Apple send a new code first.

**This invalidates the code already sent** (findmy-export 01-authentication §5), so it is the right
answer only when that one is gone or expired - never a default, and never something to do on the
user's behalf.
"""


async def log_in(
    account: AsyncAppleAccount,
    email: str,
    password: str,
    *,
    choose_second_factor: Callable[[Sequence[str]], Awaitable[int]],
    get_code: Callable[[], Awaitable[str]],
    retry_credentials: Callable[[Exception, int], Awaitable[tuple[str, str] | None]] | None = None,
    retry_code: Callable[[Exception, int], Awaitable[str | None]] | None = None,
) -> None:
    """
    Sign in, dealing with two-factor authentication if the account asks for it.

    Both things the user types can be got wrong, and neither ends the run when it is. See
    :func:`unlock` for why that matters: everything before a failure has to be done again, and the
    verification code in particular is not a thing that can simply be re-entered once it is gone.

    :param choose_second_factor: Given the methods as text, returns which one to use. Awaited,
        because asking is drawn on a terminal and drawing happens inside this same event loop.
    :param get_code: Returns the code the user received. Awaited, as above.
    :param retry_credentials: Awaited with the error and the attempt number when Apple rejects the
        Apple ID or password; returns a replacement pair, or None to stop. None means one attempt.
    :param retry_code: Awaited with the error and the attempt number when Apple rejects the
        verification code; returns :data:`CODE_AGAIN`, :data:`CODE_RESEND`, or None to stop.
    :raises ExportSourceError: If signing in does not end signed in.
    :raises InvalidCredentialsError: If the last attempt is rejected, or the user stops.
    """
    state = await _log_in_with_retries(account, email, password, retry_credentials)

    if state == LoginState.REQUIRE_2FA:
        methods = await account.get_2fa_methods()
        if not methods:
            raise ExportSourceError(
                "Apple asked for a verification code but offered no way to send one.",
            )

        chosen = methods[await choose_second_factor([_describe_factor(m) for m in methods])]
        await chosen.request()
        state = await _submit_code_with_retries(chosen, get_code, retry_code)

    if state != LoginState.LOGGED_IN:
        raise ExportSourceError(f"Signing in ended at {state} rather than signed in.")


async def _log_in_with_retries(account, email, password, retry_credentials):
    """
    Offer the Apple ID and password, letting a rejected pair be corrected in place.

    **Capped, and never automatic.** Apple locks an account after enough failed sign-ins, and how
    many is not this project's to discover on somebody's real account - so `retry_credentials` is
    asked every time and the count is small. A caller that passes None gets exactly one attempt,
    which is what this did before.
    """
    for attempt in range(1, MAX_LOGIN_ATTEMPTS + 1):
        try:
            return await account.login(email, password)
        except InvalidCredentialsError as e:
            logger.info("Apple rejected the credentials on attempt %d", attempt)

            replacement = (
                None
                if retry_credentials is None or attempt == MAX_LOGIN_ATTEMPTS
                else await retry_credentials(e, attempt)
            )
            if replacement is None:
                raise

            email, password = replacement

    raise AssertionError("unreachable")  # pragma: no cover


async def _submit_code_with_retries(chosen, get_code, retry_code):
    """
    Submit the verification code, letting a mistyped one be corrected.

    **The default retry re-types the code rather than asking for a new one**, and the difference is
    not cosmetic: requesting delivery again sends a second code *and invalidates the first*
    (findmy-export 01-authentication §5). So somebody who mistyped a code they are still holding
    would lose it by being "helpfully" sent another, and a resend has to be a thing they choose.
    """
    for attempt in range(1, MAX_CODE_ATTEMPTS + 1):
        try:
            return await chosen.submit(await get_code())
        except InvalidCredentialsError as e:
            logger.info("Apple rejected the verification code on attempt %d", attempt)

            choice = (
                None
                if retry_code is None or attempt == MAX_CODE_ATTEMPTS
                else await retry_code(e, attempt)
            )
            if choice is None:
                raise

            if choice == CODE_RESEND:
                # Deliberate: this is what invalidates whatever they were holding.
                await chosen.request()

    raise AssertionError("unreachable")  # pragma: no cover


def _describe_factor(method: object) -> str:
    """Name one second-factor method for a person choosing between them."""
    if isinstance(method, TrustedDeviceSecondFactorMethod):
        return "a trusted device"
    if isinstance(method, SmsSecondFactorMethod):
        return f"SMS to {method.phone_number}"
    return type(method).__name__


async def open_client(
    account: AsyncAppleAccount, identity: ClientIdentity = EXPORTER_IDENTITY,
) -> AsyncFindMyClient:
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
                device_name=identity.device_name,
                device_serial=identity.serial,
            ),
        )
    except Exception:
        await session.close()
        raise

    return AsyncFindMyClient(account, session, store)


MAX_UNLOCK_ATTEMPTS = 3
"""
How many times a passcode may be offered in one run.

A bound rather than a free retry, because **attempts are probably a limited resource**. FindMy.py
says Apple's escrow services generally cap them and that what this one allows is not established,
which is a good reason not to find out on somebody's real account.
"""


async def unlock(client, record, ask_passcode, on_rejection) -> None:
    """
    Recover the keychain keys, letting a rejected passcode be retried in place.

    **The retry is the point.** Both entry points used to make exactly one attempt, and a rejection
    propagated out of the whole export - so a typo in a hidden field cost the sign-in, the
    verification code and everything after it, and the user started again from the Apple ID.

    That was the wrong shape for this particular failure. The library's first advice on a rejection
    is to try again *with the same passcode*, because the call has been observed to fail
    intermittently and then succeed; the second is that a wrong passcode and a mis-parameterised
    exchange are indistinguishable here, so a rejection is not proof the passcode was wrong. Both
    say retry, and only this loop makes retrying cheap.

    **Nothing retries on its own.** :func:`on_rejection` is asked each time and a false answer
    stops, so the decision to spend another attempt is always the user's - which matters because
    the cap is unknown and the cost of exhausting it is not recoverable from here.

    :param client: An open Find My client.
    :param record: The escrow record chosen from `recovery_options()`.
    :param ask_passcode: Awaited for a passcode, given the attempt number, starting at 1.
    :param on_rejection: Awaited with the error and the attempt number; returns whether to ask
        again. Not called after the last attempt, where there is nothing to decide.
    :raises RecoveryError: If the last attempt is rejected, or the user declines another.
    """
    for attempt in range(1, MAX_UNLOCK_ATTEMPTS + 1):
        passcode = await ask_passcode(attempt)
        try:
            await client.unlock(record, passcode)
            return
        except RecoveryError as e:
            logger.info("Escrow recovery rejected attempt %d of %d", attempt, MAX_UNLOCK_ATTEMPTS)

            if attempt == MAX_UNLOCK_ATTEMPTS or not await on_rejection(e, attempt):
                raise
        finally:
            # The passcode is not this program's to keep a moment longer than the call needs it,
            # and a retry loop holds one across an interaction that waits on a person.
            del passcode


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

    if decrypted.skipped_total:
        # Reported, not raised. See `Fetched.undecryptable` for why a count here is not
        # automatically wrong - and why it still must not be silent.
        logger.info(
            "%d record(s) in the zone did not decrypt: %s",
            decrypted.skipped_total,
            ", ".join(f"{reason} x{count}" for reason, count in decrypted.skipped.items()),
        )

    return Fetched(
        candidates=candidates,
        skipped=skipped,
        undecryptable=decrypted.skipped_total,
        first_miss=decrypted.first_miss,
    )


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
