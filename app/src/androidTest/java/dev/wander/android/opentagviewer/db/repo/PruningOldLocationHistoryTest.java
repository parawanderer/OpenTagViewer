package dev.wander.android.opentagviewer.db.repo;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.DailyHistoryFetchRecord;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;

/**
 * What {@link LocationHistoryRetention} deletes, against a real SQLite in memory.
 *
 * <p>On a device because the delete is a correlated subquery, and whether it keeps each tag's
 * newest report is a claim about SQLite that only SQLite can answer. In memory so it never
 * touches the phone's own history.
 */
@RunWith(AndroidJUnit4.class)
public class PruningOldLocationHistoryTest {

    private static final String A_TAG = "tag-a";
    private static final String ANOTHER_TAG = "tag-b";

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final int A_MONTH = 31;
    private static final long CUTOFF = NOW - A_MONTH * DAY;

    private OpenTagViewerDatabase db;
    private LocationHistoryRetention retention;

    @Before
    public void openAnInMemoryDatabase() {
        this.db = Room.inMemoryDatabaseBuilder(
                        getInstrumentation().getTargetContext(), OpenTagViewerDatabase.class)
                .allowMainThreadQueries()
                .build();
        this.db.ownedBeaconDao().insertAll(own(A_TAG), own(ANOTHER_TAG));
        this.retention = new LocationHistoryRetention(this.db, () -> NOW);
    }

    @After
    public void closeIt() {
        this.db.close();
    }

    @Test
    public void keepingEverythingDeletesNothing() {
        this.db.locationReportDao().insertAll(
                report(A_TAG, NOW - 1000 * DAY),
                report(A_TAG, NOW - 400 * DAY),
                report(A_TAG, NOW - DAY));

        assertEquals(0, (int) this.retention.prune(UserSettings.HISTORY_RETENTION_FOREVER)
                .blockingGet());
        assertEquals(3, this.everything(A_TAG).size());
    }

    @Test
    public void reportsOlderThanThePeriodGoAndNewerOnesStay() {
        this.db.locationReportDao().insertAll(
                report(A_TAG, NOW - 90 * DAY),
                report(A_TAG, NOW - 40 * DAY),
                report(A_TAG, NOW - 10 * DAY),
                report(A_TAG, NOW - DAY));

        assertEquals(2, (int) this.retention.prune(A_MONTH).blockingGet());
        assertEquals(List.of(NOW - 10 * DAY, NOW - DAY), this.timestamps(A_TAG));
    }

    /**
     * A tag not heard from for longer than the period keeps its last known position. Without
     * that the map would show it as never located, which reads as the app having lost it.
     */
    @Test
    public void aTagKeepsItsNewestReportHoweverOld() {
        this.db.locationReportDao().insertAll(
                report(A_TAG, NOW - 300 * DAY),
                report(A_TAG, NOW - 200 * DAY),
                report(A_TAG, NOW - 100 * DAY));

        assertEquals(2, (int) this.retention.prune(A_MONTH).blockingGet());
        assertEquals(List.of(NOW - 100 * DAY), this.timestamps(A_TAG));
    }

    @Test
    public void aTagWithOneOldReportKeepsIt() {
        this.db.locationReportDao().insertAll(report(A_TAG, NOW - 500 * DAY));

        assertEquals(0, (int) this.retention.prune(A_MONTH).blockingGet());
        assertEquals(List.of(NOW - 500 * DAY), this.timestamps(A_TAG));
    }

    /** Each tag is judged on its own newest, not on the newest report of any tag. */
    @Test
    public void eachTagKeepsItsOwnNewest() {
        this.db.locationReportDao().insertAll(
                report(A_TAG, NOW - 200 * DAY),
                report(A_TAG, NOW - DAY),
                report(ANOTHER_TAG, NOW - 300 * DAY),
                report(ANOTHER_TAG, NOW - 250 * DAY));

        assertEquals(2, (int) this.retention.prune(A_MONTH).blockingGet());
        assertEquals(List.of(NOW - DAY), this.timestamps(A_TAG));
        assertEquals(List.of(NOW - 250 * DAY), this.timestamps(ANOTHER_TAG));

        final Map<String, LocationReport> onTheMap = new HashMap<>();
        for (final LocationReport latest : this.db.locationReportDao().getLastForAllBeacons()) {
            onTheMap.put(latest.beaconId, latest);
        }
        assertEquals("the map still has a position for both tags", 2, onTheMap.size());
        assertEquals(NOW - 250 * DAY, onTheMap.get(ANOTHER_TAG).timestamp);
    }

    @Test
    public void aReportExactlyAtTheCutoffIsKept() {
        this.db.locationReportDao().insertAll(
                report(A_TAG, CUTOFF - 1),
                report(A_TAG, CUTOFF),
                report(A_TAG, NOW));

        assertEquals(1, (int) this.retention.prune(A_MONTH).blockingGet());
        assertEquals(List.of(CUTOFF, NOW), this.timestamps(A_TAG));
    }

    /**
     * Local sightings and Google reports are history too, and go by the same rule. The newest is
     * the newest of any provenance, because that is what the map draws.
     */
    @Test
    public void everyProvenanceIsPrunedTheSameWay() {
        this.db.locationReportDao().insertAll(
                report(A_TAG, NOW - 60 * DAY, LocationReport.PROVENANCE_LOCAL),
                report(A_TAG, NOW - 50 * DAY, LocationReport.PROVENANCE_GOOGLE),
                report(A_TAG, NOW - 2 * DAY, LocationReport.PROVENANCE_APPLE));

        assertEquals(2, (int) this.retention.prune(A_MONTH).blockingGet());
        assertEquals(List.of(NOW - 2 * DAY), this.timestamps(A_TAG));
    }

    /**
     * The per-day "already fetched" markers stay. They hold no position, and without one the
     * History screen would ask Apple for that day again.
     */
    @Test
    public void theFetchedDayMarkersAreLeftAlone() {
        this.db.locationReportDao().insertAll(
                report(A_TAG, NOW - 60 * DAY),
                report(A_TAG, NOW - DAY));
        this.db.dailyHistoryFetchRecordDao().insertAll(DailyHistoryFetchRecord.builder()
                .beaconId(A_TAG)
                .dayStartTime(NOW - 60 * DAY)
                .lastUpdate(NOW - 60 * DAY)
                .build());

        this.retention.prune(A_MONTH).blockingGet();

        assertNotNull(this.db.dailyHistoryFetchRecordDao().getIfExists(A_TAG, NOW - 60 * DAY));
    }

    /** Ascending, so a failure reads as the history it left behind. */
    private List<Long> timestamps(final String beaconId) {
        final List<Long> out = new ArrayList<>();
        for (final LocationReport report : this.everything(beaconId)) {
            out.add(report.timestamp);
        }
        return out;
    }

    private List<LocationReport> everything(final String beaconId) {
        return this.db.locationReportDao().getInTimeRange(beaconId, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    private static OwnedBeacon own(final String id) {
        return OwnedBeacon.builder()
                .id(id)
                .content("{}")
                .accessoryJson("{\"type\":\"accessory\"}")
                .version("0.0.2")
                .fromAccount(false)
                .isRemoved(false)
                .build();
    }

    private static LocationReport report(final String beaconId, final long timestamp) {
        return report(beaconId, timestamp, LocationReport.PROVENANCE_APPLE);
    }

    private static LocationReport report(
            final String beaconId, final long timestamp, final String provenance) {
        return LocationReport.builder()
                .hashId(beaconId + "-" + timestamp)
                .beaconId(beaconId)
                .publishedAt(timestamp)
                .description("report at " + timestamp)
                .timestamp(timestamp)
                .confidence(1)
                .latitude(1.0)
                .longitude(-1.0)
                .horizontalAccuracy(10L)
                .status(0)
                .lastUpdate(timestamp)
                .provenance(provenance)
                .build();
    }
}
