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

import java.util.List;
import java.util.stream.Collectors;

import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.icloud.AccessoryRecords;

/**
 * Bringing the beacons held for an Apple account into line with what it holds.
 *
 * <p><b>The property being protected is not "the refresh works".</b> It is that a refresh cannot
 * reach a file-imported tag. Those rows are the only copy anyone has - the export they came from
 * may be long gone, and {@code allowBackup} is false - so a refresh that deleted one would be
 * unrecoverable data loss triggered by an ordinary action.
 */
@RunWith(AndroidJUnit4.class)
public class AccountBeaconRefreshTest {

    private static final String A_PLIST = "<?xml version=\"1.0\"?><plist><dict></dict></plist>";

    private OpenTagViewerDatabase db;
    private BeaconRepository repo;

    @Before
    public void openAnInMemoryDatabase() {
        this.db = Room.inMemoryDatabaseBuilder(
                getInstrumentation().getTargetContext(), OpenTagViewerDatabase.class)
                .allowMainThreadQueries()
                .build();

        // The real converter needs a running Python runtime; nothing here is about conversion.
        this.repo = new BeaconRepository(this.db, (plist, alignment) -> "{\"type\":\"accessory\"}");
    }

    @After
    public void closeIt() {
        this.db.close();
    }

    private static AccessoryRecords fromAccount(final String id) {
        return new AccessoryRecords(id, A_PLIST, A_PLIST, null);
    }

    private void givenAFileImportedBeacon(final String id) {
        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(id)
                .content(A_PLIST)
                .version("0.0.2")
                .fromAccount(false)
                .isRemoved(false)
                .build());
    }

    private List<String> liveBeaconIds() {
        return this.db.ownedBeaconDao().getAll().stream()
                .map(beacon -> beacon.id)
                .collect(Collectors.toList());
    }

    @Test
    public void whatIsOnTheAccountIsWritten() {
        this.repo.refreshAccountBeacons(List.of(fromAccount("a"), fromAccount("b")))
                .blockingFirst();

        assertEquals(2, this.liveBeaconIds().size());
        assertTrue(this.liveBeaconIds().containsAll(List.of("a", "b")));
    }

    @Test
    public void whatIsWrittenIsMarkedAsComingFromTheAccount() {
        this.repo.refreshAccountBeacons(List.of(fromAccount("a"))).blockingFirst();

        assertEquals(List.of("a"), this.db.ownedBeaconDao().getAccountBeaconIds());
    }

    /** A tag that has left the account goes from here too - these rows are a cache. */
    @Test
    public void whatHasLeftTheAccountIsRetired() {
        this.repo.refreshAccountBeacons(List.of(fromAccount("a"), fromAccount("b")))
                .blockingFirst();

        this.repo.refreshAccountBeacons(List.of(fromAccount("a"))).blockingFirst();

        assertEquals(List.of("a"), this.liveBeaconIds());
    }

    /**
     * <b>The one that matters.</b>
     *
     * <p>A file-imported tag is untouched by a refresh, including a refresh that finds nothing.
     * Without the {@code from_account} scope this test is what fails - and in production it would
     * be somebody's tags, gone, with no export to redo them from.
     */
    @Test
    public void afileImportedBeaconIsNeverTouchedByARefresh() {
        this.givenAFileImportedBeacon("from-a-zip");

        this.repo.refreshAccountBeacons(List.of(fromAccount("a"))).blockingFirst();
        assertTrue("a refresh removed a file-imported tag",
                this.liveBeaconIds().contains("from-a-zip"));

        this.repo.refreshAccountBeacons(List.of()).blockingFirst();
        assertTrue("an empty account removed a file-imported tag",
                this.liveBeaconIds().contains("from-a-zip"));
    }

    /** An account that now holds nothing retires its own rows - and only its own. */
    @Test
    public void anemptyAccountRetiresOnlyItsOwn() {
        this.givenAFileImportedBeacon("from-a-zip");
        this.repo.refreshAccountBeacons(List.of(fromAccount("a"))).blockingFirst();

        this.repo.refreshAccountBeacons(List.of()).blockingFirst();

        assertEquals(List.of("from-a-zip"), this.liveBeaconIds());
        assertTrue(this.db.ownedBeaconDao().getAccountBeaconIds().isEmpty());
    }

    /**
     * A tag that left and came back is live again.
     *
     * <p>The row is retired rather than deleted, so re-inserting it has to clear that flag or it
     * comes back invisible - present in the table, absent from every screen.
     */
    @Test
    public void atagThatComesBackIsLiveAgain() {
        this.repo.refreshAccountBeacons(List.of(fromAccount("a"))).blockingFirst();
        this.repo.refreshAccountBeacons(List.of()).blockingFirst();
        assertFalse(this.liveBeaconIds().contains("a"));

        this.repo.refreshAccountBeacons(List.of(fromAccount("a"))).blockingFirst();

        assertTrue("a tag that returned to the account stayed hidden",
                this.liveBeaconIds().contains("a"));
    }

    /** Refreshing twice with the same account does not duplicate anything. */
    @Test
    public void refreshingTwiceChangesNothing() {
        this.repo.refreshAccountBeacons(List.of(fromAccount("a"), fromAccount("b")))
                .blockingFirst();
        this.repo.refreshAccountBeacons(List.of(fromAccount("a"), fromAccount("b")))
                .blockingFirst();

        assertEquals(2, this.liveBeaconIds().size());
    }

    /** An accessory nothing ever named still gets a row - the app can show one of those. */
    @Test
    public void anaccessoryWithNoNamingRecordIsStillWritten() {
        this.repo.refreshAccountBeacons(
                List.of(new AccessoryRecords("nameless", A_PLIST, null, null))).blockingFirst();

        assertTrue(this.liveBeaconIds().contains("nameless"));
    }
}
