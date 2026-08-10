from enum import Enum
from typing import Any
import json
import time
import traceback
from datetime import datetime, timezone
from io import BytesIO
import base64
import NSKeyedUnArchiver

from findmy import FindMyAccessory
from findmy.reports import (
    RemoteAnisetteProvider,
    AppleAccount,
    LoginState,
    SmsSecondFactorMethod,
    TrustedDeviceSecondFactorMethod,
)
from findmy.reports.anisette import BaseAnisetteProvider
from findmy.util import files as util_files
from findmy.reports.twofactor import (
    SyncSecondFactorMethod
)


class TwoFactorMethods(Enum):
    UNKNOWN = 0
    TRUSTED_DEVICE = 1
    PHONE = 2


def _toUnixEpochMs(dt: datetime | None) -> int | None:
    """
    Convert datetime to unix epoch (milliseconds)
    """
    if dt is None:
        return None
    return int(dt.timestamp() * 1000)


def foo(arg: str):
    """For testing..."""
    print("Bar!")
    print(f"The arg was: '{arg}'")

    for i in range(10):
        print(f"Hello {i}!")

    print("Done!")

    return {
        "Some": "Dictionary",
        "key": [1, 2, 3],
        "other": False,
        "and": True,
        "nested": {
            "a": "b",
            "c": "d"
        },
        "set": {"a", "b", "c"},
        "floats": 1.123456,
        "null maybe": None
    }


def decodeBeaconNamingRecordCloudKitMetadata(cleanedBase64: str) -> dict | None:
    """
    Extract some extra information from within the plist file `cloudKitMetadata` node
    (that is followed by a `<data>` element containing base64)

    Note that `cleanedBase64` must not contain line breaks `\\n`, or tabs `\\t`
    or other whitespace characters that get introduced by some plist parsers


    ### More info:

    The most popular java plist parser, the google java
    [dd-plist](https://mvnrepository.com/artifact/com.googlecode.plist/dd-plist) library,
    [does not currently support the `NSKeyedArchiver` plist format](https://github.com/3breadt/dd-plist/issues/70)
    (at the time of writing).

    However somebody has managed to create a parser in python: https://github.com/avibrazil/NSKeyedUnArchiver

    So because there's some interesting data to be extracted from this `NSKeyedArchiver`-encoded data,
    we will extract it using python via this nice library and pass the needed data back to Java

    See:
    - https://www.mac4n6.com/blog/2016/1/1/
      manual-analysis-of-nskeyedarchiver-formatted-plist-files-a-review-of-the-new-os-x-1011-recent-items
    - https://github.com/malmeloo/FindMy.py/issues/31#issuecomment-2628072362
    - https://github.com/3breadt/dd-plist/issues/70

    """
    try:
        data = base64.b64decode(cleanedBase64)
        d_dict = NSKeyedUnArchiver.unserializeNSKeyedArchiver(data)

        # This is actually a pretty large object, but very little of the data seems useful to our app

        RecordCtime: datetime | None = d_dict.get("RecordCtime", None)
        RecordMtime: datetime | None = d_dict.get("RecordMtime", None)
        ModifiedByDevice: str | None = d_dict.get("ModifiedByDevice", None)

        res = {
            "creationTime": _toUnixEpochMs(RecordCtime),
            "modifiedTime": _toUnixEpochMs(RecordMtime),
            "modifiedByDevice": ModifiedByDevice
        }

        print(f"Computed result: {res}")

        return res

    except Exception:
        print(f"Failed to parse due to {traceback.format_exc()}")
        return None


def _convertToJavaDictWrapper(method: SyncSecondFactorMethod) -> dict[str, Any]:
    # Deliberately heterogeneous: it carries the method object plus the ints and strings
    # the Java side reads back out. Without the annotation the type is inferred from the
    # first entry alone, and every later assignment looks like an error.
    return_obj: dict[str, Any] = {
        "obj": method
    }

    print(f"The input is {method} of class {type(method)}")

    if isinstance(method, TrustedDeviceSecondFactorMethod):
        print("Option: Trusted Device 2FA method")

        return_obj["type"] = TwoFactorMethods.TRUSTED_DEVICE.value

    elif isinstance(method, SmsSecondFactorMethod):
        print(f"Option: SMS ({method.phone_number})")

        return_obj["type"] = TwoFactorMethods.PHONE.value
        return_obj["phoneNumber"] = method.phone_number
        return_obj["phoneNumberId"] = method.phone_number_id

    else:
        print(f"Unmapped 2FA method! (type: {type(method)})")

        return_obj["type"] = TwoFactorMethods.UNKNOWN.value

    return return_obj


class LocalAnisetteProvider(BaseAnisetteProvider):
    """Anisette produced on this device, rather than by somebody else's server.

    Apple's ADI libraries are native Android code, so there is no reason a login has to be
    relayed through a public Anisette server - which sees the traffic, and takes the app down
    with it when it goes offline.

    The base class does almost all the work: it builds every header itself and only asks a
    provider for two values. Both come from Java, where the libraries are loaded and ADI is
    provisioned (see the `anisette` package).

    It serializes as the *remote* provider, on purpose. FindMy embeds the anisette provider in
    the account state it exports, but this one has nothing worth embedding - the ADI state
    lives in app storage, not in the account. Writing the remote server's URL instead means an
    exported login stays restorable anywhere, including by app versions that have never heard
    of local Anisette, and by this app when local Anisette is unavailable. Whether Anisette
    came from here or from a server is a transport detail, not part of the account.
    """

    def __init__(self, bridge: Any, fallbackServerUrl: str) -> None:
        super().__init__()
        self._bridge = bridge
        self._fallbackServerUrl = fallbackServerUrl

    @property
    def otp(self) -> str:
        return str(self._bridge.otp())

    @property
    def machine(self) -> str:
        return str(self._bridge.machine())

    def to_json(self, dst=None, /):
        # Deliberately the remote mapping - see the class docstring.
        return util_files.save_and_return_json(
            {
                "type": "aniRemote",
                "url": self._fallbackServerUrl,
            },
            dst,
        )

    @classmethod
    def from_json(cls, val):
        # Never reached: nothing is ever serialized as this type. Restoring picks the local
        # provider up again through _anisetteProvider, not through deserialization.
        raise NotImplementedError(
            "LocalAnisetteProvider is never serialized under its own type"
        )

    async def close(self) -> None:
        pass


def _anisetteProvider(anisetteServerUrl: str, localAnisette: Any = None):
    """Prefer this device, fall back to the configured server.

    The fallback is not a nicety. Apple can change their libraries at any time, the download
    needs network the first time, and the whole mechanism is an optimisation over something
    that already works - so anything going wrong here has to degrade to the old behaviour
    rather than fail a login.
    """
    if localAnisette is not None:
        try:
            if localAnisette.ensureReady():
                print(f"Using local Anisette: {localAnisette.describe()}")
                return LocalAnisetteProvider(localAnisette, anisetteServerUrl)
            print(
                "Local Anisette unavailable, using the remote server instead: "
                f"{localAnisette.unavailableReason()}"
            )
        except Exception:
            print(f"Local Anisette failed, using the remote server: {traceback.format_exc()}")

    return RemoteAnisetteProvider(anisetteServerUrl)


def loginSync(email: str, password: str, anisetteServerUrl: str,
              localAnisette: Any = None) -> dict:
    try:
        anisette = _anisetteProvider(anisetteServerUrl, localAnisette)
        acc = AppleAccount(anisette)

        state = acc.login(email, password)

        # Which Anisette established this session decides whether continuing it against the
        # other kind will still be recognised by Apple - the machine identity differs between
        # them. Recorded on every login, including remote ones, so it cannot go stale.
        if localAnisette is not None:
            localAnisette.recordSessionProvenance(
                isinstance(anisette, LocalAnisetteProvider)
            )

        if state == LoginState.REQUIRE_2FA:  # Account requires 2FA
            methods = acc.get_2fa_methods()

            named_methods_list = []  # create a map for use in Java...
            for method in methods:
                named_methods_list.append(
                    _convertToJavaDictWrapper(method)
                )

            # Java needs to show us a nice UI
            # where we can select how we want to auth...
            return {
                "account": acc,
                "loginState": state.value,
                "loginMethods": named_methods_list
            }

        # Any of the other cases. I'm not sure if this can even happen...
        return {
            "account": acc,
            "loginState": state.value,
            "loginMethods": None
        }

    except Exception as e:
        print(f"Failed to log in due to error: {traceback.format_exc()}")
        return {
            "error": str(e)
        }


def exportToString(account: AppleAccount) -> str:
    """
    Replaces the old account.export() pattern. In FindMy 0.9.x, AppleAccount uses to_json/from_json.
    The returned dict (AccountStateMapping) embeds the anisette provider state, so the
    server URL no longer needs to be supplied at restore time.
    """
    return json.dumps(account.to_json())


# The anisette provider type accounts are stored with.
#
# FindMy's own `aniLocal` is not it: that one needs the unicorn CPU emulator to run Apple's ADI
# blob, and Chaquopy cannot build unicorn's native code - which is why app/stubs/unicorn exists
# at all.
#
# This app does run ADI locally, but through its own path (the `anisette` package, which loads
# Apple's real Android libraries), and accounts are deliberately still serialized as
# "aniRemote" - see LocalAnisetteProvider. So this stays the only supported stored type, and
# saved logins remain restorable by any build.
SUPPORTED_ANISETTE_TYPE = "aniRemote"


def assertAnisetteIsSupported(serializedAccountData: str) -> str | None:
    """
    Check a stored account uses a provider we can actually run, before anything tries
    to use it.

    Without this the failure surfaces as a NotImplementedError raised from inside the
    stub `unicorn` package, several layers down in anisette, at whatever unlucky moment
    the provider is first exercised. Checking the serialized state up front turns that
    into a clear message at the app boundary.

    :returns: None if supported, otherwise a human-readable reason.
    """
    try:
        data = json.loads(serializedAccountData)
        anisette_type = (data.get("anisette") or {}).get("type")

        if anisette_type == SUPPORTED_ANISETTE_TYPE:
            return None
        if anisette_type is None:
            return "This saved login has no Anisette configuration and cannot be restored."
        return (
            f"This saved login uses an unsupported Anisette provider ({anisette_type}). "
            "OpenTagViewer supports remote Anisette servers only on Android - local "
            "Anisette needs a CPU emulator that cannot be built for this platform."
        )
    except Exception:
        print(f"Could not inspect anisette configuration: {traceback.format_exc()}")
        return "This saved login could not be read."


def _preferLocalAnisette(acc: AppleAccount, localAnisette: Any) -> None:
    """Swap a restored account's anisette provider for the local one, if it is usable.

    This matters more than it looks: restoring a saved login is the common path, and a
    restored account carries the remote provider that was serialized with it. Without this,
    local Anisette would only ever apply to the one login where the account was first created,
    and every subsequent session would go back to relaying through a public server.

    Failing here is not an error - the account already has a working remote provider, so the
    worst case is the behaviour the app has always had.
    """
    if localAnisette is None:
        return

    try:
        if not localAnisette.ensureReady():
            print(
                "Local Anisette unavailable, restored account will use its remote server: "
                f"{localAnisette.unavailableReason()}"
            )
            if localAnisette.isChangingMachineIdentity():
                # Worth saying plainly. This session was established with a machine identity
                # that no longer exists for it, so Apple may not recognise the device any
                # more and may demand re-authentication. That is a consequence, not a bug,
                # and it should not look like one.
                print(
                    "WARNING: this session was established with local Anisette and is now "
                    "continuing against a remote server. Apple sees a different machine, so "
                    "signing in again may be required."
                )
            return

        # AppleAccount is a synchronous wrapper; the provider lives on the async account it
        # delegates to. FindMy exposes no setter for it, hence reaching in - and hence the
        # getattr guards, so a rename upstream degrades to "keep using remote" rather than
        # breaking every restore.
        inner = getattr(acc, "_asyncacc", None)
        previous = getattr(inner, "_anisette", None) if inner is not None else None
        if inner is None or previous is None:
            print("FindMy's account internals have changed; keeping the remote provider")
            return

        inner._anisette = LocalAnisetteProvider(
            localAnisette, getattr(previous, "_server_url", "")
        )
        print(f"Restored account switched to local Anisette: {localAnisette.describe()}")
    except Exception:
        print(f"Could not switch to local Anisette: {traceback.format_exc()}")


def getAccount(
        serializedAccountData: str,
        anisetteServerUrl: str | None = None,
        localAnisette: Any = None) -> AppleAccount | None:
    """
    Restore an AppleAccount via FindMy 0.9.x's `from_json`. The anisette provider is rebuilt
    from the embedded state inside the JSON, so `anisetteServerUrl` is unused here.

    If `localAnisette` is supplied and usable, the rebuilt provider is then swapped for one
    backed by this device - see `_preferLocalAnisette`.
    """
    try:
        unsupported = assertAnisetteIsSupported(serializedAccountData)
        if unsupported:
            print(f"Refusing to restore account: {unsupported}")
            return None

        data = json.loads(serializedAccountData)

        acc = AppleAccount.from_json(data)
        _preferLocalAnisette(acc, localAnisette)

        print(f"Login State: {acc.login_state}")

        return acc
    except Exception:
        err = traceback.format_exc()
        print(f"Failed to restore account from string: {err}")
        return None


def convertPlistToJson(
        plistXmlString: str,
        alignmentPlistXmlString: str | None = None) -> str | None:
    """
    One-shot conversion from the legacy plist XML representation (still stored in
    OwnedBeacon.content) to the JSON form that FindMy 0.9.x expects.

    Used in two places (called from Java):
    - During .zip import: convert once and store alongside the raw plist
    - As a lazy backfill: when reading an OwnedBeacon row that predates the upgrade

    `alignmentPlistXmlString` is the accessory's KeyAlignmentRecord, if the export
    contained one (format 0.0.2 and later). It supplies the rolling-key index macOS last
    observed, so fetching can start there. Without it the accessory starts at index 0 from
    its pairing date and the first fetch searches the tag's entire history - tens of
    thousands of keys for an older tag. Optional, because exports predating 0.0.2 have no
    such record and must keep working.

    Returns None on failure so Java can decide how to recover.
    """
    try:
        fp = BytesIO(plistXmlString.encode('utf-8'))

        # from_plist accepts bytes for the alignment record but not a file object,
        # unlike its first parameter.
        alignment_bytes = (
            alignmentPlistXmlString.encode('utf-8') if alignmentPlistXmlString else None
        )

        accessory = FindMyAccessory.from_plist(fp, alignment_bytes)
        return json.dumps(accessory.to_json())
    except Exception:
        print(f"convertPlistToJson failed: {traceback.format_exc()}")
        return None


def _filterReportsByTimeRange(reports, startMs, endMs):
    """
    Apple's network only ever returns ~7 days of history and 0.9.x removed the
    user-facing time-range parameters from fetch_location_history. We filter
    here so the Java side keeps the same time-window semantics it had before.
    """
    out = []
    for r in reports:
        ts_ms = _toUnixEpochMs(r.timestamp)
        if ts_ms is None:
            continue
        if startMs is not None and ts_ms < startMs:
            continue
        if endMs is not None and ts_ms > endMs:
            continue
        out.append(r)
    return out


# Apple accepts at most ~290 hashed keys per request, and FindMy.py walks the key index
# range one step at a time (15 minutes per step for an AirTag). Anything wider than this
# is enough round trips to be worth avoiding.
_ALIGNMENT_PROBE_THRESHOLD_INDICES = 2000

# Apple rejects requests carrying much more than ~290 hashed keys, so a ranged fetch is split
# into chunks below that with a little headroom.
_MAX_KEYS_PER_REQUEST = 255

# Ceiling on how many requests one ranged fetch may make. A single day is ~96 indices for an
# AirTag, so roughly one request; the cap only bites when alignment is unknown and the key
# range balloons. Without it, a history screen could quietly fire hundreds of requests at
# Apple - the account-flagging risk from issue #30, arriving through a different door.
_MAX_REQUESTS_PER_RANGE_FETCH = 8

# Attempts per request. Apple's endpoint times out often enough to see it by hand, and for a
# single day the range is one request - so one timeout meant a whole day of history came back
# empty. Two attempts, not more: this is a retry against a rate-sensitive endpoint.
_RANGE_FETCH_ATTEMPTS = 2
_RANGE_FETCH_RETRY_DELAY_SECONDS = 1


def _isAlignmentWide(accessory: FindMyAccessory, start, end) -> int:
    """Width of the key-index range a history fetch would search, or 0 if unknown."""
    try:
        return accessory.get_max_index(end) - accessory.get_min_index(start)
    except Exception:
        print(f"Could not determine key index width: {traceback.format_exc()}")
        return 0


def _fetchReportsForAccessory(account: AppleAccount, accessory: FindMyAccessory, start, end):
    """
    Fetch reports for one accessory, avoiding a full-history key search when possible.

    Without a key alignment record an accessory starts at index 0 from its pairing date,
    so a history fetch searches the tag's entire life - measured at ~50,000 indices for an
    18-month-old AirTag, which at ~290 keys per request is hundreds of round trips. That is
    the account-flagging risk from issue #30.

    When the window is that wide we ask for the latest location *instead of* the history,
    not before it. `fetch_location` walks backwards from now and stops at the first hit, and
    alignment is updated as a side effect of any successful fetch. So one call both returns
    something useful and collapses the window for every fetch that follows.

    Doing it as a probe *before* a history fetch was worse: for a tag with no recent reports
    the probe traverses the whole range, finds nothing, narrows nothing, and then the history
    fetch traverses it all over again - double the work, in the worst case rather than the
    best. Replacing the call avoids that.
    """
    width = _isAlignmentWide(accessory, start, end)

    if width > _ALIGNMENT_PROBE_THRESHOLD_INDICES:
        print(f"Key search window is {width} indices wide; fetching latest location only, "
              f"to establish alignment without searching the tag's whole history.")
        latest = account.fetch_location(accessory)

        narrowed = _isAlignmentWide(accessory, start, end)
        if narrowed < width:
            print(f"Key search window narrowed from {width} to {narrowed} indices; "
                  f"subsequent fetches will search the narrow range.")
        else:
            # No report found anywhere in range. Nothing to align to, and a history fetch
            # would search the same empty range again, so don't.
            print("No reports found for this accessory; leaving alignment untouched and "
                  "skipping the history fetch.")

        return [latest] if latest is not None else []

    return account.fetch_location_history(accessory)


def _chunk(items, size):
    """Split a list into consecutive chunks of at most `size`."""
    return [items[i:i + size] for i in range(0, len(items), size)]


def _fetchReportsInRange(account: AppleAccount, accessory: FindMyAccessory, start, end):
    """
    Fetch the reports an accessory produced inside a specific time window.

    `fetch_location_history(accessory)` cannot do this. For a rolling-key accessory it calls
    `_fetch_accessory_reports(..., only_latest=True)`, which walks backwards from *now* and
    returns as soon as the first batch of keys yields anything - roughly the last day, since
    an AirTag steps its index every 15 minutes and Apple takes ~290 keys per request. It also
    takes no date range at all: 0.9.x removed the range parameters that 0.7.6 had.

    So asking for last Tuesday returned today's reports, which the caller then filtered away
    to nothing. The history screen showed data for today and empty days behind it, for every
    tag, with no error anywhere.

    What the library does expose is the two halves needed to do it properly:

      * `accessory.keys_between(start, end)` yields `(index, key)` for exactly the window,
        both primary and secondary, already de-duplicated
      * `fetch_location_history(list_of_keys)` batches plain keys into a single request and
        decrypts what comes back

    So we generate the window's keys ourselves, ask for those, and update the accessory's
    alignment from each report - `keys_between` tells us which index a key belongs to, which
    is the piece `_fetch_key_reports` cannot know and therefore does not do.

    Ordering note: alignment is established *before* generating keys, not after. An accessory
    with no alignment spans a huge index range, so `keys_between` would yield tens of
    thousands of keys for a single day. One `fetch_location` collapses that first. This is the
    opposite order to `_fetchReportsForAccessory`, and deliberately so: there, a probe was
    wasted work because the caller only wanted the latest report anyway; here the range is the
    whole point, so the probe pays for itself.
    """
    if not _alignBeforeRangedFetch(account, accessory, start, end):
        return []

    indexed_keys = _keysForRange(accessory, start, end)
    if not indexed_keys:
        return []

    index_by_key = {key: index for index, key in indexed_keys}
    chunks = _chunk(indexed_keys, _MAX_KEYS_PER_REQUEST)

    reports = []
    failed = 0
    for chunk in chunks:
        try:
            reports.extend(_fetchChunkAndAlign(account, accessory, chunk, index_by_key))
        except _ChunkFetchError:
            failed += 1

    if failed == len(chunks):
        # Every request failed, so we know nothing about this range. Returning an empty list
        # would be indistinguishable from "Apple has no reports here", and the history screen
        # would show a confident, wrong "0 reports" for the day. Raising lets the caller skip
        # the accessory instead of reporting an absence it cannot vouch for.
        raise RuntimeError(
            f"Every request in the ranged fetch failed ({failed} of {len(chunks)}); "
            f"refusing to report an empty range")

    if failed:
        print(f"WARNING: {failed} of {len(chunks)} requests failed; this range is incomplete")

    print(f"Ranged fetch searched {len(indexed_keys)} keys in {len(chunks)} request(s)")
    return reports


def _alignBeforeRangedFetch(account: AppleAccount, accessory: FindMyAccessory, start, end) -> bool:
    """
    Narrow the key index range before generating keys for it.

    An accessory with no alignment spans its whole life, so `keys_between` would yield tens of
    thousands of keys for a single day. One `fetch_location` collapses that.

    @return whether the ranged fetch is worth doing at all
    """
    width = _isAlignmentWide(accessory, start, end)
    if width <= _ALIGNMENT_PROBE_THRESHOLD_INDICES:
        return True

    print(f"Key search window is {width} indices wide; establishing alignment before "
          f"fetching the requested range.")
    account.fetch_location(accessory)

    narrowed = _isAlignmentWide(accessory, start, end)
    if narrowed >= width:
        # Nothing was found anywhere, so there is nothing to align to and the ranged sweep
        # would search the same empty range again.
        print("No reports found for this accessory; skipping the ranged fetch.")
        return False

    print(f"Key search window narrowed from {width} to {narrowed} indices.")
    return True


def _keysForRange(accessory: FindMyAccessory, start, end):
    """The `(index, key)` pairs covering a time window, capped at what one fetch may search."""
    try:
        indexed_keys = list(accessory.keys_between(start, end))
    except Exception:
        print(f"Could not generate keys for the requested range: {traceback.format_exc()}")
        return []

    if not indexed_keys:
        print("No keys fall inside the requested range; nothing to fetch.")
        return []

    max_keys = _MAX_KEYS_PER_REQUEST * _MAX_REQUESTS_PER_RANGE_FETCH
    if len(indexed_keys) > max_keys:
        # keys_between yields ascending by index, so the tail is the most recent part of the
        # window - the part most likely to still hold reports Apple has not dropped.
        print(f"Requested range needs {len(indexed_keys)} keys, more than the {max_keys} this "
              f"fetch is allowed; searching only the most recent part of the range.")
        indexed_keys = indexed_keys[-max_keys:]

    return indexed_keys


class _ChunkFetchError(Exception):
    """One request of a ranged fetch failed, after its retries."""


def _fetchChunkAndAlign(account: AppleAccount, accessory: FindMyAccessory, chunk, index_by_key):
    """
    Fetch one request's worth of keys and feed what comes back into the accessory's alignment.

    Retried once, because Apple's endpoint times out often enough to hit by hand. A day of
    history is a single request, so one `aiohttp` timeout meant the whole day came back empty
    and the screen said "0 reports" - which reads as "your tag was not seen", not "the network
    failed".

    The alignment update is the part `_fetch_key_reports` cannot do for us: it is handed plain
    keys and has no idea which index each belongs to. We do, because `keys_between` said so.

    @raise _ChunkFetchError if every attempt failed
    """
    keys = [key for _, key in chunk]
    found = None

    for attempt in range(1, _RANGE_FETCH_ATTEMPTS + 1):
        try:
            found = account.fetch_location_history(keys)
            break
        except Exception:
            print(f"Request {attempt} of {_RANGE_FETCH_ATTEMPTS} for a chunk of "
                  f"{len(keys)} keys failed: {traceback.format_exc()}")
            if attempt < _RANGE_FETCH_ATTEMPTS:
                time.sleep(_RANGE_FETCH_RETRY_DELAY_SECONDS)
    else:
        raise _ChunkFetchError(f"all {_RANGE_FETCH_ATTEMPTS} attempts failed for a chunk of "
                               f"{len(keys)} keys")

    reports = []
    for key, key_reports in (found or {}).items():
        for report in key_reports or []:
            reports.append(report)
            _updateAlignment(accessory, report, index_by_key.get(key))
    return reports


def _updateAlignment(accessory: FindMyAccessory, report, index):
    if index is None:
        return
    try:
        accessory.update_alignment(report.timestamp, index)
    except Exception:
        print(f"Could not update alignment: {traceback.format_exc()}")


def _serializeReports(reports):
    """
    Map FindMy 0.9.x LocationReport objects to the dict shape Java's mapResults expects.
    Note: `published_at` and `description` no longer exist on LocationReport in 0.9.x; we
    fall back to `timestamp` and an empty string respectively. Java's BeaconLocationReport
    can absorb that without changes.
    """
    items = []
    for report in sorted(reports):
        items.append({
            "publishedAt": _toUnixEpochMs(report.timestamp),
            "description": getattr(report, "description", "") or "",
            "timestamp": _toUnixEpochMs(report.timestamp),
            "confidence": report.confidence,
            "latitude": report.latitude,
            "longitude": report.longitude,
            "horizontalAccuracy": report.horizontal_accuracy,
            "status": report.status
        })
    return items


def _resultOrError(res: dict, failures: int, num_items: int) -> dict | None:
    """
    Decide whether a short result is an answer or a failure.

    Java's `mapResults` only raises when this module returns None; a dict missing a beacon
    reads as "that beacon has no reports". So swallowing every failure and returning a partial
    dict told the history screen, with complete confidence, that a day it had failed to fetch
    was a day the tag was not seen - no error state, and no Retry button, because as far as
    Java was concerned the call succeeded.

    A partial result is still worth returning: the accessories that did answer have fresh
    reports and, more importantly, updated alignment worth persisting.
    """
    if num_items and failures == num_items:
        print(f"Every accessory failed ({failures} of {num_items}); reporting an error rather "
              f"than an empty result.")
        return None

    if failures:
        print(f"WARNING: {failures} of {num_items} accessories failed; result is incomplete")

    return res


def getLastReports(
        account: AppleAccount,
        idToAccessoryData,
        hoursBack: int) -> dict | None:
    """
    Fetch the most recent reports for each beacon over the requested time window.

    `idToAccessoryData` is a List<AccessoryRequest> from Java where each element exposes
    getBeaconId() / getAccessoryJson() (the persisted FindMyAccessory JSON).

    Each entry in the result dict carries:
      - "reports": list of report dicts (same shape as before)
      - "updatedAccessoryJson": JSON string of the accessory AFTER fetch — Java must
        write this back to OwnedBeacon.accessory_json so the rolling key alignment
        survives across calls (this is the issue #30 fix).
    """
    try:
        res = {}

        num_items = idToAccessoryData.size()
        print(f"getLastReports: num_items={num_items}, hoursBack={hoursBack}")

        now_ms = int(datetime.now(tz=timezone.utc).timestamp() * 1000)
        start_ms = now_ms - (hoursBack * 60 * 60 * 1000)
        failures = 0

        for i in range(0, num_items):
            req = idToAccessoryData.get(i)
            beaconId = req.getBeaconId()
            accessoryJson = req.getAccessoryJson()

            print(f"Fetching report for {beaconId} for the last {hoursBack} hours...")

            airtag = FindMyAccessory.from_json(json.loads(accessoryJson))
            start_dt = datetime.fromtimestamp(start_ms / 1000, tz=timezone.utc)
            now_dt = datetime.fromtimestamp(now_ms / 1000, tz=timezone.utc)

            # Per-accessory isolation. One beacon failing used to abort the whole call,
            # which meant no beacon's updated alignment was persisted - so every later
            # fetch started from the same wide range again and never converged.
            try:
                reports = _fetchReportsForAccessory(account, airtag, start_dt, now_dt) or []
            except Exception:
                failures += 1
                print(f"Fetch failed for {beaconId}, continuing with the rest: "
                      f"{traceback.format_exc()}")
                continue

            print(f"Got {len(reports)} raw reports for {beaconId}")

            filtered = _filterReportsByTimeRange(reports, start_ms, now_ms)
            print(f"  -> {len(filtered)} reports after filtering to last {hoursBack}h")

            res[beaconId] = {
                "reports": _serializeReports(filtered),
                # Always written, even when there were no reports: the alignment may still
                # have moved, and persisting it is what stops the next fetch re-searching.
                "updatedAccessoryJson": json.dumps(airtag.to_json()),
            }

        return _resultOrError(res, failures, num_items)

    except Exception:
        err = traceback.format_exc()
        print(f"Failed to fetch all reports due to error: {err}")
        return None


def getReports(
        account: AppleAccount,
        idToAccessoryData,
        unixStartMs: int,
        unixEndMs: int) -> dict | None:
    """
    Time-range variant. Apple's network only retains ~7 days of history; ranges further
    back than that will return empty (the local Room cache is already the canonical store
    for older history via DailyHistoryFetchRecord).

    Same input/output shape as `getLastReports`.
    """
    try:
        res = {}

        num_items = idToAccessoryData.size()
        print(f"getReports: num_items={num_items}, range=[{unixStartMs}, {unixEndMs}]")
        failures = 0

        for i in range(0, num_items):
            req = idToAccessoryData.get(i)
            beaconId = req.getBeaconId()
            accessoryJson = req.getAccessoryJson()

            print(f"Fetching report for {beaconId} in time range {unixStartMs}-{unixEndMs}...")

            airtag = FindMyAccessory.from_json(json.loads(accessoryJson))
            start_dt = datetime.fromtimestamp(unixStartMs / 1000, tz=timezone.utc)
            end_dt = datetime.fromtimestamp(unixEndMs / 1000, tz=timezone.utc)

            try:
                reports = _fetchReportsInRange(account, airtag, start_dt, end_dt) or []
            except Exception:
                failures += 1
                print(f"Fetch failed for {beaconId}, continuing with the rest: "
                      f"{traceback.format_exc()}")
                continue
            print(f"Got {len(reports)} raw reports for {beaconId}")

            filtered = _filterReportsByTimeRange(reports, unixStartMs, unixEndMs)
            print(f"  -> {len(filtered)} reports after filtering to requested range")

            updated_accessory_json = json.dumps(airtag.to_json())

            res[beaconId] = {
                "reports": _serializeReports(filtered),
                "updatedAccessoryJson": updated_accessory_json,
            }

        return _resultOrError(res, failures, num_items)

    except Exception:
        err = traceback.format_exc()
        print(f"Failed to fetch all reports due to error: {err}")
        return None
