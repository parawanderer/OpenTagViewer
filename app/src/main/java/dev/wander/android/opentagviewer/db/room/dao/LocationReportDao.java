package dev.wander.android.opentagviewer.db.room.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

import dev.wander.android.opentagviewer.db.room.entity.LocationReport;

@Dao
public interface LocationReportDao {
    @Query("SELECT * FROM LocationReport WHERE beacon_id = :beaconId ORDER BY timestamp DESC LIMIT 1")
    LocationReport getLastFor(String beaconId);

    @Query("SELECT * FROM LocationReport WHERE beacon_id = :beaconId AND timestamp >= :startUnixMS AND timestamp < :endUnixMS ORDER BY timestamp ASC")
    List<LocationReport> getInTimeRange(String beaconId, long startUnixMS, long endUnixMS);

    @Query("SELECT MAX(timestamp) AS latest_report_timestamp, * FROM LocationReport GROUP BY beacon_id")
    List<LocationReport> getLastForAllBeacons();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(LocationReport... locationReports);
}
