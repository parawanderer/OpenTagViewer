package dev.wander.android.opentagviewer.db.repo;

import android.util.Log;

import java.util.ArrayList;
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
import dev.wander.android.opentagviewer.python.AccessoryRequest;
import dev.wander.android.opentagviewer.python.ChaquopyPlistToAccessoryJsonConverter;
import dev.wander.android.opentagviewer.python.FetchResult;
import dev.wander.android.opentagviewer.python.PlistToAccessoryJsonConverter;
import dev.wander.android.opentagviewer.util.BeaconLocationReportHasher;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.NonNull;

public class BeaconRepository {
    private final static String TAG = BeaconRepository.class.getSimpleName();
    private final OpenTagViewerDatabase db;
    private final PlistToAccessoryJsonConverter accessoryJsonConverter;

    public BeaconRepository(OpenTagViewerDatabase db) {
        this(db, new ChaquopyPlistToAccessoryJsonConverter());
    }

    /**
     * Injectable converter, so the lazy accessory_json backfill can be tested without a
     * running Python runtime.
     */
    public BeaconRepository(OpenTagViewerDatabase db, PlistToAccessoryJsonConverter accessoryJsonConverter) {
        this.db = db;
        this.accessoryJsonConverter = accessoryJsonConverter;
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

    /**
     * One beacon's fallback entry, tolerating the tag having no plist.
     *
     * <p>Exists because {@code Map.of} rejects a null value with a
     * {@link NullPointerException}, and the obvious guard - skipping the entry - is worse than
     * the crash: the map is what decides which tags get fetched, so an omitted key is a tag
     * that silently never updates.
     */
    public static Map<String, String> plistFallback(final String beaconId, final String plist) {
        final Map<String, String> fallback = new HashMap<>();
        fallback.put(beaconId, plist);
        return fallback;
    }

    /**
     * The same, for a whole list of stored rows.
     *
     * <p><b>This exists because fixing the three call sites I found was not the same as fixing
     * all of them.</b> A fourth - the import path - kept {@code Collectors.toMap} and crashed
     * with a {@link NullPointerException} the moment somebody imported a self-generated tag,
     * which is the only kind that reaches it with no plist. One helper is harder to miss than a
     * rule about which collectors happen to be null-safe.
     */
    public static Map<String, String> plistFallbacks(final List<OwnedBeacon> beacons) {
        final Map<String, String> fallbacks = new HashMap<>();
        beacons.forEach(beacon -> fallbacks.put(beacon.id, beacon.content));
        return fallbacks;
    }

    /**
     * Build the FindMy 0.9.x fetch input for the given beacons. For each beacon we use
     * the persisted {@code accessory_json} if present, otherwise lazily backfill it
     * from the legacy XML plist via {@code main.py:convertPlistToJson} (and persist
     * the result so the next call is cheap).
     *
     * Beacons whose plist cannot be converted are dropped — passing them to Python
     * would just throw inside FindMyAccessory.from_json. The caller will see a
     * shorter fetch result than it asked for, which is preferable to taking down
     * the whole batch.
     *
     * @param beaconIdToPlistFallback beacon ID → legacy XML plist (used only when the
     *                                row's accessory_json is still NULL, e.g. for rows
     *                                imported under FindMy 0.7.6).
     *                                <p><b>A null value is meaningful and must be allowed
     *                                through.</b> A self-generated tag has no plist and never
     *                                needs one - its accessory_json is written at import and
     *                                the fallback branch below is never reached for it. So the
     *                                key must still be present, or the tag is simply never
     *                                fetched: build these maps with {@link #plistFallback} or a
     *                                {@link java.util.HashMap}, because {@code Map.of} and
     *                                {@code Collectors.toMap} both throw on a null value.
     */
    public Observable<List<AccessoryRequest>> toAccessoryRequests(Map<String, String> beaconIdToPlistFallback) {
        return Observable.fromCallable(() -> {
            if (beaconIdToPlistFallback.isEmpty()) {
                return java.util.Collections.<AccessoryRequest>emptyList();
            }

            final var dao = db.ownedBeaconDao();

            List<AccessoryRequest> out = new ArrayList<>(beaconIdToPlistFallback.size());
            for (var entry : beaconIdToPlistFallback.entrySet()) {
                final String beaconId = entry.getKey();

                final OwnedBeacon row = dao.getById(beaconId);
                String accessoryJson = row == null ? null : row.accessoryJson;

                if (accessoryJson == null) {
                    // Lazy backfill: rows imported under FindMy 0.7.6 have no accessory_json,
                    // and neither do rows whose import-time conversion failed. Prefer the
                    // plist retained on the row over the caller's copy - the row is the
                    // source of truth, and the caller's map may be stale.
                    final String plist = (row != null && row.content != null)
                            ? row.content
                            : entry.getValue();

                    final String alignmentPlist = row == null ? null : row.alignmentPlist;
                    accessoryJson = this.accessoryJsonConverter.convert(plist, alignmentPlist);
                    if (accessoryJson != null) {
                        dao.updateAccessoryJson(beaconId, accessoryJson);
                        Log.d(TAG, "Lazy-backfilled accessory_json for beaconId=" + beaconId);
                    }
                }

                if (accessoryJson != null) {
                    out.add(new AccessoryRequest(beaconId, accessoryJson));
                } else {
                    // Dropped rather than passed on: FindMyAccessory.from_json would throw
                    // and take down the whole batch. A short result is better than none.
                    Log.w(TAG, "Skipping beaconId=" + beaconId + " - no accessory_json available");
                }
            }
            return out;
        }).subscribeOn(Schedulers.io());
    }

    /**
     * Persist a {@link FetchResult} from {@code PythonAppleService}: location reports
     * go to the cache (delegating to {@link #storeToLocationCache}), and the freshly
     * serialized {@code accessory_json} per beacon (which now carries the rolling-key
     * alignment from FindMy 0.9.x — the issue #30 fix) is written back to the
     * {@code OwnedBeacons} table.
     */
    public Observable<Map<String, List<BeaconLocationReport>>> storeFetchResult(FetchResult fetchResult) {
        return Observable.fromCallable(() -> {
            final var dao = db.ownedBeaconDao();
            for (var entry : fetchResult.getUpdatedAccessoryJson().entrySet()) {
                if (entry.getValue() != null) {
                    dao.updateAccessoryJson(entry.getKey(), entry.getValue());
                }
            }
            return fetchResult.getReports();
        }).subscribeOn(Schedulers.io())
        .flatMap(this::storeToLocationCache);
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
