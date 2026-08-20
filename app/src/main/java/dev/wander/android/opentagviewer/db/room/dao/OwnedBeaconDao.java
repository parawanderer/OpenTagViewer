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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertAll(OwnedBeacon... ownedBeacons);

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
