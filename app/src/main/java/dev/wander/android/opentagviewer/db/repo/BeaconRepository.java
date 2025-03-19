package dev.wander.android.opentagviewer.db.repo;

import android.util.Log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.db.repo.model.BeaconData;
import dev.wander.android.opentagviewer.db.repo.model.ImportData;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.DailyHistoryFetchRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.db.room.entity.UserBeaconOptions;
import dev.wander.android.opentagviewer.db.util.BeaconCombinerUtil;
import dev.wander.android.opentagviewer.util.BeaconLocationReportHasher;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.NonNull;

public class BeaconRepository {
    private final static String TAG = BeaconRepository.class.getSimpleName();
    private final OpenTagViewerDatabase db;

    public BeaconRepository(OpenTagViewerDatabase db) {
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

    public Observable<Optional<Import>> getImportById(final long importId) {
        return Observable.fromCallable(() -> {
            try {
                var res = db.importDao().getById(importId);
                return Optional.ofNullable(res);
            } catch (Exception e) {
                Log.e(TAG, "Failed to get import by id", e);
                throw new RepoQueryException(e);
            }
        }).subscribeOn(Schedulers.io());
    }

    public Observable<List<BeaconData>> getAllBeacons() {
        return Observable.fromCallable(() -> {
            try {
                List<OwnedBeacon> ownedBeacons = db.ownedBeaconDao().getAll();
                List<BeaconNamingRecord> beaconNamingRecords = db.beaconNamingRecordDao().getAll();
                List<UserBeaconOptions> userBeaconOptions = db.userBeaconOptionsDao().getAll();

                return BeaconCombinerUtil.combine(ownedBeacons, beaconNamingRecords, userBeaconOptions);

            } catch (Exception e) {
                Log.e(TAG, "Error occurred when trying to retrieve all beacons from repository", e);
                throw new RepoQueryException(e);
            }
        }).subscribeOn(Schedulers.io());
    }

    public Completable storeUserBeaconOptions(UserBeaconOptions userOptions) {
        return Completable.fromRunnable(() -> {
            try {
                this.db.userBeaconOptionsDao().insertAll(userOptions);
            } catch (Exception e) {
                Log.e(TAG, "Error occurred when trying to insert user options for beaconId="+userOptions.beaconId, e);
                throw new RepoQueryException(e);
            }
        }).subscribeOn(Schedulers.io());
    }

    public Observable<BeaconData> getById(final String beaconId) {
        return Observable.fromCallable(() -> {
            OwnedBeacon ownedBeacon = db.ownedBeaconDao().getById(beaconId);

            if (ownedBeacon == null) {
                return null;
            }

            BeaconNamingRecord namingRecord = db.beaconNamingRecordDao().getByBeaconId(beaconId);
            UserBeaconOptions userBeaconOptions = db.userBeaconOptionsDao().getById(beaconId);

            return new BeaconData(
                    ownedBeacon.id,
                    ownedBeacon,
                    namingRecord,
                    userBeaconOptions
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

    public Observable<List<DailyHistoryFetchRecord>> storeHistoryRecords(DailyHistoryFetchRecord... records) {
        return Observable.fromCallable(() -> {
            final var now = System.currentTimeMillis();

            for (var record: records) {
                record.lastUpdate = now;
            }

            db.dailyHistoryFetchRecordDao().insertAll(records);

            return Arrays.stream(records).collect(Collectors.toList());
        }).subscribeOn(Schedulers.io());
    }

    public Completable markBeaconAsRemoved(final String beaconId) {
        return Completable.fromRunnable(() -> {

            db.beaconNamingRecordDao().setRemoved(beaconId);
            db.ownedBeaconDao().setRemoved(beaconId);

        }).subscribeOn(Schedulers.io());
    }

    public Observable<Optional<DailyHistoryFetchRecord>> getInsertionHistoryItem(final String beaconId, final long startOfDayTimestampMS) {
        return Observable.fromCallable(() -> {
            DailyHistoryFetchRecord record = db.dailyHistoryFetchRecordDao().getIfExists(beaconId, startOfDayTimestampMS);
            return Optional.ofNullable(record);
        }).subscribeOn(Schedulers.io());
    }
}
