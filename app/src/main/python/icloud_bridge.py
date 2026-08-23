"""
Reading an Apple account directly, instead of importing a zip somebody made on a Mac.

The desktop exporter is a UI over :mod:`exporter.icloud`, and that module is pure Python with no
desktop in it - so the app can run the same pipeline. This is the layer between it and Java:
one flow, four steps, each a separate call because a person has to answer something between
them.

    open -> recoveryOptions -> unlock(serial, passcode) -> fetch

**Why a session object rather than four functions.** Two of the steps need what the previous one
opened - a keychain session and a Find My client, both of which hold sockets - and the passcode
step waits on a dialog. Java holds the session for as long as that takes and closes it when the
screen goes away.

**Everything here returns a JSON string, including failures.** A raised Python exception arrives
in Java as a `PyException` whose message is whatever `str()` produced, which for several of the
errors worth reporting here is the empty string. The app has already shipped one dialog reading
`Login failed:` with nothing after it. So a failure is a value: `{"ok": false, "reason": ...,
"message": ...}`, and the `reason` is what Java branches on so the wording stays in
`strings.xml` where it can be translated.

**Everything here reads, except :meth:`ICloudSession.join`.** Recovery unwraps shares to a key and
creates nothing. `join` is the one call that writes: it enrols an escrow record and adds this app
to the account's keychain as a member in its own right.

That is worth the write for one reason. A non-member reads with view keys it holds a share of,
and those keep working - until the view keys **roll**, which is expected whenever the circle's
membership changes. Only a current member receives shares of the new ones, so a non-member goes
quietly stale: still holding keys, still looking fine, decrypting nothing new. Here that surfaces
as a map that stopped updating for no reason, which is the failure shape of issues #43 and #119
and the one users cannot diagnose for themselves.
"""

from __future__ import annotations

import base64
import json
import plistlib
import sys
import traceback
from typing import Any

import identity as app_identity
from exporter import icloud
from findmy.errors import InvalidCredentialsError
from findmy.keychain.enrolment import DeviceDescription
from findmy.keychain.join import JoinedPeer
from findmy.keychain.recovery import RecoveryError
from findmy.keychain.session import KeychainSessionError

REASON_NOT_SIGNED_IN = "not_signed_in"
"""The account handed over is not in a state that can talk to iCloud."""

REASON_NOTHING_TO_RECOVER_FROM = "nothing_to_recover_from"
"""
The account has no escrow record this can unlock the keychain with.

**The expected answer for a real class of user**, not an error: somebody who has an Apple ID but
has never owned an iPhone, iPad or Mac has nothing that ever escrowed a keychain. There is no
way for them to reach the tags in an account, and no amount of retrying changes that.
"""

REASON_SERVICE_UNSURE = "service_unsure"
"""
Nothing was reported usable *at all*, which reads as a service having a bad day.

Distinguished from :data:`REASON_NOTHING_TO_RECOVER_FROM` because the advice is opposite: this
one is worth trying again later, and that one never will be. FindMy.py draws the same
distinction through `viability_is_trustworthy`, for the same reason.
"""

REASON_PASSCODE_REJECTED = "passcode_rejected"
"""The escrow service did not accept the passcode. **Not proof it was wrong** - see below."""

REASON_NOT_UNLOCKED = "not_unlocked"
"""A join was asked for before anything unlocked, so there is no peer to sponsor it."""

REASON_CREDENTIALS_REJECTED = "credentials_rejected"
"""
Apple refused the stored password, so nothing needing the keychain can work.

**The account state does carry the password** - FindMy.py serialises it in `to_json` and restores
it - so this is not "nothing to authenticate with". It is Apple declining what was sent, and the
message is Apple's own `em` string passed through verbatim, which is why it reads as though
somebody mistyped something. Nobody typed anything.

**And it cannot be worked around here.** Opening a keychain session calls `request_pet`, which
re-runs the SRP exchange and raises rather than making do - unlike `_fresh_pet`, which falls back
to the token already held. That difference is deliberate: keychain operations need a genuinely
fresh PET. So the password working again is the only route, and that means a fresh sign-in.

Causes worth telling apart, none of which this can distinguish: the Apple ID password was
changed, Apple invalidated the credential, or this app's device was removed from the account.

Untreated it reads as an outage. The account read is silent by design and runs every six hours,
so the failure scrolls past a log nobody is watching while the tag list quietly stops matching
Find My, with nothing on screen ever saying why.
"""

REASON_MEMBERSHIP_UNUSABLE = "membership_unusable"
"""
The stored membership no longer reads the keychain.

**Not a broken app.** The peer may have been removed from the account - which is how a
user revokes this app - so the answer is to unlock with a passcode again and join afresh,
not to retry with the same stored keys.
"""

REASON_NO_SUCH_RECORD = "no_such_record"
"""The serial Java asked to unlock with is not in the list it was given."""

REASON_NO_SUCH_ACCESSORY = "no_such_accessory"
"""An id was asked for that this session never fetched, or has not fetched since reopening."""


REASON_NOT_AN_ACCESSORY = "not_an_accessory"
"""
The thing being renamed is one of the owner's own devices, not an accessory.

**An iPhone, iPad or Mac takes its name from more places than the naming record**, so writing one
here would change what Find My shows for it in one place and leave every other copy saying
something else. The app nicknames those locally instead, and keeps showing the real name
alongside. Only an accessory - an AirTag, or a Find My-certified tag somebody made - has the
naming record as its single source of truth.
"""

REASON_UNKNOWN = "unknown"
"""Anything else, with the exception text carried through so a report can be answered."""


APP_IDENTITY = icloud.ClientIdentity(
    serial=app_identity.APP_SERIAL,
    device_name=app_identity.APP_CLOUDKIT_DEVICE_NAME,
)
"""
Who this app says it is to CloudKit.

Rule 11: the same identity every path already sends. Defaulting this would present Apple with
`0PENTAGXPORT` - the *desktop exporter* - from a phone, and the two would share one device-list
entry that neither could be removed from safely.
"""


def _toUnixEpochMs(when: Any) -> int | None:
    """A datetime as milliseconds, or None. Formatting a date is Java's job, not this one's."""
    if when is None:
        return None

    try:
        return int(when.timestamp() * 1000)
    except (AttributeError, OSError, OverflowError, ValueError):
        # A record with an unrepresentable date is still a record worth offering.
        return None


def _failure(reason: str, message: str) -> str:
    return json.dumps({"ok": False, "reason": reason, "message": message})


def _unexpected(what: str) -> str:
    """
    Report a failure nothing anticipated, with the traceback in the log and the text in the UI.

    Both halves matter. The log is where a maintainer looks and the dialog is where the user is,
    and an empty dialog is what sent somebody to the issue tracker last time.
    """
    detail = traceback.format_exc()
    print(f"iCloud bridge: {what} failed:\n{detail}")

    # **Classified here rather than at each call site.** Every method on this class ends in the
    # same `except Exception: return _unexpected(...)`, and there are eight of them; catching
    # rejected credentials at one of those and not the others is how the retry screen keeps
    # turning up for a session that can never work. Doing it here also covers whatever is added
    # next, which the per-site version would not.
    if _isCausedBy(sys.exc_info()[1], InvalidCredentialsError):
        return _failure(REASON_CREDENTIALS_REJECTED, _lastLineOf(detail) or what + " was refused")

    # `str(e)` is empty for several of these - TimeoutError most of all - so the last line of
    # the traceback stands in. It names the exception type, which is not a good message but is
    # infinitely better than a colon with nothing after it.
    return _failure(
        REASON_UNKNOWN,
        _lastLineOf(detail) or f"{what} failed for an unrecorded reason",
    )


def _lastLineOf(detail: str) -> str:
    return detail.strip().splitlines()[-1] if detail.strip() else ""


def _isCausedBy(error: BaseException | None, wanted: type[BaseException]) -> bool:
    """
    Whether `wanted` is anywhere in the chain, not only at the top.

    **Wrapping is the normal case here, not the exception.** These calls go through
    `run_until_complete`, through FindMy.py's own layers, and anything that re-raises with
    context puts the interesting error underneath. Matching only the outermost type means the
    classification silently stops working the first time somebody adds a wrapper.
    """
    seen = set()
    while error is not None and id(error) not in seen:
        if isinstance(error, wanted):
            return True
        seen.add(id(error))
        error = error.__cause__ or error.__context__
    return False


def _asyncAccount(account: Any) -> Any:
    """
    The async account inside the app's synchronous one.

    `AppleAccount` is a thin wrapper that owns an `AsyncAppleAccount` and an event loop, and
    FindMy.py exposes neither. Reaching in rather than restoring a second account from the same
    stored JSON, because a second account is a second `aiohttp` session on a second loop
    presenting the same identity - two clients, one device, and sockets nobody closes.

    :mod:`main` already reaches for the same attribute when it swaps the Anisette provider, with
    the same guard: if a rename upstream makes this None, the caller reports a clean failure
    rather than raising something unreadable.
    """
    return getattr(account, "_asyncacc", None)


class ICloudSession:
    """
    One conversation with iCloud, held open across the calls a person has to answer.

    Java constructs this through :func:`openSession`, calls the steps in order, and calls
    :meth:`close` when the screen is finished with - in a `finally`, because two of these steps
    hold sockets and an abandoned session leaks them for the life of the process.

    **Not thread-safe, and it cannot be made so.** Every step runs a coroutine on the account's
    own event loop, and a loop cannot be driven from two threads at once. Java calls these from
    a single Rx chain.
    """

    def __init__(self, account: Any, asyncAccount: Any, loop: Any) -> None:
        self._account = account
        self._async = asyncAccount
        self._loop = loop
        self._client: Any = None
        self._records: list[Any] = []
        # The peer an unlock recovered, kept because it is what sponsors a join.
        self._sponsor: Any = None
        self._candidates: dict[str, Any] = {}

    def open(self) -> str:
        """
        Open the Find My client, which is a keychain session and a CloudKit client.

        Nothing is decrypted yet - that needs keys, and keys need :meth:`unlock`.
        """
        if self._client is not None:
            return json.dumps({"ok": True})

        try:
            client = self._loop.run_until_complete(
                icloud.open_client(self._async, APP_IDENTITY))
            self._loop.run_until_complete(client.__aenter__())
            self._client = client

            return json.dumps({"ok": True})
        except Exception:
            # Rejected credentials are classified inside _unexpected, which every method here
            # funnels through - see REASON_CREDENTIALS_REJECTED.
            return _unexpected("opening the Find My client")

    def recoveryOptions(self) -> str:
        """
        What this account could unlock its keychain from, as a list to choose between.

        **The two empty answers are different and are reported differently.** An account with no
        recoverable record has nothing this app can ever do for it; one where the service
        reported nothing usable at all is very likely a bad afternoon at Apple. Telling a user
        the first when it is the second sends them away for good.

        Serials are held here rather than sent to Java and back, so the unlock step names a
        record this session actually saw. A record is a live object with key material in it, and
        it is not something to reconstruct from a string.
        """
        if self._client is None:
            return _failure(REASON_NOT_SIGNED_IN, "The Find My client is not open.")

        try:
            options = self._loop.run_until_complete(self._client.recovery_options())
        except Exception:
            return _unexpected("asking what this account can be recovered from")

        # **This app's own escrow record is dropped, not shown.**
        #
        # Joining the trust circle registers this app as a device, so the account then holds a
        # record for `0PENTAGVIEWR` alongside the user's real hardware - and the picker offered
        # it, asking for "the screen-lock passcode of one of your Apple devices" for a device
        # that has no screen and no lock. There is no answer to that question: the escrow
        # passcode was generated, never shown, and is not the user's to know.
        #
        # Matched on the serial from `APP_IDENTITY`, which is the one place this app's identity
        # is written (rule 11) - not on the name, which Apple does not carry for this entry, nor
        # on a literal, which would be a second copy of the identity to keep in step.
        #
        # Filtered here rather than in the screen so the count below is honest: an account whose
        # only recoverable record is this app's has nothing the user can recover from, and
        # should be told so rather than shown one unusable tile.
        self._records = [
            record for record in options.recoverable
            if record.serial != APP_IDENTITY.serial
        ]

        if not self._records:
            if not options.viability_is_trustworthy:
                return _failure(
                    REASON_SERVICE_UNSURE,
                    "Nothing was reported usable at all, which reads as a service having a bad"
                    " day rather than an account with nothing to recover from.")

            return _failure(
                REASON_NOTHING_TO_RECOVER_FROM,
                "No record on this account can currently be recovered from.")

        return json.dumps({
            "ok": True,
            "devices": [
                {
                    "serial": record.serial,
                    # FindMy.py's own sentence, kept as the honest fallback for anything the
                    # screen cannot lay out itself.
                    "description": record.describe(),
                    # The parts, so the screen can build a tile rather than print a sentence.
                    # **`name` is the user's own and is often empty** - somebody who never
                    # renamed their phone has none - so the screen falls back to the class,
                    # which is a real word, rather than to "unnamed device".
                    "name": record.device_name or "",
                    "model": record.device_model or "",
                    # "iPhone", "iPad", "Mac" and so on: what picks the icon.
                    "modelClass": record.device_model_class or "",
                    # **When the record was created, not when the device was last used.**
                    # Labelling it "last used" would be a straight lie: a record escrowed three
                    # months ago says nothing about whether the phone was used this morning.
                    "escrowedAtMs": _toUnixEpochMs(record.escrowed_at),
                }
                for record in self._records
            ],
        })

    def unlock(self, serial: str, passcode: str) -> str:
        """
        Recover the keychain keys with one device's screen-lock passcode.

        **One attempt per call, and the retry belongs to Java.** :func:`exporter.icloud.unlock`
        loops with a callback because the CLI has a terminal to ask at; here the question is a
        dialog, and a Python function blocking a Java thread while it waits for one is a worse
        shape than returning and being called again.

        That leaves the cap - `MAX_UNLOCK_ATTEMPTS`, three - on Java's side, and it has to be
        respected there: attempts are probably a limited resource on Apple's end, and what this
        particular service allows is not established.

        **A rejection is not proof the passcode was wrong.** FindMy.py's own first advice is to
        try again with the same passcode, because the exchange has been seen to fail
        intermittently and then succeed. The message says so; do not reword it into "incorrect
        passcode".
        """
        if self._client is None:
            return _failure(REASON_NOT_SIGNED_IN, "The Find My client is not open.")

        record = next((r for r in self._records if r.serial == serial), None)
        if record is None:
            return _failure(
                REASON_NO_SUCH_RECORD,
                f"No recoverable device in this session has the serial {serial!r}.")

        try:
            # Recovered explicitly rather than through `client.unlock`, which does the same two
            # steps and keeps the peer to itself. The peer is what `join` sponsors, and joining
            # is the whole reason this app unlocks at all - so it has to survive the call.
            peer = self._loop.run_until_complete(
                self._client.session.recover(record, passcode))
            self._loop.run_until_complete(self._client.resume(peer))
            self._sponsor = peer

            return json.dumps({"ok": True})
        except RecoveryError as e:
            # The library's own text, whole. It says what was rejected and then what is worth
            # doing about it, in the order worth doing it.
            print(f"iCloud bridge: escrow recovery rejected a passcode for {serial}")

            return _failure(REASON_PASSCODE_REJECTED, str(e) or "The passcode was not accepted.")
        except Exception:
            return _unexpected("unlocking the keychain")
        finally:
            # Not this module's to hold a moment longer than the call needs it.
            del passcode

    def join(self, escrowPasscode: str) -> str:
        """
        Become a member of the account's keychain in this app's own right.

        **Why this is worth an irreversible call.** Without it the app reads with keys it holds a
        share of as a non-member. Those keep working - they are the keychain's view keys, not the
        sponsoring device's - right up until the view keys **roll**, which is expected whenever
        the circle's membership changes. Only a current member is given shares of the new ones, so
        a non-member goes quietly stale: still holding keys, still looking fine, decrypting
        nothing new. In this app that surfaces as a map that stopped updating for no reason, which
        is the failure shape of issues #43 and #119 and the one users cannot diagnose.

        **`escrowPasscode` is not the user's.** It is the passcode *this app's own* record will be
        recoverable under - generated by `EscrowPasscode`, 256 bits, never shown to anybody. The
        one the user typed recovered the sponsor and is already gone.

        **Never call this twice for one intent.** A response that will not decode is not a call
        that failed, and a timeout does not establish that nothing was sent - FindMy.py raises
        with `JOIN_HAPPENED` for exactly that, and the recovery is to sync the directory, not to
        try again. Java must treat any failure here as "it may have happened".

        Returns the membership to persist. **The keys in it are the only copy in existence**: lose
        them and the peer is stranded in the account with no way to use or remove it, so the
        caller must store it before anything else can go wrong.
        """
        if self._client is None:
            return _failure(REASON_NOT_SIGNED_IN, "The Find My client is not open.")

        if self._sponsor is None:
            return _failure(
                REASON_NOT_UNLOCKED,
                "Nothing has been unlocked in this session, so there is no peer to sponsor a"
                " join.")

        if not escrowPasscode:
            # Enrolment refuses this anyway - "a record enrolled under an empty passcode could be
            # recovered by anyone" - but failing here says which side got it wrong.
            return _failure(
                REASON_UNKNOWN, "No passcode was supplied for this app's own escrow record.")

        try:
            identity = self._async.identity
            outcome = self._loop.run_until_complete(self._client.session.join(
                self._sponsor,
                passcode=escrowPasscode,
                # **Read, not composed** - rule 11. The same identity reaches the escrow record's
                # metadata and the peer's stable info, both of which a person reads in a listing,
                # and a path that invents its own makes one client look like several.
                device=DeviceDescription(
                    name=app_identity.APP_CLOUDKIT_DEVICE_NAME,
                    model=identity.model,
                    serial=self._async.serial,
                    build=identity.os_build,
                ),
                os_version=identity.os_version,
            ))

            print(f"iCloud bridge: joined as {outcome.peer.peer_id}, "
                  f"{outcome.shares} view key(s) re-addressed")

            return json.dumps({
                "ok": True,
                "peer": outcome.peer.to_json(),
                # Kept alongside the membership, and not instead of it. The membership is how a
                # refresh avoids a passcode; the entropy plus the passcode above is how the peer
                # is recovered through escrow if the app's encrypted store is ever destroyed.
                # Neither substitutes for the other.
                "entropy": base64.b64encode(outcome.bottle.entropy).decode("ascii"),
                "label": outcome.label,
                "shares": outcome.shares,
            })
        except Exception:
            return _unexpected("joining the account's keychain")
        finally:
            del escrowPasscode

    def resume(self, peerJson: str) -> str:
        """
        Read the keychain as the member this app already is - no passcode, nothing borrowed.

        This is what the join bought. Every method that takes a peer wants only its id and its two
        private keys, which is what a stored membership carries.
        """
        if self._client is None:
            return _failure(REASON_NOT_SIGNED_IN, "The Find My client is not open.")

        try:
            peer = JoinedPeer.from_json(json.loads(peerJson))
            self._loop.run_until_complete(self._client.resume(peer))

            print(f"iCloud bridge: reading as {peer.peer_id}, with no passcode")

            return json.dumps({"ok": True})
        except Exception:
            # Worth its own reason. A membership that no longer works is not a broken app - the
            # peer may have been removed from the account - and the answer is to unlock with a
            # passcode again, not to retry this.
            detail = traceback.format_exc()
            print(f"iCloud bridge: could not read as the stored member:\n{detail}")

            return _failure(
                REASON_MEMBERSHIP_UNUSABLE,
                detail.strip().splitlines()[-1] if detail.strip() else "resume failed")

    def fetch(self) -> str:
        """
        Read and decrypt the account's accessories, and describe what is there.

        **Descriptions only - no key material.** The records themselves come from
        :meth:`records`, for the ones the user actually picks. Rendering every accessory's
        private key into a JSON string that crosses into Java, so that a screen can show a list
        of names, is more of the secret in more places than the screen needs.

        `hasName` is false for an accessory with no naming record in CloudKit. Not a problem to
        solve before importing - the app can show and rename a nameless tag perfectly well - but
        worth knowing in a picker, where `label` would otherwise read "unnamed" and `details` is
        the only thing telling one from another.
        """
        if self._client is None:
            return _failure(REASON_NOT_SIGNED_IN, "The Find My client is not open.")

        try:
            fetched = self._loop.run_until_complete(icloud.fetch(self._client))
        except Exception:
            return _unexpected("reading the account's accessories")

        self._candidates = {c.beacon_id: c for c in fetched.candidates}

        return json.dumps({
            "ok": True,
            "accessories": [
                {
                    "beaconId": candidate.beacon_id,
                    "name": candidate.name,
                    "emoji": candidate.emoji,
                    # What to show when it has no name of its own: what kind of thing it is, the
                    # serial Find My shows for it, and when it was paired - which is often the
                    # one a person recognises, because they remember buying it.
                    "label": candidate.label,
                    "details": candidate.details,
                    "hasAlignment": candidate.has_alignment,
                    "hasName": candidate.name is not None,
                }
                for candidate in fetched.candidates
            ],
            # Named rather than dropped quietly: "fewer tags than expected" and "some of those
            # were never tags" look identical from outside, and the second is the common one -
            # an account's own iPhones and Macs come back in the same records.
            "skipped": [
                {"beaconId": skipped.beacon_id, "reason": skipped.reason}
                for skipped in fetched.skipped
            ],
        })

    def records(self, selectionJson: str) -> str:
        """
        The chosen accessories, as the plists the importer already reads.

        **The same documents a bundle carries**, so Java feeds them to the path it has rather
        than growing a second one that means the same thing. An accessory read from an account
        and one read from a zip become the same rows in the same tables. That is most of why
        `opentagviewer_export` is a shared package.

        **Not through `to_export`, and the difference matters.** That function refuses an
        accessory with no naming record unless it is given a name to synthesise one from, which
        is right for what it is for: a *bundle* is inner-joined by its importer, so an accessory
        exported without a name is one silently missing after import. None of that applies here.
        Nothing is being written to a zip, the app left-joins the two, and a tag with no naming
        record is a thing it already knows how to show - it is what a self-generated tag is.

        So there is nothing to ask the user, and nothing for this module to invent. A name they
        have already given a tag lives in `UserBeaconOptions` and wins at display time anyway,
        which is the same mechanism that renames any other tag.

        :param selectionJson: `[{"beaconId": ...}]`, in the order to return them.
        """
        if self._client is None:
            return _failure(REASON_NOT_SIGNED_IN, "The Find My client is not open.")

        try:
            selection = json.loads(selectionJson)
        except ValueError:
            return _unexpected("reading the selection Java sent")

        accessories = []
        for chosen in selection:
            beaconId = chosen.get("beaconId")
            candidate = self._candidates.get(beaconId)

            if candidate is None:
                return _failure(
                    REASON_NO_SUCH_ACCESSORY,
                    f"No accessory in this session has the id {beaconId!r}. It was either never"
                    " fetched, or the session has been reopened since.")

            accessories.append({
                "beaconId": candidate.beacon_id,
                "ownedBeaconPlist": _plist(candidate.owned_beacon),
                # Null where CloudKit holds none. The app shows such a tag by what its own
                # record says it is, and the user can name it like any other.
                "namingRecordPlist": _plist(candidate.naming_record),
                "keyAlignmentPlist": _plist(candidate.key_alignment_record),
            })

        return json.dumps({"ok": True, "accessories": accessories})

    def rename(self, beaconId: str, plistXml: str, name: str, emoji: str) -> str:
        """
        Change an accessory's name and emoji in iCloud.

        **The second call here that writes**, and unlike :meth:`join` it changes something the
        owner sees in Find My on their own devices. FindMy.py sends back the rest of the record
        unchanged, so nothing but these two fields moves.

        **Refused for the owner's own devices**, and the refusal is made here rather than trusted
        to the caller. An iPhone, iPad or Mac carries its name in more places than this record;
        writing one would leave Find My showing a name that disagrees with the device itself. The
        test is :func:`opentagviewer_export.hardware.is_own_device`, which is the same one the
        exporter uses to decide that handing over a Mac's keys is a different act from handing
        over an AirTag's - two signals, an Apple model identifier or the shared secret only a
        device carries, and neither is a thing to re-derive in Java.

        **Judged from the stored record rather than by fetching.** Java already holds the
        `OwnedBeacons` plist this accessory was imported with - it is the same document a bundle
        carries - so the alternative is reading the whole account to answer a question the
        record on disk already answers.

        :param beaconId: The accessory's identifier, which is what `associatedBeacon` names.
        :param plistXml: Its stored `OwnedBeacons` plist, to decide what kind of thing it is.
        :param name: The new name, or empty to leave it alone.
        :param emoji: The new emoji, or empty to leave it alone.
        """
        if self._client is None:
            return _failure(REASON_NOT_SIGNED_IN, "The Find My client is not open.")

        # Empty means "leave it alone", not "set it to empty" - sending both fields every time
        # would blank the emoji of every accessory anybody renamed.
        changes = {field: value for field, value in (("name", name), ("emoji", emoji)) if value}

        if not changes:
            return _failure(REASON_UNKNOWN, "Nothing to change: pass a name, an emoji, or both.")

        refusal = _refuseToRenameADevice(plistXml)
        if refusal is not None:
            return refusal

        try:
            self._loop.run_until_complete(
                self._client.rename(beaconId, **changes))
        except KeychainSessionError:
            # Distinguished from the catch-all because the answer is different: this is not a
            # broken rename, it is a session that never got its keys, and the app can fix it by
            # resuming or unlocking rather than by telling the user something went wrong.
            return _failure(
                REASON_NOT_UNLOCKED,
                "The keychain is not open, so nothing can be written to the account yet.")
        except Exception:
            return _unexpected("renaming the accessory")

        return json.dumps({"ok": True})

    def close(self) -> None:
        """Close the client, and say so rather than raising if it will not go quietly."""
        if self._client is None:
            return

        try:
            self._loop.run_until_complete(self._client.__aexit__(None, None, None))
        except Exception:
            print(f"iCloud bridge: closing the Find My client failed:\n{traceback.format_exc()}")
        finally:
            self._client = None
            self._records = []
            # Dropped with the client, because they hold decrypted key material and the session
            # is over. A later `records` call then fails with a reason rather than handing back
            # secrets from a conversation that has ended.
            self._candidates = {}


def _refuseToRenameADevice(plistXml: str) -> str | None:
    """
    Say no if this is one of the owner's own devices, or None to carry on.

    The test is :func:`opentagviewer_export.hardware.is_own_device` - two signals, an Apple model
    identifier or the shared secret only a device carries - and it is called rather than
    reproduced. The exporter asks the same question to decide that handing over a Mac's keys is a
    different act from handing over an AirTag's, and one of those answers going stale while the
    other did not is exactly the bug this arrangement avoids.
    """
    try:
        record = plistlib.loads(plistXml.encode("utf-8"))
    except Exception:
        return _unexpected("reading the accessory's stored record")

    from opentagviewer_export.hardware import is_own_device

    if not is_own_device(record):
        return None

    return _failure(
        REASON_NOT_AN_ACCESSORY,
        "This is one of your own devices rather than an accessory. Its name is not something"
        " this record decides, so changing it here would not change it anywhere you would"
        " see it.")


def _plist(mapping: Any) -> str | None:
    """
    One plist mapping as the XML the importer reads, or None where there is no record.

    XML rather than binary because that is what a bundle carries and what Java's XPath parses.
    `plistlib` handles the two types that matter and are easy to forget: key material arrives as
    `bytes` and becomes `<data>`, and dates become `<date>`.
    """
    if mapping is None:
        return None

    return plistlib.dumps(dict(mapping), fmt=plistlib.FMT_XML).decode("utf-8")


def openSession(account: Any) -> ICloudSession | None:
    """
    Start an iCloud conversation on the account the app is already signed in with.

    **The account the app already has, not a second one restored from the same JSON.** One
    install is one device to Apple (rule 11), and the identity, the ADI state and the session
    all live on the object Java is holding. Restoring a parallel copy would put a second client
    on the wire under the same name.

    Returns None when there is nothing usable to work with, which Java reports as needing a
    sign-in - the same recovery as a session that has expired.
    """
    asyncAccount = _asyncAccount(account)
    loop = getattr(account, "_evt_loop", None)

    if asyncAccount is None or loop is None:
        print(
            "FindMy.py's account internals have changed: no _asyncacc or _evt_loop, so the"
            " iCloud flow cannot be driven from the account the app is signed in with.")
        return None

    return ICloudSession(account, asyncAccount, loop)
