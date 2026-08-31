package dev.wander.android.opentagviewer.db.room.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.util.BeaconLocationReportHasher;
import dev.wander.android.opentagviewer.util.history.HistoryImportResult;
import dev.wander.android.opentagviewer.util.history.HistoryImportRow;

/** The atomic database half of restoring a history archive. */
@Dao
public interface HistoryImportDao {
    @Query("SELECT EXISTS(SELECT 1 FROM OwnedBeacons"
            + " WHERE id = :beaconId AND is_removed = 0)")
    boolean isActiveBeacon(String beaconId);

    @Query("SELECT timestamp FROM LocationReport WHERE beacon_id = :beaconId")
    List<Long> timestampsFor(String beaconId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(LocationReport report);

    /**
     * Existing data wins. The first valid row in the archive wins among repeated timestamps.
     * Everything is one transaction so a database failure leaves no partial restore behind.
     */
    @Transaction
    default HistoryImportResult merge(
            final List<HistoryImportRow> rows,
            final int rowsRead,
            final int malformedRows,
            final long now) {

        final Map<String, Boolean> activeBeacons = new HashMap<>();
        final Map<String, Set<Long>> heldTimestamps = new HashMap<>();
        int added = 0;
        int alreadyPresent = 0;
        int unknown = 0;

        for (HistoryImportRow row : rows) {
            final String beaconId = row.getBeaconId();
            final boolean active = activeBeacons.computeIfAbsent(
                    beaconId, this::isActiveBeacon);
            if (!active) {
                unknown++;
                continue;
            }

            final Set<Long> timestamps = heldTimestamps.computeIfAbsent(
                    beaconId, id -> new HashSet<>(this.timestampsFor(id)));
            if (!timestamps.add(row.getReport().getTimestamp())) {
                alreadyPresent++;
                continue;
            }

            final LocationReport stored = LocationReport.builder()
                    .hashId(BeaconLocationReportHasher.getSha256HashFor(
                            beaconId, row.getReport()))
                    .beaconId(beaconId)
                    .publishedAt(row.getReport().getPublishedAt())
                    .description(row.getReport().getDescription())
                    .timestamp(row.getReport().getTimestamp())
                    .confidence(row.getReport().getConfidence())
                    .latitude(row.getReport().getLatitude())
                    .longitude(row.getReport().getLongitude())
                    .horizontalAccuracy(row.getReport().getHorizontalAccuracy())
                    .status(row.getReport().getStatus())
                    .lastUpdate(now)
                    .build();

            if (this.insert(stored) == -1L) {
                alreadyPresent++;
            } else {
                added++;
            }
        }

        return new HistoryImportResult(
                rowsRead, added, alreadyPresent, malformedRows, unknown);
    }
}
