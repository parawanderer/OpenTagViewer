package dev.wander.android.airtagforall.python;

import android.util.Log;
import android.util.Pair;

import com.chaquo.python.Kwarg;
import com.chaquo.python.Python;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.wander.android.airtagforall.data.model.BeaconLocationReport;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class PythonAppleService {
    private static final String TAG = PythonAppleService.class.getSimpleName();

    private static final String MODULE_MAIN = "main";

    private final PythonAppleAccount account;

    public PythonAppleService(PythonAppleAccount account) {
        this.account = account;
    }

    public Observable<Map<String, List<BeaconLocationReport>>> getLastReports(final Map<String, String> beaconIdToPList, final int hoursToGoBack) {
        return Observable.fromCallable(() -> {
            if (beaconIdToPList.isEmpty()) {
                // if there's no items being requested, just return none immediately:
                return Collections.<String, List<BeaconLocationReport>>emptyMap();
            }

            var py = Python.getInstance();
            var module = py.getModule(MODULE_MAIN);

            var asListOfPairs = beaconIdToPList.entrySet().stream()
                    .map(kvp -> Pair.create(kvp.getKey(), kvp.getValue()))
                    .collect(Collectors.toList());

            var returned = module.callAttr(
                    "getLastReports",
                    new Kwarg("account", this.account.getAccountObj()),
                    new Kwarg("idToPList", asListOfPairs),
                    new Kwarg("hoursBack", hoursToGoBack)
            );

            // extract to result list...
            if (returned == null) {
                Log.e(TAG, "python call to getAccount resulted in error (check python logs for details)");
                throw new PythonAppleFindMyException("Error while retrieving last reports for account via python!");
            }

            Map<String, List<BeaconLocationReport>> results = new HashMap<>();

            var mapBeaconIdToResult = returned.asMap();
            for (var key : mapBeaconIdToResult.keySet()) {
                var locationReportList = mapBeaconIdToResult.get(key).asList();

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

                results.put(key.toString(), reports);
            }

            return results;
        }).subscribeOn(Schedulers.io());
    }
}
