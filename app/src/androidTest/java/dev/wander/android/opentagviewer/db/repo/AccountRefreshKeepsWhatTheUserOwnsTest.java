package dev.wander.android.opentagviewer.db.repo;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.repo.model.ImportData;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.db.room.entity.UserBeaconOptions;
import dev.wander.android.opentagviewer.python.icloud.AccessoryRecords;

/**
 * What an account read is allowed to destroy, which is nothing the user put there.
 *
 * <p><b>This is a data-loss test, not a correctness test.</b> The account read runs on its own,
 * every six hours and on every start, against tags the user did not ask it to touch. Anything it
 * silently removes is removed without anybody performing an action they could connect it to -
 * they restart the app and their custom names are gone.
 *
 * <p>The mechanism is worth stating because it is invisible at the Java layer. Room's
 * {@code @Insert(onConflict = REPLACE)} compiles to SQLite's {@code INSERT OR REPLACE}, and that
 * is <b>not</b> an update: it deletes the conflicting row and inserts a new one. Room enables
 * {@code PRAGMA foreign_keys}, so that delete runs the {@code ON DELETE CASCADE} on every child
 * table - and {@code UserBeaconOptions} and {@code LocationReport} are both children of
 * {@code OwnedBeacons}. Re-writing a row that already exists therefore erases the user's name and
 * emoji for that tag, and its entire location history, while reading exactly like an upsert.
 *
 * <p>Reported by @parawanderer: an iPad, a MacBook and a duplicate iPad were given custom emoji,
 * and the rows were gone after a restart.
 */
@RunWith(AndroidJUnit4.class)
public class AccountRefreshKeepsWhatTheUserOwnsTest {

    private static final String A_PLIST = "<?xml version=\"1.0\"?><plist><dict></dict></plist>";
    private static final String THE_IPAD = "ipad-1";

    private OpenTagViewerDatabase db;
    private BeaconRepository repo;

    @Before
    public void openAnInMemoryDatabase() {
        this.db = Room.inMemoryDatabaseBuilder(
                        getInstrumentation().getTargetContext(), OpenTagViewerDatabase.class)
                .allowMainThreadQueries()
                .build();

        this.repo = new BeaconRepository(this.db, (plist, alignment) -> "{\"type\":\"accessory\"}");
    }

    @After
    public void closeIt() {
        this.db.close();
    }

    private static AccessoryRecords fromAccount(final String id) {
        // (beaconId, ownedBeaconPlist, namingRecordPlist, keyAlignmentPlist)
        return new AccessoryRecords(id, A_PLIST, null, A_PLIST);
    }

    /** The tag is already held, exactly as a previous account read would have left it. */
    private void givenTheTagIsAlreadyKnown() {
        this.repo.refreshAccountBeacons(List.of(fromAccount(THE_IPAD))).blockingFirst();
    }

    private void givenTheUserNamedIt(final String name, final String emoji) {
        this.db.userBeaconOptionsDao().insertAll(UserBeaconOptions.builder()
                .beaconId(THE_IPAD)
                .lastUpdate(1_000L)
                .uiName(name)
                .uiEmoji(emoji)
                .build());
    }

    private void givenItHasSomeHistory() {
        this.db.locationReportDao().insertAll(LocationReport.builder()
                .hashId("a-report")
                .beaconId(THE_IPAD)
                .publishedAt(1_000L)
                .description("Wi-Fi")
                .timestamp(1_000L)
                .confidence(0)
                .latitude(52.370216)
                .longitude(4.895168)
                .horizontalAccuracy(83)
                .status(144)
                .lastUpdate(1_000L)
                .build());
    }

    private UserBeaconOptions storedOptions() {
        return this.db.userBeaconOptionsDao().getById(THE_IPAD);
    }

    /**
     * <b>The reported bug.</b> A custom name and emoji must survive the next account read.
     *
     * <p>The user set these deliberately, on tags whose real names are unhelpful - two iPads, one
     * of them a stale duplicate. Losing them means doing the work again after every restart, and
     * nothing on screen explains why.
     */
    @Test
    public void acustomNameAndEmojiSurviveAnAccountRead() {
        this.givenTheTagIsAlreadyKnown();
        this.givenTheUserNamedIt("Studio iPad", "🎨");

        this.repo.refreshAccountBeacons(List.of(fromAccount(THE_IPAD))).blockingFirst();

        final UserBeaconOptions held = this.storedOptions();
        assertNotNull("the user's name and emoji were destroyed by an account read", held);
        assertEquals("Studio iPad", held.uiName);
        assertEquals("🎨", held.uiEmoji);
    }

    /**
     * And so must the location history.
     *
     * <p>The same cascade reaches {@code LocationReport}, which is worse: Apple keeps about seven
     * days, so history older than that exists only here. An account read that wipes it is
     * destroying the one copy.
     */
    @Test
    public void locationHistorySurvivesAnAccountRead() {
        this.givenTheTagIsAlreadyKnown();
        this.givenItHasSomeHistory();

        this.repo.refreshAccountBeacons(List.of(fromAccount(THE_IPAD))).blockingFirst();

        assertEquals("an account read destroyed the tag's location history",
                1, this.db.locationReportDao()
                        .getInTimeRange(THE_IPAD, 0L, 10_000L).size());
    }

    /**
     * The backoff state survives too.
     *
     * <p>Otherwise every six-hourly account read resets the silent-tag handling to zero, and a tag
     * that has been given up on quietly comes back to being scanned - which is the whole cost that
     * logic exists to avoid, paid four times a day.
     */
    @Test
    public void thesilentTagStateSurvivesAnAccountRead() {
        this.givenTheTagIsAlreadyKnown();
        this.db.ownedBeaconDao().markIgnored(THE_IPAD, 5_000L);

        this.repo.refreshAccountBeacons(List.of(fromAccount(THE_IPAD))).blockingFirst();

        final OwnedBeacon held = this.db.ownedBeaconDao().getById(THE_IPAD);
        assertNotNull(held);
        assertEquals("the tag was un-ignored by an account read",
                Long.valueOf(5_000L), held.ignoredAt);
        assertEquals("its strike count was reset by an account read", 1, held.fruitlessScans);
    }

    /**
     * What the account read <i>is</i> for still happens: the stored plists are brought up to date.
     *
     * <p>Guarding the fix from the other side. Making the write skip existing rows entirely would
     * pass every test above and quietly stop the app ever picking up a re-paired tag's new keys.
     */
    @Test
    public void thestoredRecordsAreStillUpdated() {
        this.givenTheTagIsAlreadyKnown();

        final String newer = "<?xml version=\"1.0\"?><plist><dict><key>v2</key></dict></plist>";
        this.repo.refreshAccountBeacons(
                        List.of(new AccessoryRecords(THE_IPAD, newer, null, newer)))
                .blockingFirst();

        final OwnedBeacon held = this.db.ownedBeaconDao().getById(THE_IPAD);
        assertNotNull(held);
        assertEquals("the account read stopped updating the stored plist", newer, held.content);
        assertEquals(newer, held.alignmentPlist);
    }

    /** A tag the user never named has no row, and the read must not invent one. */
    @Test
    public void atagWithNoCustomNameStillHasNone() {
        this.givenTheTagIsAlreadyKnown();

        this.repo.refreshAccountBeacons(List.of(fromAccount(THE_IPAD))).blockingFirst();

        assertNull(this.storedOptions());
    }

    private ImportData animportOf(final String beaconId, final String plist) {
        return new ImportData(
                Import.builder()
                        .version("0.0.2")
                        .importedAt(1_000L)
                        .exportedAt(1_000L)
                        .sourceUser("someone@example.com")
                        .exportedVia("OpenTagViewer.wizard:test")
                        .build(),
                new ArrayList<>(List.of(OwnedBeacon.builder()
                        .id(beaconId)
                        .content(plist)
                        .version("0.0.2")
                        .fromAccount(false)
                        .isRemoved(false)
                        .build())),
                new ArrayList<>());
    }

    /**
     * <b>The same defect on the import path, which is the one people repeat deliberately.</b>
     *
     * <p>Re-importing is not an error case: an export made after format {@code 0.0.2} carries a
     * key alignment record that an older one lacks, and the advice for a tag searching its whole
     * history is to export again. Doing that must not cost the user everything they have
     * accumulated for the tag - {@code addNewImport} says in its own javadoc that it updates
     * beacons that already exist, so it lands on exactly this cascade.
     */
    @Test
    public void areImportKeepsTheCustomNameAndTheHistory() throws Exception {
        this.repo.addNewImport(this.animportOf(THE_IPAD, A_PLIST)).blockingFirst();
        this.givenTheUserNamedIt("Studio iPad", "🎨");
        this.givenItHasSomeHistory();

        final String newer = "<?xml version=\"1.0\"?><plist><dict><key>v2</key></dict></plist>";
        this.repo.addNewImport(this.animportOf(THE_IPAD, newer)).blockingFirst();

        final UserBeaconOptions held = this.storedOptions();
        assertNotNull("re-importing destroyed the user's name and emoji", held);
        assertEquals("Studio iPad", held.uiName);
        assertEquals("re-importing destroyed the tag's location history",
                1, this.db.locationReportDao().getInTimeRange(THE_IPAD, 0L, 10_000L).size());
        assertEquals("re-importing did not update the stored plist",
                newer, this.db.ownedBeaconDao().getById(THE_IPAD).content);
    }
}
