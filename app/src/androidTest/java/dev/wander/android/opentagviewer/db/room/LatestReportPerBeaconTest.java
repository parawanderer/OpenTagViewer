package dev.wander.android.opentagviewer.db.room;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;

/**
 * The map's "where is each tag now" query, which relies on a SQLite rule to be correct.
 *
 * <p>{@code getLastForAllBeacons} is
 * {@code SELECT MAX(timestamp), * FROM LocationReport GROUP BY beacon_id}. In standard SQL that
 * is meaningless - the bare columns may come from any row of the group. SQLite guarantees they
 * come from the row that produced the max, but <b>only while the query contains exactly one
 * {@code min()} or {@code max()}</b>.
 *
 * <p><b>So the query has a precondition that nothing in the type system carries.</b> Delete the
 * aggregate as unused - it is, its value is never read - or add a second one, and the query
 * still compiles, still returns one row per tag, and starts returning an arbitrary report for
 * each. The map then shows positions from whenever, indefinitely, with nothing failing.
 *
 * <p>This is the thing that notices. It is on a device rather than the JVM because it is a claim
 * about SQLite's behaviour, and only the real engine can answer it - rule 13's carve-out, not an
 * exception to it: a fake would be asserting my belief about SQLite back at me.
 */
@RunWith(AndroidJUnit4.class)
public class LatestReportPerBeaconTest {

    private static final String A_TAG = "tag-a";
    private static final String ANOTHER_TAG = "tag-b";

    private OpenTagViewerDatabase db;

    @Before
    public void openAnInMemoryDatabase() {
        this.db = Room.inMemoryDatabaseBuilder(
                        getInstrumentation().getTargetContext(), OpenTagViewerDatabase.class)
                .allowMainThreadQueries()
                .build();

        // A report has a foreign key onto the tag that produced it, so the tags have to exist
        // before any of them do. Both tags for every test, including the ones that only use one -
        // an empty group is not what any of these are about, and a tag with no reports simply
        // does not appear in a GROUP BY over the reports table.
        this.db.ownedBeaconDao().insertAll(own(A_TAG), own(ANOTHER_TAG));
    }

    @After
    public void closeIt() {
        this.db.close();
    }

    /**
     * <b>The newest report per tag, when the newest is not the last row inserted.</b>
     *
     * <p>Insertion order is deliberately not timestamp order. A query that returned "whichever
     * row SQLite reached last" would pass against rows inserted oldest-first, which is how they
     * arrive in practice and therefore how a test written without thinking would arrange them.
     */
    @Test
    public void eachTagComesBackAtItsNewestReport() {
        this.db.locationReportDao().insertAll(
                report(A_TAG, 3_000L, 3.0),
                report(A_TAG, 9_000L, 9.0),
                report(A_TAG, 6_000L, 6.0),
                report(ANOTHER_TAG, 5_000L, 5.0),
                report(ANOTHER_TAG, 1_000L, 1.0));

        final Map<String, LocationReport> byBeacon = byBeacon(
                this.db.locationReportDao().getLastForAllBeacons());

        assertEquals("one row per tag, not one per report", 2, byBeacon.size());

        assertEquals("tag-a came back at the wrong timestamp",
                9_000L, byBeacon.get(A_TAG).timestamp);
        assertEquals("tag-b came back at the wrong timestamp",
                5_000L, byBeacon.get(ANOTHER_TAG).timestamp);
    }

    /**
     * <b>And the whole row is that report, not a mixture.</b>
     *
     * <p>The timestamp is the column the aggregate is over, so a broken query can still return
     * the right one while taking latitude and longitude from a different row - which is the
     * failure that matters, because the coordinates are what gets drawn. Each report here is at
     * a distinct position, so a mixed row is detectable.
     */
    @Test
    public void thecoordinatesBelongToThatSameReport() {
        this.db.locationReportDao().insertAll(
                report(A_TAG, 3_000L, 3.0),
                report(A_TAG, 9_000L, 9.0),
                report(A_TAG, 6_000L, 6.0));

        final LocationReport newest =
                byBeacon(this.db.locationReportDao().getLastForAllBeacons()).get(A_TAG);

        assertNotNull(newest);
        assertEquals("the latitude came from a different row than the timestamp",
                9.0, newest.latitude, 0.000_001);
        assertEquals("the longitude came from a different row than the timestamp",
                -9.0, newest.longitude, 0.000_001);
        assertEquals("the hash id came from a different row than the timestamp",
                A_TAG + "-9000", newest.hashId);
    }

    /** A tag with exactly one report is still returned - the group of one. */
    @Test
    public void atagWithASingleReportIsNotDropped() {
        this.db.locationReportDao().insertAll(report(A_TAG, 4_000L, 4.0));

        final Map<String, LocationReport> byBeacon = byBeacon(
                this.db.locationReportDao().getLastForAllBeacons());

        assertEquals(1, byBeacon.size());
        assertEquals(4_000L, byBeacon.get(A_TAG).timestamp);
    }

    /** And an empty table is an empty list rather than a row of nulls. */
    @Test
    public void nothingStoredIsNothingReturned() {
        assertEquals(List.of(), this.db.locationReportDao().getLastForAllBeacons());
    }

    private static Map<String, LocationReport> byBeacon(final List<LocationReport> reports) {
        final Map<String, LocationReport> out = new HashMap<>();
        for (final LocationReport report : reports) {
            out.put(report.beaconId, report);
        }
        return out;
    }

    /** Just enough of a tag to satisfy the foreign key; nothing here reads any of it. */
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

    /**
     * A report whose every field follows from its timestamp.
     *
     * <p>So "this column came from the wrong row" is visible in the assertion rather than needing
     * to be worked out - a row at 9000 has latitude 9.0 and hash {@code tag-a-9000}.
     */
    private static LocationReport report(
            final String beaconId, final long timestamp, final double position) {
        return LocationReport.builder()
                .hashId(beaconId + "-" + timestamp)
                .beaconId(beaconId)
                .publishedAt(timestamp)
                .description("report at " + timestamp)
                .timestamp(timestamp)
                .confidence(1)
                .latitude(position)
                .longitude(-position)
                .horizontalAccuracy(10L)
                .status(0)
                .lastUpdate(timestamp)
                .provenance(LocationReport.PROVENANCE_APPLE)
                .build();
    }
}
