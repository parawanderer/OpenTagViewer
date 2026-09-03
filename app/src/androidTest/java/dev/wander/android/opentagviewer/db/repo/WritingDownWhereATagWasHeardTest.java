package dev.wander.android.opentagviewer.db.repo;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.util.LocalFixWorthKeeping;

/**
 * A position this phone worked out for itself, stored beside the ones Apple's network sent.
 *
 * <p><b>Same table, different claim.</b> Everything that draws a tag reads from
 * {@code LocationReport}, so a locally heard position has to land there to be of any use - but a
 * row saying "a stranger's iPhone guessed the tag was somewhere around here" and one saying
 * "this phone heard it from ten metres away" are not interchangeable, and the history gets
 * exported. The {@code provenance} column is what keeps them apart.
 */
@RunWith(AndroidJUnit4.class)
public class WritingDownWhereATagWasHeardTest {

    private static final String A_TAG = "a-tag";
    private static final String A_PLIST = "<?xml version=\"1.0\"?><plist><dict></dict></plist>";

    /** Ilvesheim, where the tags behind this feature actually live. */
    private static final double LAT = 49.4767;
    private static final double LON = 8.5622;

    private static final long NOON = 1_700_000_000_000L;
    private static final int A_STATUS_BYTE = 0x20;

    private OpenTagViewerDatabase db;
    private BeaconRepository repo;

    @Before
    public void openAnInMemoryDatabase() {
        this.db = Room.inMemoryDatabaseBuilder(
                        getInstrumentation().getTargetContext(), OpenTagViewerDatabase.class)
                .allowMainThreadQueries()
                .build();

        this.repo = new BeaconRepository(this.db, (plist, alignment) -> "{\"type\":\"accessory\"}");

        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(A_TAG).content(A_PLIST).accessoryJson("{\"type\":\"accessory\"}")
                .version("0.0.2").fromAccount(false).isRemoved(false).build());
    }

    @After
    public void closeIt() {
        this.db.close();
    }

    /**
     * @return whether the sighting earned a row. {@code recordLocalSighting} used to answer that
     *         with a boolean and now hands back the report it wrote, so presence is the same
     *         answer - every assertion here is still about the decision, not the row.
     */
    private boolean record(final double lat, final double lon, final long accuracy, final long at) {
        return this.repo.recordLocalSighting(A_TAG, lat, lon, accuracy, A_STATUS_BYTE, at)
                .blockingFirst()
                .isPresent();
    }

    private List<LocationReport> allReports() {
        return this.db.locationReportDao()
                .getInTimeRange(A_TAG, NOON - 86_400_000L, NOON + 86_400_000L);
    }

    @Test
    public void aSightingBecomesALocationReportMarkedAsLocal() {
        assertTrue(this.record(LAT, LON, 8, NOON));

        final List<LocationReport> reports = this.allReports();
        assertEquals(1, reports.size());

        final LocationReport report = reports.get(0);
        assertEquals(LocationReport.PROVENANCE_LOCAL, report.provenance);
        assertEquals(LAT, report.latitude, 0.00001);
        assertEquals(LON, report.longitude, 0.00001);
        assertEquals(NOON, report.timestamp);
    }

    /**
     * The accuracy is the fix's own, not a guess.
     *
     * <p>It is the field anything comparing two reports reads, and a locally heard position is
     * usually an order of magnitude tighter than a network one. Inventing a number here would
     * either throw that advantage away or claim precision the fix never had.
     */
    @Test
    public void theFixesOwnAccuracyIsWhatGetsStored() {
        this.record(LAT, LON, 8, NOON);

        assertEquals(8, this.allReports().get(0).horizontalAccuracy);
    }

    /** The status byte the tag broadcast rides along, the same field an Apple report carries. */
    @Test
    public void theAdvertisedStatusByteIsKeptOnTheReport() {
        this.record(LAT, LON, 8, NOON);

        assertEquals(A_STATUS_BYTE, this.allReports().get(0).status);
    }

    /**
     * <b>The rule that keeps a tag on a desk from filling the history.</b> Sightings arrive every
     * couple of seconds and the callback fires once a minute; without this, an evening beside
     * somebody would be several hundred rows describing one spot.
     */
    @Test
    public void standingStillDoesNotWriteASecondRowStraightAway() {
        assertTrue(this.record(LAT, LON, 8, NOON));
        assertFalse(this.record(LAT, LON, 8, NOON + 60_000));

        assertEquals(1, this.allReports().size());
    }

    @Test
    public void movingFarEnoughWritesAnotherRow() {
        assertTrue(this.record(LAT, LON, 8, NOON));
        assertTrue(this.record(LAT + 0.0008, LON, 8, NOON + 60_000));

        assertEquals(2, this.allReports().size());
    }

    @Test
    public void stayingPutIsWorthRecordingAgainAfterLongEnough() {
        assertTrue(this.record(LAT, LON, 8, NOON));
        assertTrue(this.record(LAT, LON, 8, NOON + LocalFixWorthKeeping.AGAIN_AFTER_MS));

        assertEquals(2, this.allReports().size());
    }

    /**
     * A network report must not suppress the local row that supersedes it.
     *
     * <p>The two answer different questions: "when did somebody else last see it" and "when did
     * I last hear it". Deciding the write rule from the newest report of <i>any</i> kind would
     * mean a tag fetched a minute ago never records the far more precise position of being heard
     * in the same room.
     */
    @Test
    public void anAppleReportDoesNotStandInForTheLastLocalOne() {
        this.db.locationReportDao().insertAll(LocationReport.builder()
                .hashId("an-apple-report")
                .beaconId(A_TAG)
                .publishedAt(NOON)
                .description("Apple")
                .timestamp(NOON)
                .confidence(2)
                .latitude(LAT)
                .longitude(LON)
                .horizontalAccuracy(120)
                .status(0)
                .lastUpdate(NOON)
                .provenance(LocationReport.PROVENANCE_APPLE)
                .build());

        assertTrue("a fresh network report must not suppress a local sighting",
                this.record(LAT, LON, 8, NOON + 1_000));
    }

    /**
     * Two sightings of the same tag at the same moment and place collapse to one row.
     *
     * <p>The id is a hash of what the report says, so a repeat cannot accumulate - which is what
     * keeps a retry or a duplicated callback from doubling the history.
     */
    @Test
    public void theSameSightingTwiceIsOneRow() {
        this.repo.recordLocalSighting(A_TAG, LAT, LON, 8, A_STATUS_BYTE, NOON).blockingFirst();
        this.repo.recordLocalSighting(A_TAG, LAT, LON, 8, A_STATUS_BYTE, NOON).blockingFirst();

        assertEquals(1, this.allReports().size());
    }

    /** And the map reads it: the newest row per tag is what gets drawn. */
    @Test
    public void aLocalReportBecomesTheTagsLatestPosition() {
        this.record(LAT, LON, 8, NOON);

        final LocationReport latest = this.db.locationReportDao().getLastFor(A_TAG);

        assertNotNull(latest);
        assertEquals(LocationReport.PROVENANCE_LOCAL, latest.provenance);
    }
}
