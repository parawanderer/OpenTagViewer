package dev.wander.android.opentagviewer.db.room.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RewriteQueriesToDropUnusedColumns;

import java.util.List;

import dev.wander.android.opentagviewer.db.room.entity.LocationReport;

@Dao
public interface LocationReportDao {
    @Query("SELECT * FROM LocationReport WHERE beacon_id = :beaconId ORDER BY timestamp DESC LIMIT 1")
    LocationReport getLastFor(String beaconId);

    @Query("SELECT * FROM LocationReport WHERE beacon_id = :beaconId AND timestamp >= :startUnixMS AND timestamp < :endUnixMS ORDER BY timestamp ASC")
    List<LocationReport> getInTimeRange(String beaconId, long startUnixMS, long endUnixMS);

    /**
     * The newest report held for each beacon - one row per tag, which is what the map draws.
     *
     * <p><b>The {@code MAX(timestamp)} is load-bearing, and its value is never read.</b> In
     * standard SQL, selecting bare columns alongside an aggregate is meaningless: the group has
     * many rows and the engine may take those columns from any of them. SQLite makes one
     * documented exception - if a query contains <i>exactly one</i> {@code min()} or
     * {@code max()} aggregate, every bare column comes from the row that produced it
     * (<a href="https://www.sqlite.org/lang_select.html#bareagg">lang_select §bareagg</a>).
     *
     * <p>So the aggregate is not here for its result. It is here to choose which row of each
     * group the rest of the columns come from, and deleting it as unused would leave a query
     * that still compiles, still returns one row per tag, and returns an <i>arbitrary</i> report
     * for each - a map showing positions from whenever, with nothing failing anywhere.
     *
     * <p>Adding a second aggregate breaks it the same silent way, because the guarantee holds
     * only while there is exactly one. {@code LatestReportPerBeaconTest} is what notices.
     *
     * <p>{@code latest_report_timestamp} is that same row's {@code timestamp} under another
     * name, so nothing maps it and Room is told to drop it rather than warning on every build.
     */
    @RewriteQueriesToDropUnusedColumns
    @Query("SELECT MAX(timestamp) AS latest_report_timestamp, * FROM LocationReport GROUP BY beacon_id")
    List<LocationReport> getLastForAllBeacons();

    /**
     * The newest report held for one tag, or null if there are none.
     *
     * <p>Read for the tag page's debug line: "last result" answers a different question from
     * "last attempt", and the gap between them is the whole diagnosis when somebody asks why a
     * tag has stopped moving.
     */
    @Query("SELECT MAX(timestamp) FROM LocationReport WHERE beacon_id = :beaconId")
    Long newestReportTimeFor(String beaconId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(LocationReport... locationReports);
}
