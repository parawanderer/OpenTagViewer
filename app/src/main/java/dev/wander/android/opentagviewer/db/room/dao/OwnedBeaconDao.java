package dev.wander.android.opentagviewer.db.room.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;

@Dao
public interface OwnedBeaconDao {
    @Query("SELECT * FROM OwnedBeacons WHERE is_removed = 0")
    List<OwnedBeacon> getAll();

    @Query("SELECT * FROM OwnedBeacons WHERE import_id = :importId AND is_removed = 0")
    List<OwnedBeacon> getAllByImportId(int importId);

    @Query("SELECT * FROM OwnedBeacons WHERE id = :beaconId AND is_removed = 0")
    OwnedBeacon getById(String beaconId);

    /**
     * <b>REPLACE deletes the existing row, and that delete cascades.</b>
     *
     * <p>SQLite's {@code INSERT OR REPLACE} is not an update: on a primary key conflict it removes
     * the old row and inserts a new one. Room turns foreign keys on, so that removal runs every
     * {@code ON DELETE CASCADE} hanging off {@code OwnedBeacons} - and both
     * {@code UserBeaconOptions} and {@code LocationReport} are children. Re-writing a beacon that
     * already exists therefore erases the user's custom name and emoji for it and its entire
     * location history, while reading at the call site exactly like an upsert.
     *
     * <p>So this is for rows that are genuinely new. Anything that re-writes a beacon the database
     * may already hold wants {@link #insertIfNew} and {@link #refreshFromAccount} instead - see
     * {@code AccountRefreshKeepsWhatTheUserOwnsTest}, which exists because this went unnoticed.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertAll(OwnedBeacon... ownedBeacons);

    /** Add beacons that are not held yet, leaving any that are exactly as they are. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    List<Long> insertIfNew(OwnedBeacon... ownedBeacons);

    /**
     * Bring a held beacon into line with what the account says, without touching anything else.
     *
     * <p>An UPDATE rather than a re-insert, so nothing cascades. Three of the columns are written
     * defensively, and each {@code COALESCE} is deliberate:
     *
     * <ul>
     *   <li><b>{@code accessory_json} keeps what is already there.</b> It is not a copy of the
     *       plist - it carries the rolling-key alignment state that {@link #updateAccessoryJson}
     *       maintains after every fetch. Overwriting it with a freshly converted one throws that
     *       away and sends the next fetch back to searching the tag's whole history, which is the
     *       expense the alignment record exists to avoid. It is only filled in when absent.</li>
     *   <li><b>{@code alignment_plist} prefers the account's copy</b>, which is authoritative, but
     *       will not be cleared by a read that happens not to carry one.</li>
     *   <li><b>{@code content} likewise.</b> A null plist means this read did not include it, not
     *       that the tag no longer has one.</li>
     * </ul>
     *
     * <p>{@code is_removed} is cleared because a tag that left the account and came back should
     * not be restored still marked as gone.
     */
    @Query("UPDATE OwnedBeacons SET"
            + " content = COALESCE(:content, content),"
            + " alignment_plist = COALESCE(:alignmentPlist, alignment_plist),"
            + " accessory_json = COALESCE(accessory_json, :accessoryJson),"
            + " version = :version,"
            + " import_id = NULL,"
            + " from_account = 1,"
            + " is_removed = 0"
            + " WHERE id = :beaconId")
    void refreshFromAccount(String beaconId, String content, String alignmentPlist,
                            String accessoryJson, String version);

    /**
     * The same, for a beacon arriving again in a newer zip.
     *
     * <p>Re-importing an export is an ordinary thing to do - a newer one carries a key alignment
     * record an older one lacked - and it must not cost the user their custom names, the tag's
     * location history, or the record of which days have already been fetched. All three are
     * children of this table and all three cascade, so this re-links the import without deleting
     * anything. The {@code COALESCE} choices are the same as {@link #refreshFromAccount}.
     *
     * <p>{@code from_account} is cleared, matching what a re-insert did: a beacon that arrived in
     * a file is a file-imported beacon, and must not then be retired by an account refresh that
     * does not list it.
     */
    @Query("UPDATE OwnedBeacons SET"
            + " content = COALESCE(:content, content),"
            + " alignment_plist = COALESCE(:alignmentPlist, alignment_plist),"
            + " accessory_json = COALESCE(accessory_json, :accessoryJson),"
            + " version = :version,"
            + " import_id = :importId,"
            + " from_account = 0,"
            + " is_removed = 0"
            + " WHERE id = :beaconId")
    void refreshFromImport(String beaconId, String content, String alignmentPlist,
                           String accessoryJson, String version, long importId);

    @Query("UPDATE OwnedBeacons SET is_removed = 1 WHERE id = :beaconId")
    void setRemoved(String beaconId);

    /**
     * Persist the rolling-key alignment state (FindMy 0.9.x stateful FindMyAccessory)
     * after a successful fetch. Targeted UPDATE so we don't risk clobbering other
     * columns if the in-memory copy is stale.
     */
    @Query("UPDATE OwnedBeacons SET accessory_json = :accessoryJson WHERE id = :beaconId")
    void updateAccessoryJson(String beaconId, String accessoryJson);

    @Delete
    void delete(OwnedBeacon ownedBeaconWithId);

    /**
     * Record that a search for this tag found something.
     *
     * <p>Clears everything held against it: a tag that reports is a normal tag, whatever it was
     * doing before. A coat comes out of storage, a bike is found, and the app must go straight
     * back to asking about it as often as any other.
     */
    @Query("UPDATE OwnedBeacons SET fruitless_scans = 0, last_scan_at = :at, ignored_at = NULL"
            + " WHERE id = :beaconId")
    void recordSuccessfulScan(String beaconId, long at);

    /** Record that a search found nothing, which lengthens the wait before the next one. */
    @Query("UPDATE OwnedBeacons SET fruitless_scans = fruitless_scans + 1, last_scan_at = :at"
            + " WHERE id = :beaconId")
    void recordFruitlessScan(String beaconId, long at);

    /**
     * Give up on this tag until somebody asks again.
     *
     * <p>Only for a search that covered months and found nothing anywhere - see
     * {@code _DEAD_TAG_WIDTH_INDICES}. An ignored tag is skipped by the automatic fetches
     * entirely, which is the point: each one costs a full-history search that will not repay it.
     */
    @Query("UPDATE OwnedBeacons SET ignored_at = :at, last_scan_at = :at,"
            + " fruitless_scans = fruitless_scans + 1 WHERE id = :beaconId")
    void markIgnored(String beaconId, long at);

    /** The beacons currently held as a cache of the Apple account. */
    @Query("SELECT id FROM OwnedBeacons WHERE from_account = 1 AND is_removed = 0")
    List<String> getAccountBeaconIds();

    /**
     * Retire the account beacons that are no longer on the account.
     *
     * <p><b>Scoped to {@code from_account = 1}, and that scope is load-bearing.</b> A
     * file-imported beacon is the only copy in existence - nobody else holds it and the export it
     * came from may be long gone - so a refresh of what Apple holds must never reach one.
     *
     * <p>Marked removed rather than deleted, because {@code LocationReport} cascades on delete
     * and a tag that leaves the account should not take its history with it.
     */
    @Query("UPDATE OwnedBeacons SET is_removed = 1"
            + " WHERE from_account = 1 AND id NOT IN (:stillOnTheAccount)")
    int retireAccountBeaconsMissingFrom(List<String> stillOnTheAccount);

    /** The same, for an account that now holds nothing - `NOT IN ()` is not valid SQL. */
    @Query("UPDATE OwnedBeacons SET is_removed = 1 WHERE from_account = 1")
    int retireEveryAccountBeacon();
}
