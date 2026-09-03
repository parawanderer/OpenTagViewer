package dev.wander.android.opentagviewer.db.room.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import dev.wander.android.opentagviewer.db.room.entity.LastBleSighting;

@Dao
public interface LastBleSightingDao {
    @Query("SELECT * FROM LastBleSighting WHERE beacon_id = :beaconId")
    LastBleSighting getById(String beaconId);

    /**
     * Store this sighting, replacing whatever the tag last said.
     *
     * <p><b>{@code REPLACE} is safe here in a way it is not on other tables.</b> It deletes the
     * conflicting row before inserting, and on {@code OwnedBeacons} or {@code UserBeaconOptions}
     * that delete either cascades into location history or throws away a nickname - see the long
     * note on {@code UserBeaconOptionsDao.storeArrangement}. Nothing references this table, and
     * every column is written on every insert, so there is nothing for the delete to take with
     * it. It also works on the SQLite that ships with API 24, which the {@code ON CONFLICT DO
     * UPDATE} form does not.
     *
     * <p><b>Worth revisiting if a column is ever added that not every sighting can fill.</b> A
     * position, for instance, would be absent whenever the phone had no fix - and with
     * {@code REPLACE} a sighting carrying no position would erase the last one that did. At that
     * point this wants to become the insert-then-update pair that table uses.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(LastBleSighting sighting);
}
