package dev.wander.android.airtagforall.db.repo;

import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.wander.android.airtagforall.data.model.BeaconLocationReport;
import dev.wander.android.airtagforall.db.repo.model.BeaconData;
import dev.wander.android.airtagforall.db.repo.model.ImportData;
import dev.wander.android.airtagforall.db.room.AirTag4AllDatabase;
import dev.wander.android.airtagforall.db.room.entity.BeaconNamingRecord;
import dev.wander.android.airtagforall.db.room.entity.LocationReport;
import dev.wander.android.airtagforall.db.room.entity.OwnedBeacon;
import dev.wander.android.airtagforall.db.util.BeaconCombinerUtil;
import dev.wander.android.airtagforall.util.BeaconLocationReportHasher;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.NonNull;

public class BeaconRepository {
    private final static String TAG = BeaconRepository.class.getSimpleName();
    private final AirTag4AllDatabase db;

    public BeaconRepository(AirTag4AllDatabase db) {
        this.db = db;
    }

    /**
     * Insert all the data for a single import action.
     * <br>
     * This will update data for existing beacons by beaconid and link them to the latest import
     */
    public Observable<ImportData> addNewImport(@NonNull ImportData importData) throws RepoQueryException {
        return Observable.fromCallable(() -> {
            try {
                long insertionId = db.importDao().insert(importData.getAnImport());

                var ownedBeacons = importData.getOwnedBeacons();
                ownedBeacons.forEach(b -> b.importId = insertionId);
                db.ownedBeaconDao().insertAll(ownedBeacons.toArray(new OwnedBeacon[0]));

                var beaconNamingRecords = importData.getBeaconNamingRecords();
                beaconNamingRecords.forEach(b -> b.importId = insertionId);
                db.beaconNamingRecordDao().insertAll(beaconNamingRecords.toArray(new BeaconNamingRecord[0]));

                return importData;
            } catch (Exception e) {
                Log.e(TAG, "Error occurred when trying to insert all data for new import", e);
                throw new RepoQueryException(e);
            }
        }).subscribeOn(Schedulers.io());
    }

    public Observable<List<BeaconData>> getAllBeacons() {
        return Observable.fromCallable(() -> {
            try {
                List<OwnedBeacon> ownedBeacons = db.ownedBeaconDao().getAll();
                List<BeaconNamingRecord> beaconNamingRecords = db.beaconNamingRecordDao().getAll();

                return BeaconCombinerUtil.combine(ownedBeacons, beaconNamingRecords);

            } catch (Exception e) {
                Log.e(TAG, "Error occurred when trying to retrieve all beacons from repository", e);
                throw new RepoQueryException(e);
            }
        }).subscribeOn(Schedulers.io());
    }

    public Observable<BeaconData> getById(final String beaconId) {
        return Observable.fromCallable(() -> {
            OwnedBeacon ownedBeacon = db.ownedBeaconDao().getById(beaconId);
            BeaconNamingRecord namingRecord = db.beaconNamingRecordDao().getByBeaconId(beaconId);

            return new BeaconData(
                    ownedBeacon.id,
                    ownedBeacon,
                    namingRecord
            );
        }).subscribeOn(Schedulers.io());
    }

    public Observable<Map<String, List<BeaconLocationReport>>> storeToLocationCache(Map<String, List<BeaconLocationReport>> reportsForBeaconId) {
        return Observable.fromCallable(() -> {
            if (reportsForBeaconId.isEmpty()) {
                // If it's empty then there's nothing to do. So just return right away.
                return reportsForBeaconId;
            }

            final long now = System.currentTimeMillis();

            // flat map them all:
            LocationReport[] allRecords = reportsForBeaconId.entrySet().stream()
                            .flatMap(kvp -> kvp.getValue().stream().map(locationReport -> LocationReport.builder()
                                    .hashId(BeaconLocationReportHasher.getSha256HashFor(kvp.getKey(), locationReport))
                                    .beaconId(kvp.getKey())
                                    .publishedAt(locationReport.getPublishedAt())
                                    .description(locationReport.getDescription())
                                    .timestamp(locationReport.getTimestamp())
                                    .confidence(locationReport.getConfidence())
                                    .latitude(locationReport.getLatitude())
                                    .longitude(locationReport.getLongitude())
                                    .horizontalAccuracy(locationReport.getHorizontalAccuracy())
                                    .status(locationReport.getStatus())
                                    .lastUpdate(now)
                                    .build()
                            ))
                            .toArray(LocationReport[]::new);

            db.locationReportDao().insertAll(allRecords);

            return reportsForBeaconId;
        }).subscribeOn(Schedulers.io());
    }

    public Observable<Map<String, BeaconLocationReport>> getLastLocationsForAll() {
        return Observable.fromCallable(() -> {

            var locationReports = db.locationReportDao().getLastForAllBeacons();

            Map<String, BeaconLocationReport> result = new HashMap<>();
            for (var locationReport: locationReports) {
                result.put(
                    locationReport.beaconId,
                    BeaconLocationReport.builder()
                            .publishedAt(locationReport.publishedAt)
                            .description(locationReport.description)
                            .timestamp(locationReport.timestamp)
                            .confidence(locationReport.confidence)
                            .latitude(locationReport.latitude)
                            .longitude(locationReport.longitude)
                            .horizontalAccuracy(locationReport.horizontalAccuracy)
                            .status(locationReport.status)
                            .build()
                );
            }

            return result;
        }).subscribeOn(Schedulers.io());
    }

    public Observable<List<BeaconLocationReport>> getLocationsFor(final String beaconId, final long unixStartTimeMS, final long unixEndTimeMS) {
        return Observable.fromCallable(() -> {
            List<LocationReport> reports = db.locationReportDao().getInTimeRange(beaconId, unixStartTimeMS, unixEndTimeMS);
            return reports.stream().map(locationReport -> BeaconLocationReport.builder()
                    .publishedAt(locationReport.publishedAt)
                    .description(locationReport.description)
                    .timestamp(locationReport.timestamp)
                    .confidence(locationReport.confidence)
                    .latitude(locationReport.latitude)
                    .longitude(locationReport.longitude)
                    .horizontalAccuracy(locationReport.horizontalAccuracy)
                    .status(locationReport.status)
                    .build())
                .collect(Collectors.toList());
        }).subscribeOn(Schedulers.io());
    }
}
