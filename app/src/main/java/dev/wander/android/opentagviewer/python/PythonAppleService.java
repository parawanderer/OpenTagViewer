package dev.wander.android.opentagviewer.python;

import android.util.Log;

import com.chaquo.python.Kwarg;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class PythonAppleService {
    private static final String TAG = PythonAppleService.class.getSimpleName();

    private static final String MODULE_MAIN = "main";

    private final PythonAppleAccount account;

    public static PythonAppleService INSTANCE = null;

    /**
     * Take this account as the signed-in one, closing whatever was here before.
     *
     * <p><b>Replacing used to leak the old one.</b> An {@code AppleAccount} owns an aiohttp
     * session, a connector and an event loop, and dropping the reference releases none of it -
     * Python's collector tries and fails, leaving two sockets open per discarded account. This
     * screen is rebuilt on any theme, language or map-provider change, so somebody toggling dark
     * mode a few times accumulates them. See issue #133.
     */
    public static PythonAppleService setup(PythonAppleAccount account) {
        final PythonAppleService previous = INSTANCE;

        INSTANCE = new PythonAppleService(account);

        if (previous != null && previous.account != account) {
            closeQuietly(previous.account);
        }

        return INSTANCE;
    }

    /**
     * Let go of the signed-in account, closing it.
     *
     * <p>For signing out, which is the clearest case of an account nobody will use again. Safe
     * to call when there is none.
     */
    public static void forget() {
        final PythonAppleService previous = INSTANCE;
        INSTANCE = null;

        if (previous != null) {
            closeQuietly(previous.account);
        }
    }

    /**
     * Close an account's session, and never fail for it.
     *
     * <p>Every caller is discarding the account regardless, so there is nothing useful to do
     * about a failure except say so - and this runs on paths that are already recovering from
     * something else, where a new exception would replace the original problem with this one.
     */
    private static void closeQuietly(final PythonAppleAccount account) {
        if (account == null) {
            return;
        }

        try {
            PythonLock.holding(() -> {
                final var module = Python.getInstance().getModule(MODULE_MAIN);
                module.callAttr("closeAccount", new Kwarg("account", account.getAccountObj()));
                return null;
            });
        } catch (final Exception e) {
            Log.w(TAG, "Could not close a discarded Apple account", e);
        }
    }

    public static PythonAppleService getInstance() {
        return INSTANCE;
    }

    /**
     * Whether a call into Python is currently in progress.
     *
     * <p>Delegates to {@link PythonLock}, which is where the lock moved when the iCloud flow
     * started driving the same event loop. Kept here because the periodic refresh asks this
     * service, and where the lock lives is not its business.
     */
    public static boolean isBusy() {
        return PythonLock.isBusy();
    }

    private PythonAppleService(PythonAppleAccount account) {
        this.account = account;
    }

    /**
     * The signed-in account, for the iCloud flow.
     *
     * <p><b>This one, not a second one restored from the same stored JSON.</b> One install is
     * one device to Apple (rule 11), and a parallel account would be a second HTTP session on a
     * second event loop presenting the same identity.
     */
    public PythonAppleAccount getAccount() {
        return this.account;
    }

    public Observable<FetchResult> getLastReports(final List<AccessoryRequest> requests, final int hoursToGoBack) {
        return Observable.fromCallable(() -> {
            if (requests.isEmpty()) {
                return emptyResult();
            }

            return PythonLock.holding(() -> {
                var py = Python.getInstance();
                var module = py.getModule(MODULE_MAIN);

                var returned = module.callAttr(
                        "getLastReports",
                        new Kwarg("account", this.account.getAccountObj()),
                        new Kwarg("idToAccessoryData", requests),
                        new Kwarg("hoursBack", hoursToGoBack)
                );

                if (returned == null) {
                    Log.e(TAG, "python call to getLastReports resulted in error (check python logs for details)");
                    throw new PythonAppleFindMyException("Error while retrieving last reports for account via python!");
                }

                return mapResults(returned);
            });
        }).subscribeOn(Schedulers.io());
    }

    public Observable<FetchResult> getReportsBetween(final List<AccessoryRequest> requests, final long startTimeUnixMS, final long endTimeUnixMS) {
        return Observable.fromCallable(() -> {
            if (requests.isEmpty()) {
                return emptyResult();
            }

            return PythonLock.holding(() -> {
                var py = Python.getInstance();
                var module = py.getModule(MODULE_MAIN);

                var returned = module.callAttr(
                        "getReports",
                        new Kwarg("account", this.account.getAccountObj()),
                        new Kwarg("idToAccessoryData", requests),
                        new Kwarg("unixStartMs", startTimeUnixMS),
                        new Kwarg("unixEndMs", endTimeUnixMS)
                );

                if (returned == null) {
                    Log.e(TAG, "python call to getReports resulted in error (check python logs for details)");
                    throw new PythonAppleFindMyException("Error while retrieving time ranged reports for account via python!");
                }

                return mapResults(returned);
            });
        }).subscribeOn(Schedulers.io());
    }

    private static FetchResult emptyResult() {
        return new FetchResult(Collections.emptyMap(), Collections.emptyMap(),
                java.util.Set.of(), java.util.Set.of());
    }

    /**
     * Python returns a dict shaped:
     *   { beaconId: { "reports": [reportDict, ...], "updatedAccessoryJson": "<json>" } }
     *
     * We split it back into two parallel maps so callers can persist the updated
     * accessory JSON via the OwnedBeaconDao (Phase 3) while consuming reports as before.
     */
    private static FetchResult mapResults(final PyObject locationReportsResult) {
        Map<String, List<BeaconLocationReport>> results = new HashMap<>();
        Map<String, String> updatedAccessoryJson = new HashMap<>();
        java.util.Set<String> exhaustedWideSearch = new java.util.HashSet<>();
        java.util.Set<String> wideSearch = new java.util.HashSet<>();

        var mapBeaconIdToResult = locationReportsResult.asMap();
        for (var key : mapBeaconIdToResult.keySet()) {
            var perBeacon = mapBeaconIdToResult.get(key).asMap();

            var locationReportList = perBeacon.get("reports").asList();
            var updatedAccessory = perBeacon.get("updatedAccessoryJson");

            // Absent on any answer from an older bridge, which reads as "not exhausted" - the
            // cautious way round, since the consequence of a wrong true is the app quietly
            // giving up on somebody's tag.
            final var exhausted = perBeacon.get("exhaustedWideSearch");
            if (exhausted != null && exhausted.toBoolean()) {
                exhaustedWideSearch.add(key.toString());
            }

            // Absent on an older bridge, which reads as "not expensive" - so nothing is counted
            // against a tag on the strength of a field that was not there.
            final var wide = perBeacon.get("wideSearch");
            if (wide != null && wide.toBoolean()) {
                wideSearch.add(key.toString());
            }

            List<BeaconLocationReport> reports = new LinkedList<>();
            final int numReports = locationReportList.size();
            for (int i = 0; i < numReports; ++i) {
                var locationReportMap = locationReportList.get(i).asMap();

                final long publishedAt = locationReportMap.get("publishedAt").toLong();
                final String description = locationReportMap.get("description").toString();
                final long timestamp = locationReportMap.get("timestamp").toLong();
                final long confidence = locationReportMap.get("confidence").toLong();
                final double latitude = locationReportMap.get("latitude").toDouble();
                final double longitude = locationReportMap.get("longitude").toDouble();
                final long horizontalAccuracy = locationReportMap.get("horizontalAccuracy").toLong();
                final long status = locationReportMap.get("status").toLong();

                var locationReport = BeaconLocationReport.builder()
                        .publishedAt(publishedAt)
                        .description(description)
                        .timestamp(timestamp)
                        .confidence(confidence)
                        .latitude(latitude)
                        .longitude(longitude)
                        .horizontalAccuracy(horizontalAccuracy)
                        .status(status)
                        .build();

                reports.add(locationReport);
            }
            reports.sort(Comparator.comparingLong(BeaconLocationReport::getTimestamp));

            String beaconIdStr = key.toString();
            results.put(beaconIdStr, reports);
            if (updatedAccessory != null) {
                updatedAccessoryJson.put(beaconIdStr, updatedAccessory.toString());
            }
        }

        return new FetchResult(results, updatedAccessoryJson, exhaustedWideSearch, wideSearch);
    }
}
