package dev.wander.android.opentagviewer.db.repo;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Optional;

import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.BatteryLevel;
import dev.wander.android.opentagviewer.db.repo.model.LastSightingData;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.LastBleSighting;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;

/**
 * What a tag said over the air, kept after the tag itself has gone quiet.
 *
 * <p><b>Why it is stored at all.</b> The accessory record's own battery field is written by
 * Apple's devices as they walk past the tag, so for somebody with no Apple device it reads 0,
 * "not yet reported", forever - which is what both of the real tags this was built against still
 * report. The advertisement is then the only source there is, and it is only audible while the
 * tag is in range. Not keeping it would mean the one battery reading these users can get
 * disappears thirty seconds after it arrives.
 *
 * <p><b>The most recent sighting only.</b> Every advertisement carries the same two bits, so a
 * history of them would be thousands of rows saying "full" to answer a question that only ever
 * needs the last one.
 */
@RunWith(AndroidJUnit4.class)
public class KeepingWhatATagLastSaidTest {

    private static final String A_TAG = "a-tag";
    private static final String ANOTHER_TAG = "another-tag";
    private static final String A_PLIST = "<?xml version=\"1.0\"?><plist><dict></dict></plist>";

    /** Status bytes whose top two bits read as each level, as a real one would. */
    private static final int FULL_BYTE = 0b0000_0000;
    private static final int MEDIUM_BYTE = 0b0100_0000;
    private static final int LOW_BYTE = 0b1000_0000;
    private static final int VERY_LOW_BYTE = 0b1100_0000;

    private static final long MORNING = 1_700_000_000_000L;
    private static final long AFTERNOON = MORNING + 21_600_000L;

    private OpenTagViewerDatabase db;
    private BeaconRepository repo;

    @Before
    public void openAnInMemoryDatabase() {
        this.db = Room.inMemoryDatabaseBuilder(
                        getInstrumentation().getTargetContext(), OpenTagViewerDatabase.class)
                .allowMainThreadQueries()
                .build();

        this.repo = new BeaconRepository(this.db, (plist, alignment) -> "{\"type\":\"accessory\"}");

        this.insertTag(A_TAG);
        this.insertTag(ANOTHER_TAG);
    }

    @After
    public void closeIt() {
        this.db.close();
    }

    private void insertTag(final String id) {
        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(id).content(A_PLIST).accessoryJson("{\"type\":\"accessory\"}")
                .version("0.0.2").fromAccount(false).isRemoved(false).build());
    }

    private Optional<LastSightingData> readBack(final String beaconId) {
        return this.repo.getLastSighting(beaconId).blockingFirst();
    }

    /** A tag nothing has ever heard has no sighting, and must not be given one. */
    @Test
    public void aTagNeverHeardHasNothingStored() {
        assertTrue("a tag that has never been heard must not report a battery level",
                this.readBack(A_TAG).isEmpty());
    }

    @Test
    public void whatTheTagSaidSurvivesTheSightingThatCarriedIt() {
        this.repo.storeLastSighting(A_TAG, BatteryLevel.MEDIUM, MEDIUM_BYTE, MORNING)
                .blockingAwait();

        final Optional<LastSightingData> stored = this.readBack(A_TAG);

        assertTrue(stored.isPresent());
        assertEquals(BatteryLevel.MEDIUM, stored.get().getBatteryLevel());
        assertEquals("a sighting must carry the moment it was heard, or nothing on it can be"
                + " shown with its age", MORNING, stored.get().getHeardAtMs());
        assertEquals("the raw byte is what a disputed reading gets re-derived from",
                MEDIUM_BYTE, stored.get().getStatusByte());
    }

    /**
     * A later sighting replaces the earlier one rather than joining it. The point of the row is
     * "what it last said", and a tag draining from full to low must not still be able to answer
     * "full".
     */
    @Test
    public void aFresherSightingReplacesTheOneBeforeIt() {
        this.repo.storeLastSighting(A_TAG, BatteryLevel.FULL, FULL_BYTE, MORNING).blockingAwait();
        this.repo.storeLastSighting(A_TAG, BatteryLevel.LOW, LOW_BYTE, AFTERNOON).blockingAwait();

        final Optional<LastSightingData> stored = this.readBack(A_TAG);

        assertTrue(stored.isPresent());
        assertEquals(BatteryLevel.LOW, stored.get().getBatteryLevel());
        assertEquals(AFTERNOON, stored.get().getHeardAtMs());

        try (var cursor = this.db.query(
                "SELECT COUNT(*) FROM LastBleSighting WHERE beacon_id = ?",
                new Object[]{A_TAG})) {
            assertTrue(cursor.moveToFirst());
            assertEquals("the table holds the latest sighting per tag, not a history of them",
                    1, cursor.getInt(0));
        }
    }

    /** One tag's sighting is not another's, which a single-row-per-tag table has to get right. */
    @Test
    public void eachTagKeepsItsOwn() {
        this.repo.storeLastSighting(A_TAG, BatteryLevel.FULL, FULL_BYTE, MORNING).blockingAwait();
        this.repo.storeLastSighting(ANOTHER_TAG, BatteryLevel.VERY_LOW, VERY_LOW_BYTE, MORNING)
                .blockingAwait();

        assertEquals(BatteryLevel.FULL, this.readBack(A_TAG).get().getBatteryLevel());
        assertEquals(BatteryLevel.VERY_LOW, this.readBack(ANOTHER_TAG).get().getBatteryLevel());
    }

    /**
     * A battery level this build does not know makes the whole sighting unreadable rather than a
     * guess.
     *
     * <p>The case is a row written by a later version that understands a state this one does not,
     * met after a downgrade or a shared database. Every available way to map it onto the four
     * states here produces a wrong reading shown as a right one, so the row is passed over. It
     * stays in the table, raw byte and all, for whoever is debugging it.
     */
    @Test
    public void anUnknownStoredLevelIsNoReadingRatherThanAGuess() {
        this.db.lastBleSightingDao().insert(LastBleSighting.builder()
                .beaconId(A_TAG)
                .heardAt(MORNING)
                .batteryLevel("HALF_ISH")
                .statusByte(MEDIUM_BYTE)
                .build());

        assertTrue("an unrecognised level must not be rounded to a neighbouring one",
                this.readBack(A_TAG).isEmpty());

        try (var cursor = this.db.query(
                "SELECT battery_level FROM LastBleSighting WHERE beacon_id = ?",
                new Object[]{A_TAG})) {
            assertTrue(cursor.moveToFirst());
            assertEquals("the unreadable row must be left alone, not deleted",
                    "HALF_ISH", cursor.getString(0));
        }
    }

    /**
     * Removing a tag takes its stored sighting with it.
     *
     * <p>The foreign key is what makes that automatic. Without it a sighting would outlive the tag
     * it describes and be waiting to be shown against a re-imported tag of the same id, dated
     * before that tag was ever added here.
     */
    @Test
    public void deletingATagTakesItsSightingWithIt() {
        this.repo.storeLastSighting(A_TAG, BatteryLevel.FULL, FULL_BYTE, MORNING).blockingAwait();
        this.repo.storeLastSighting(ANOTHER_TAG, BatteryLevel.LOW, LOW_BYTE, MORNING)
                .blockingAwait();

        this.db.getOpenHelper().getWritableDatabase()
                .execSQL("DELETE FROM OwnedBeacons WHERE id = ?", new Object[]{A_TAG});

        assertTrue("a deleted tag must not leave a sighting behind",
                this.readBack(A_TAG).isEmpty());
        assertFalse("deleting one tag must not touch another's sighting",
                this.readBack(ANOTHER_TAG).isEmpty());
    }
}
