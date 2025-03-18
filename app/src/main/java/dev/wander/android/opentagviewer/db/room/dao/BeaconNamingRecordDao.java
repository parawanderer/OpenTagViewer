package dev.wander.android.opentagviewer.db.room.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;

@Dao
public interface BeaconNamingRecordDao {
    @Query("SELECT * FROM BeaconNamingRecord WHERE is_removed = 0")
    List<BeaconNamingRecord> getAll();

    @Query("SELECT * FROM BeaconNamingRecord WHERE id = :beaconId AND is_removed = 0")
    BeaconNamingRecord getByBeaconId(String beaconId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertAll(BeaconNamingRecord... beaconNamingRecords);

    @Query("UPDATE BeaconNamingRecord SET is_removed = 1 WHERE id = :beaconId")
    void setRemoved(String beaconId);

    @Delete
    void delete(BeaconNamingRecord beaconNamingRecWithId);
}
