package dev.wander.android.opentagviewer.db.room.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

import dev.wander.android.opentagviewer.db.room.entity.DailyHistoryFetchRecord;

@Dao
public interface DailyHistoryFetchRecordDao {
    @Query("SELECT * FROM DailyHistoryFetchRecord WHERE beacon_id = :beaconId")
    List<DailyHistoryFetchRecord> getInsertedDays(String beaconId);

    @Query("SELECT * FROM DailyHistoryFetchRecord WHERE beacon_id = :beaconId AND day_start_time = :dayStartTime")
    DailyHistoryFetchRecord getIfExists(String beaconId, long dayStartTime);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(DailyHistoryFetchRecord... records);
}
