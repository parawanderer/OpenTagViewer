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
import java.util.concurrent.locks.ReentrantLock;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class PythonAppleService {
    private static final String TAG = PythonAppleService.class.getSimpleName();

    private static final String MODULE_MAIN = "main";

    private final PythonAppleAccount account;

    public static PythonAppleService INSTANCE = null;

    public static PythonAppleService setup(PythonAppleAccount account) {
        INSTANCE = new PythonAppleService(account);
        return INSTANCE;
    }

    public static PythonAppleService getInstance() {
        return INSTANCE;
    }

    /**
     * Serialises all calls into Python.
     * <br>
     * FindMy.py's synchronous AppleAccount wraps an async one and drives it with a single
     * asyncio event loop. RxJava schedules our fetches on a thread pool, and the periodic
     * refresh in MapsActivity fires every 60 seconds regardless of whether the previous
     * fetch has finished. Two fetches overlapping means two threads calling
     * run_until_complete on the same loop, which fails with
     * "RuntimeError: This event loop is already running" and then keeps failing.
     * <br>
     * A fetch that takes longer than the refresh interval is entirely normal for an
     * accessory with no alignment yet, so this is not a rare race.
     * <br>
     * Serialising alone is not enough: the periodic refresh would still queue up behind a
     * slow fetch, one entry per minute, and then fire the whole stale backlog at once when
     * it finally drained. Callers on the periodic path should check {@link #isBusy()} and
     * skip their turn instead - a refresh that is minutes late has no value.
     */
    private static final ReentrantLock PYTHON_LOCK = new ReentrantLock();

    /**
     * Whether a call into Python is currently in progress.
     * <br>
     * Advisory only. A caller that acts on this can still be beaten to the lock, which is
     * harmless: it just waits, exactly as it did before.
     */
    public static boolean isBusy() {
        return PYTHON_LOCK.isLocked();
    }

    private PythonAppleService(PythonAppleAccount account) {
        this.account = account;
    }

    public Observable<FetchResult> getLastReports(final List<AccessoryRequest> requests, final int hoursToGoBack) {
        return Observable.fromCallable(() -> {
            if (requests.isEmpty()) {
                return emptyResult();
            }

            PYTHON_LOCK.lock();
            try {
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
            } finally {
                PYTHON_LOCK.unlock();
            }
        }).subscribeOn(Schedulers.io());
    }

    public Observable<FetchResult> getReportsBetween(final List<AccessoryRequest> requests, final long startTimeUnixMS, final long endTimeUnixMS) {
        return Observable.fromCallable(() -> {
            if (requests.isEmpty()) {
                return emptyResult();
            }

            PYTHON_LOCK.lock();
            try {
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
            } finally {
                PYTHON_LOCK.unlock();
            }
        }).subscribeOn(Schedulers.io());
    }

    private static FetchResult emptyResult() {
        return new FetchResult(Collections.emptyMap(), Collections.emptyMap());
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

        var mapBeaconIdToResult = locationReportsResult.asMap();
        for (var key : mapBeaconIdToResult.keySet()) {
            var perBeacon = mapBeaconIdToResult.get(key).asMap();

            var locationReportList = perBeacon.get("reports").asList();
            var updatedAccessory = perBeacon.get("updatedAccessoryJson");

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

        return new FetchResult(results, updatedAccessoryJson);
    }
}
