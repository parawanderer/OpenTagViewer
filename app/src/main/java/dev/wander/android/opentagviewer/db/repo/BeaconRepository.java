package dev.wander.android.opentagviewer.db.repo;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
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
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.ChaquopyPlistToAccessoryJsonConverter;
import dev.wander.android.opentagviewer.python.FetchResult;
import dev.wander.android.opentagviewer.python.icloud.AccessoryRecords;
import dev.wander.android.opentagviewer.util.parse.NamingRecordEditor;
import dev.wander.android.opentagviewer.python.PlistToAccessoryJsonConverter;
import dev.wander.android.opentagviewer.util.BeaconLocationReportHasher;
import dev.wander.android.opentagviewer.util.parse.KeyAlignmentPlist;
import dev.wander.android.opentagviewer.util.rx.ScanOrder;
import dev.wander.android.opentagviewer.util.rx.WideScanBackoff;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.NonNull;

public class BeaconRepository {
    private final static String TAG = BeaconRepository.class.getSimpleName();
    private final OpenTagViewerDatabase db;
    private final PlistToAccessoryJsonConverter accessoryJsonConverter;

    /**
     * The source of the scheduled fetch's shuffle.
     *
     * <p>Held rather than made per call so a test can seed it, and so the sequence continues
     * across refreshes instead of restarting - a fresh Random each time is a fresh chance to
     * draw the same order.
     */
    private final java.util.Random shuffle = new java.util.Random();

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

                // **Insert what is new, update what is held.** Re-importing is ordinary - a newer
                // export carries a key alignment record an older one lacked - and a re-insert
                // would delete the existing row, cascading into the user's custom names, the
                // tag's location history and the record of which days have been fetched. See
                // OwnedBeaconDao#insertAll.
                db.ownedBeaconDao().insertIfNew(ownedBeacons.toArray(new OwnedBeacon[0]));

                for (final OwnedBeacon beacon : ownedBeacons) {
                    db.ownedBeaconDao().refreshFromImport(
                            beacon.id,
                            beacon.content,
                            beacon.alignmentPlist,
                            beacon.accessoryJson,
                            beacon.version,
                            insertionId);
                }

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

    /**
     * Bring the beacons held for the Apple account into line with what it actually holds.
     *
     * <p><b>A refresh, not an import.</b> These rows are a cache of somebody's account: what is
     * on it is written, and what has left it is retired. That is the whole reason
     * {@code from_account} exists - a file-imported beacon is the only copy anyone has, so this
     * must never touch one, and the DAO scopes every write accordingly.
     *
     * <p>Retired rather than deleted, so a tag that leaves the account does not take its location
     * history with it on the way out.
     *
     * <p>Rows are written with {@code is_removed = 0}, which matters for a tag that left the
     * account and later came back: without it the row would be restored still marked as gone.
     *
     * @return the ids now held for the account.
     */
    public Observable<List<String>> refreshAccountBeacons(
            @NonNull final List<AccessoryRecords> fromAccount) {
        return Observable.fromCallable(() -> {
            try {
                final List<String> ids = new ArrayList<>();
                final List<OwnedBeacon> beacons = new ArrayList<>();
                final List<BeaconNamingRecord> namingRecords = new ArrayList<>();

                for (final AccessoryRecords record : fromAccount) {
                    ids.add(record.getBeaconId());

                    beacons.add(OwnedBeacon.builder()
                            .id(record.getBeaconId())
                            .importId(null)
                            .version(ACCOUNT_SOURCED_VERSION)
                            .content(record.getOwnedBeaconPlist())
                            .alignmentPlist(record.getKeyAlignmentPlist())
                            // Converted eagerly, exactly as a zip import does. Null on failure is
                            // not fatal: the lazy backfill on first fetch handles it.
                            .accessoryJson(this.accessoryJsonConverter.convert(
                                    record.getOwnedBeaconPlist(), record.getKeyAlignmentPlist()))
                            .fromAccount(true)
                            .isRemoved(false)
                            .build());

                    if (record.getNamingRecordPlist() != null) {
                        namingRecords.add(BeaconNamingRecord.builder()
                                .id(record.getBeaconId())
                                .importId(null)
                                .version(ACCOUNT_SOURCED_VERSION)
                                .content(record.getNamingRecordPlist())
                                .build());
                    }
                }

                // **Insert the new ones, update the rest. Never re-insert.** A re-insert is
                // `INSERT OR REPLACE`, which deletes the row it is replacing and cascades that
                // delete into UserBeaconOptions and LocationReport - so an ordinary background
                // read would silently take the user's custom names and the tag's whole location
                // history with it. See OwnedBeaconDao#insertAll.
                if (!beacons.isEmpty()) {
                    db.ownedBeaconDao().insertIfNew(beacons.toArray(new OwnedBeacon[0]));

                    for (final OwnedBeacon beacon : beacons) {
                        db.ownedBeaconDao().refreshFromAccount(
                                beacon.id,
                                beacon.content,
                                beacon.alignmentPlist,
                                beacon.accessoryJson,
                                beacon.version);
                    }
                }
                if (!namingRecords.isEmpty()) {
                    db.beaconNamingRecordDao()
                            .insertAll(namingRecords.toArray(new BeaconNamingRecord[0]));
                }

                // `NOT IN ()` is not valid SQL, so an account that now holds nothing needs the
                // other query rather than a list nobody can match against.
                final int retired = ids.isEmpty()
                        ? db.ownedBeaconDao().retireEveryAccountBeacon()
                        : db.ownedBeaconDao().retireAccountBeaconsMissingFrom(ids);

                Log.i(TAG, "Refreshed from the Apple account: " + ids.size()
                        + " held, " + retired + " retired");

                return ids;
            } catch (Exception e) {
                Log.e(TAG, "Error occurred while refreshing the beacons held for the account", e);
                throw new RepoQueryException(e);
            }
        }).subscribeOn(Schedulers.io());
    }

    /**
     * Write a name and emoji that iCloud has already accepted into the stored naming record.
     *
     * <p><b>Only after the account has taken the change</b>, never before and never instead. This
     * is what makes the tag's real name change rather than acquiring a nickname over the top of
     * it - see {@link dev.wander.android.opentagviewer.util.parse.NamingRecordEditor} for why the
     * difference is not cosmetic.
     *
     * <p>Silently does nothing for a tag with no naming record. CloudKit holds none for an
     * accessory nobody ever named, and a rename of one of those is a change the next account read
     * will bring back properly - there is nothing here to edit in the meantime, and inventing a
     * record would put a document in the database that Apple never sent.
     */
    public Completable renameStoredAccessory(
            @NonNull final String beaconId, final String name, final String emoji) {
        return Completable.fromAction(() -> {
            // **Any nickname over this tag has to go.** The name being written is now the tag's
            // real one, and an override wins at display time - leaving one would hide the value
            // that was just sent to Apple behind the value it replaced.
            //
            // The nickname only, not the row: it also holds where the user dragged this tag to,
            // which a rename has nothing to say about. See clearNameAndEmoji.
            db.userBeaconOptionsDao().clearNameAndEmoji(beaconId, System.currentTimeMillis());

            final BeaconNamingRecord stored = db.beaconNamingRecordDao().getByBeaconId(beaconId);

            if (stored == null) {
                Log.i(TAG, "No stored naming record for " + beaconId
                        + "; the next account read will bring the new name back");
                return;
            }

            stored.content = NamingRecordEditor.with(stored.content, name, emoji);
            db.beaconNamingRecordDao().insertAll(stored);
        }).subscribeOn(Schedulers.io());
    }

    /**
     * The {@code version} recorded for a row that came from the account rather than a bundle.
     *
     * <p>A bundle's version is its export format, which is what tells a reader how to interpret
     * the files in it. Nothing was exported here, so borrowing a format number would be a claim
     * about a file that does not exist.
     */
    private static final String ACCOUNT_SOURCED_VERSION = "account";

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

    /**
     * Store a nickname and emoji, <b>without disturbing where the tag was dragged to</b>.
     *
     * <p>The insert is {@code REPLACE}, so it writes the whole row - and callers here build the
     * object from a rename dialog, which knows about a name and an emoji and nothing else. Its
     * {@code uiOrder} is therefore null, meaning "not supplied" rather than "unarrange this",
     * and writing it as-is would quietly send a renamed tag back to the end of the list.
     *
     * <p>So a null position is filled in from what is already stored. Nothing needs to clear one
     * through this path; {@code UserBeaconOptionsDao.storeArrangement} is what writes positions.
     */
    public Completable storeUserBeaconOptions(UserBeaconOptions userOptions) {
        return Completable.fromRunnable(() -> {
            try {
                if (userOptions.uiOrder == null) {
                    final UserBeaconOptions held =
                            this.db.userBeaconOptionsDao().getById(userOptions.beaconId);
                    if (held != null) {
                        userOptions.uiOrder = held.uiOrder;
                    }
                }

                this.db.userBeaconOptionsDao().insertAll(userOptions);
            } catch (Exception e) {
                Log.e(TAG, "Error occurred when trying to insert user options for beaconId="+userOptions.beaconId, e);
                throw new RepoQueryException(e);
            }
        }).subscribeOn(Schedulers.io());
    }

    /**
     * Record the order the user has just dragged their tags into.
     *
     * @param positions every visible tag's id to its place in the list, as produced by
     *                  {@code TagOrder.positionsFor}. All of them, not only the one that moved.
     */
    public Completable storeArrangement(final Map<String, Integer> positions) {
        return Completable.fromRunnable(() -> {
            try {
                this.db.userBeaconOptionsDao()
                        .storeArrangement(positions, System.currentTimeMillis());
            } catch (Exception e) {
                Log.e(TAG, "Error occurred when trying to store the tag arrangement", e);
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
    /**
     * The same, minus the tags a <b>scheduled</b> fetch should leave alone right now.
     *
     * <p><b>A separate entry point on purpose, and the separation is the safety.</b> The backoff
     * exists so the app stops spending most of its conversation with Apple on tags that never
     * answer - but a person who opens a tag and presses refresh must get a search every time,
     * however long it has been quiet, because they may have just found the thing. Filtering
     * inside {@link #toAccessoryRequests} would have applied it to both, and the user-facing
     * failure would be a button that silently does nothing.
     *
     * <p>So the periodic path calls this and the manual paths call the other one, and which is
     * which is readable at the call site rather than hidden behind a flag.
     */
    /** The newest location held for a tag, for the "last result" line on the tag page. */
    public Observable<Optional<Long>> newestReportTimeFor(@NonNull final String beaconId) {
        return Observable.fromCallable(
                        () -> Optional.ofNullable(db.locationReportDao().newestReportTimeFor(beaconId)))
                .subscribeOn(Schedulers.io());
    }

    public Observable<List<AccessoryRequest>> toScheduledAccessoryRequests(
            final Map<String, String> beaconIdToPlistFallback) {

        return Observable.fromCallable(() -> this.dueForAScheduledScan(beaconIdToPlistFallback))
                .subscribeOn(Schedulers.io())
                .flatMap(this::toAccessoryRequests);
    }

    private Map<String, String> dueForAScheduledScan(final Map<String, String> all) {
        final long now = System.currentTimeMillis();
        final var dao = db.ownedBeaconDao();

        final List<ScanOrder.Candidate> candidates = new ArrayList<>();
        int ignored = 0;
        int waiting = 0;

        for (final var entry : all.entrySet()) {
            final OwnedBeacon row = dao.getById(entry.getKey());

            if (row != null && row.ignoredAt != null) {
                ignored++;
                continue;
            }
            if (row != null && !WideScanBackoff.isDue(now, row.fruitlessScans, row.lastScanAt)) {
                waiting++;
                continue;
            }

            candidates.add(new ScanOrder.Candidate(
                    entry.getKey(),
                    row != null && row.lastScanAt != null,
                    row != null && row.lastScanAt != null && row.fruitlessScans == 0,
                    // Only ever consulted for a tag with no scan history, so this is read for
                    // every candidate and used for very few. It is a small XPath over a plist
                    // already in memory - cheaper than the schema change that storing it would
                    // cost, and rule 1 makes that a migration.
                    row == null ? null : KeyAlignmentPlist.observedAtMillis(row.alignmentPlist)));
        }

        if (ignored > 0 || waiting > 0) {
            Log.d(TAG, "Scheduled fetch is skipping " + ignored + " ignored and " + waiting
                    + " backing off; asking about " + candidates.size());
        }

        // **A LinkedHashMap, because the order is the point.** toAccessoryRequests walks the map
        // it is handed, so a HashMap here would throw the ordering away silently - the requests
        // would come out in hash order and nothing would fail.
        //
        // Null values are meaningful - see toAccessoryRequests - so every key goes in with
        // whatever fallback it had.
        final Map<String, String> due = new LinkedHashMap<>();
        for (final String beaconId : ScanOrder.forScheduledFetch(candidates, this.shuffle)) {
            due.put(beaconId, all.get(beaconId));
        }

        return due;
    }

    /**
     * The beacons nobody has ever searched for, which get a wider first window.
     *
     * <p>Read once per batch rather than per accessory: it is one small query and the answer
     * cannot change underneath a batch in a way that matters - a tag that becomes scanned
     * halfway through simply gets the ordinary window next time, which is the intent.
     */
    public Observable<Set<String>> neverScanned() {
        return Observable.fromCallable(
                        () -> (Set<String>) new HashSet<>(db.ownedBeaconDao().neverScannedIds()))
                .subscribeOn(Schedulers.io());
    }

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
     * Record that a tag was seen advertising at {@code keyIndex}, and keep the alignment it
     * proves.
     *
     * <p><b>A BLE sighting is worth the same as a decrypted location report</b>, and is persisted
     * the same way: FindMy.py's {@code update_alignment} is how it is told about either, and
     * {@code accessory_json} is where the result lives. Without this the twelve-hour candidate
     * range that found the tag is re-derived from scratch on the next scan; with it, the next
     * scan derives three keys.
     *
     * <p><b>Failure is swallowed on purpose.</b> This runs after a sound has already played (or
     * failed to), and nothing the user asked for depends on it - the cost of losing it is a
     * wider search next time, not a broken feature. It is emphatically not worth turning a
     * successful ring into an error toast.
     */
    public Completable recordAccessorySighting(
            final String beaconId, final String mac, final long seenAtUnixMs) {
        return Completable.fromRunnable(() -> {
            final var dao = db.ownedBeaconDao();
            final OwnedBeacon row = dao.getById(beaconId);

            if (row == null || row.accessoryJson == null) {
                Log.d(TAG, "Nothing to align for beaconId=" + beaconId + " - no accessory_json");
                return;
            }

            final String updated = AppDependencies.accessoryMacResolver()
                    .recordSeen(row.accessoryJson, mac, seenAtUnixMs);

            if (updated == null) {
                // Also the ordinary outcome of a secondary-key-only match, not just a failure -
                // see AccessoryMacResolver#recordSeen. Nothing to log as a problem here.
                return;
            }

            dao.updateAccessoryJson(beaconId, updated);
            Log.d(TAG, "Aligned beaconId=" + beaconId + " from a Bluetooth sighting");
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

            this.recordWhatEachScanFound(dao, fetchResult);

            return fetchResult.getReports();
        }).subscribeOn(Schedulers.io())
        .flatMap(this::storeToLocationCache);
    }

    /**
     * Note, per tag, whether this search found anything - which is what paces the next one.
     *
     * <p><b>Three outcomes, not two.</b> Something found resets everything. Nothing found lengthens
     * the wait a little, because a fortnight of silence is an ordinary tag having an ordinary
     * week. Nothing found across <i>months</i> of history is different in kind: the tag has
     * stopped broadcasting, and every further search costs a full-history scan that cannot repay
     * itself, so it is set aside until somebody asks.
     *
     * <p>Driven from what was actually searched rather than from a count of reports, because only
     * Python knows how wide the key window was - see {@code FetchResult#getExhaustedWideSearch}.
     */
    private void recordWhatEachScanFound(
            final dev.wander.android.opentagviewer.db.room.dao.OwnedBeaconDao dao,
            final FetchResult fetchResult) {

        final long now = System.currentTimeMillis();

        for (final var entry : fetchResult.getReports().entrySet()) {
            final String beaconId = entry.getKey();
            final boolean foundSomething = entry.getValue() != null && !entry.getValue().isEmpty();

            if (foundSomething) {
                dao.recordSuccessfulScan(beaconId, now);
            } else if (fetchResult.getExhaustedWideSearch().contains(beaconId)) {
                // **Not on the first one.** Retiring a tag is close to permanent - it is skipped
                // by every automatic fetch afterwards and only comes back when somebody opens it
                // and asks - so one bad search is a thin basis for it. A fetch can come back
                // empty for reasons that have nothing to do with the tag: a request that failed,
                // an account that was briefly unhappy, a moment when Apple returned nothing.
                //
                // So it takes a second, and the two are a refresh cycle apart rather than
                // back-to-back, which is a real further chance for somebody to walk past it.
                //
                // Phrased as "the previous search also failed" rather than "was also
                // exhaustive", because there is no column recording that and adding one means a
                // Room migration - rule 1 - for a refinement of a heuristic. In practice the
                // difference is nothing: exhaustion needs a key window wider than
                // _DEAD_TAG_WIDTH_INDICES, and a window that wide does not narrow on its own, so
                // for the tags this is aimed at every consecutive failure is an exhaustive one.
                final OwnedBeacon held = dao.getById(beaconId);
                final int failuresBefore = held == null ? 0 : held.fruitlessScans;

                if (failuresBefore >= 1) {
                    Log.i(TAG, beaconId + " found nothing across months of history for the"
                            + " second time running; it will be skipped until somebody asks for"
                            + " it directly");
                    dao.markIgnored(beaconId, now);
                } else {
                    Log.i(TAG, beaconId + " found nothing across months of history; giving it"
                            + " one more search before setting it aside");
                    dao.recordFruitlessScan(beaconId, now);
                }
            } else if (fetchResult.getWideSearch().contains(beaconId)) {
                dao.recordFruitlessScan(beaconId, now);
            } else {
                // **An empty answer from a cheap search is not a failure.** An aligned tag costs
                // a request or two, and finding nothing new in the window asked for is the
                // ordinary state of a tag that reported an hour ago and has not moved since.
                // Counting it made tags that update every day slowly accrue strikes and start
                // being asked less often - the opposite of what the backoff is for, which is to
                // stop full-history searches nobody is going to benefit from.
                dao.recordSuccessfulScan(beaconId, now);
            }
        }
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
