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

**Nothing here writes to the account.** Recovery unwraps shares to a key; it does not enrol this
device as a peer, sign anything, or create a record. That is a deliberate line, and the moment
something crosses it this docstring should stop saying so.
"""

from __future__ import annotations

import json
import plistlib
import traceback
from typing import Any

import identity as app_identity
from exporter import icloud
from findmy.keychain.recovery import RecoveryError

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

REASON_NO_SUCH_RECORD = "no_such_record"
"""The serial Java asked to unlock with is not in the list it was given."""

REASON_NO_SUCH_ACCESSORY = "no_such_accessory"
"""An id was asked for that this session never fetched, or has not fetched since reopening."""


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

    # `str(e)` is empty for several of these - TimeoutError most of all - so the last line of
    # the traceback stands in. It names the exception type, which is not a good message but is
    # infinitely better than a colon with nothing after it.
    lastLine = detail.strip().splitlines()[-1] if detail.strip() else ""

    return _failure(REASON_UNKNOWN, lastLine or f"{what} failed for an unrecorded reason")


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

        self._records = list(options.recoverable)

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
                    "description": record.describe(),
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
            self._loop.run_until_complete(self._client.unlock(record, passcode))

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
