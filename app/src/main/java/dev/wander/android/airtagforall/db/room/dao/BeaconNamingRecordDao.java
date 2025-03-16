package dev.wander.android.airtagforall.db.room.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

import dev.wander.android.airtagforall.db.room.entity.BeaconNamingRecord;

@Dao
public interface BeaconNamingRecordDao {
    @Query("SELECT * FROM BeaconNamingRecord")
    List<BeaconNamingRecord> getAll();

    @Query("SELECT * FROM BeaconNamingRecord WHERE import_id = :importId")
    List<BeaconNamingRecord> getAllByImportId(int importId);

    @Query("SELECT * FROM BeaconNamingRecord WHERE id = :beaconId")
    BeaconNamingRecord getByBeaconId(String beaconId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertAll(BeaconNamingRecord... beaconNamingRecords);

    @Delete
    void delete(BeaconNamingRecord beaconNamingRecWithId);
}
