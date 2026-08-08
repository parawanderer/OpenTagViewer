from enum import Enum
import json
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
from findmy.reports.twofactor import (
    SyncSecondFactorMethod
)


class TwoFactorMethods(Enum):
    UNKNOWN = 0
    TRUSTED_DEVICE = 1
    PHONE = 2


def _toUnixEpochMs(dt: datetime) -> int:
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


def decodeBeaconNamingRecordCloudKitMetadata(cleanedBase64: str) -> dict:
    """
    Extract some extra information from within the plist file `cloudKitMetadata` node
    (that is followed by a `<data>` element containing base64)

    Note that `cleanedBase64` must not contain line breaks `\\n`, or tabs `\\t`
    or other whitespace characters that get introduced by some plist parsers


    ### More info:

    The most popular java plist parser, the google java [dd-plist](https://mvnrepository.com/artifact/com.googlecode.plist/dd-plist) library,
    [does not currently support the `NSKeyedArchiver` plist format](https://github.com/3breadt/dd-plist/issues/70) (at the time of writing).

    However somebody has managed to create a parser in python: https://github.com/avibrazil/NSKeyedUnArchiver

    So because there's some interesting data to be extracted from this `NSKeyedArchiver`-encoded data,
    we will extract it using python via this nice library and pass the needed data back to Java

    See:
    - https://www.mac4n6.com/blog/2016/1/1/manual-analysis-of-nskeyedarchiver-formatted-plist-files-a-review-of-the-new-os-x-1011-recent-items
    - https://github.com/malmeloo/FindMy.py/issues/31#issuecomment-2628072362
    - https://github.com/3breadt/dd-plist/issues/70

    """
    try:
        data = base64.b64decode(cleanedBase64)
        d_dict = NSKeyedUnArchiver.unserializeNSKeyedArchiver(data)

        # This is actually a pretty large object, but very little of the data seems useful to our app

        RecordCtime: datetime = d_dict.get("RecordCtime", None)
        RecordMtime: datetime = d_dict.get("RecordMtime", None)
        ModifiedByDevice: str = d_dict.get("ModifiedByDevice", None)

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


def _convertToJavaDictWrapper(method: SyncSecondFactorMethod):
    return_obj = {
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


def loginSync(email: str, password: str, anisetteServerUrl: str) -> dict:
    try:
        anisette = RemoteAnisetteProvider(anisetteServerUrl)
        acc = AppleAccount(anisette)

        state = acc.login(email, password)

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


def getAccount(serializedAccountData: str, anisetteServerUrl: str = None) -> AppleAccount:
    """
    Restore an AppleAccount via FindMy 0.9.x's `from_json`. The anisette provider is rebuilt
    from the embedded state inside the JSON, so `anisetteServerUrl` is unused here. We accept
    the parameter for now to keep the existing Java callsite compiling; it will be dropped
    in Phase 2 when the Java bridge is updated.
    """
    try:
        data = json.loads(serializedAccountData)

        acc = AppleAccount.from_json(data)

        print(f"Login State: {acc.login_state}")

        return acc
    except Exception:
        err = traceback.format_exc()
        print(f"Failed to restore account from string: {err}")
        return None


def convertPlistToJson(plistXmlString: str) -> str:
    """
    One-shot conversion from the legacy plist XML representation (still stored in
    OwnedBeacon.content) to the JSON form that FindMy 0.9.x expects.

    Used in two places (called from Java):
    - During .zip import: convert once and store alongside the raw plist
    - As a lazy backfill: when reading an OwnedBeacon row that predates the upgrade

    Returns None on failure so Java can decide how to recover.
    """
    try:
        fp = BytesIO(plistXmlString.encode('utf-8'))
        accessory = FindMyAccessory.from_plist(fp)
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


def getLastReports(
        account: AppleAccount,
        idToAccessoryData,
        hoursBack: int) -> dict:
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

        for i in range(0, num_items):
            req = idToAccessoryData.get(i)
            beaconId = req.getBeaconId()
            accessoryJson = req.getAccessoryJson()

            print(f"Fetching report for {beaconId} for the last {hoursBack} hours...")

            airtag = FindMyAccessory.from_json(json.loads(accessoryJson))

            reports = account.fetch_location_history(airtag)
            if reports is None:
                reports = []
            print(f"Got {len(reports)} raw reports for {beaconId}")

            filtered = _filterReportsByTimeRange(reports, start_ms, now_ms)
            print(f"  -> {len(filtered)} reports after filtering to last {hoursBack}h")

            updated_accessory_json = json.dumps(airtag.to_json())

            res[beaconId] = {
                "reports": _serializeReports(filtered),
                "updatedAccessoryJson": updated_accessory_json,
            }

        return res

    except Exception:
        err = traceback.format_exc()
        print(f"Failed to fetch all reports due to error: {err}")
        return None


def getReports(
        account: AppleAccount,
        idToAccessoryData,
        unixStartMs: int,
        unixEndMs: int) -> dict:
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

        for i in range(0, num_items):
            req = idToAccessoryData.get(i)
            beaconId = req.getBeaconId()
            accessoryJson = req.getAccessoryJson()

            print(f"Fetching report for {beaconId} in time range {unixStartMs}-{unixEndMs}...")

            airtag = FindMyAccessory.from_json(json.loads(accessoryJson))

            reports = account.fetch_location_history(airtag)
            if reports is None:
                reports = []
            print(f"Got {len(reports)} raw reports for {beaconId}")

            filtered = _filterReportsByTimeRange(reports, unixStartMs, unixEndMs)
            print(f"  -> {len(filtered)} reports after filtering to requested range")

            updated_accessory_json = json.dumps(airtag.to_json())

            res[beaconId] = {
                "reports": _serializeReports(filtered),
                "updatedAccessoryJson": updated_accessory_json,
            }

        return res

    except Exception:
        err = traceback.format_exc()
        print(f"Failed to fetch all reports due to error: {err}")
        return None
