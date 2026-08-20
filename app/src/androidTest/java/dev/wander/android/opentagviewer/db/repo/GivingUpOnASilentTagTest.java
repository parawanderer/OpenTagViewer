package dev.wander.android.opentagviewer.db.repo;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AccessoryRequest;
import dev.wander.android.opentagviewer.python.FetchResult;
import dev.wander.android.opentagviewer.util.rx.WideScanBackoff;

/**
 * Noticing that a tag has stopped broadcasting, and asking about it less.
 *
 * <p><b>Silent tags are the expensive ones.</b> A tag with no key alignment record searches from
 * its pairing date at a request per ~290 keys, so one that nobody has walked past costs hundreds
 * of requests where a healthy tag costs one - and it costs them again on every refresh. Left
 * alone the app spends most of its conversation with Apple on the answers least likely to come,
 * which is rule 6's account-flagging risk arriving through a different door.
 *
 * <p><b>Three outcomes, not two, and the distinctions are the whole feature.</b> Something found
 * clears everything. Nothing found lengthens the wait a little, because a fortnight in a drawer
 * is a normal tag having a normal week. Nothing found across <i>months</i> of history is
 * different in kind, and only that one is given up on.
 *
 * <p>None of this is visible when it goes wrong. Too eager is a quiet flood of requests; too keen
 * to give up is an app that stops looking for somebody's bike.
 */
@RunWith(AndroidJUnit4.class)
public class GivingUpOnASilentTagTest {

    private static final String A_TAG = "a-tag";
    private static final String A_PLIST = "<?xml version=\"1.0\"?><plist><dict></dict></plist>";

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

    private OwnedBeacon stored() {
        return this.db.ownedBeaconDao().getById(A_TAG);
    }

    private void storeAFetchThatFound(final boolean anything, final boolean searchedWide) {
        final Map<String, List<BeaconLocationReport>> reports = Map.of(
                A_TAG, anything
                        ? List.of(BeaconLocationReport.builder()
                                .timestamp(1L).publishedAt(1L).description("somewhere")
                                .latitude(1).longitude(1).build())
                        : List.of());

        this.repo.storeFetchResult(new FetchResult(
                reports,
                Collections.emptyMap(),
                searchedWide ? Set.of(A_TAG) : Set.of())).blockingFirst();
    }

    /** A search that found something clears everything held against the tag. */
    @Test
    public void atagThatReportsIsAnOrdinaryTagAgain() {
        this.storeAFetchThatFound(false, false);
        this.storeAFetchThatFound(false, false);
        assertEquals(2, this.stored().fruitlessScans);

        this.storeAFetchThatFound(true, false);

        assertEquals("a tag that answered must go back to being asked normally",
                0, this.stored().fruitlessScans);
        assertNull(this.stored().ignoredAt);
        assertNotNull("the attempt should still be recorded", this.stored().lastScanAt);
    }

    /** An ordinary empty search lengthens the wait, and nothing more. */
    @Test
    public void anemptySearchBacksOffRatherThanGivingUp() {
        this.storeAFetchThatFound(false, false);

        assertEquals(1, this.stored().fruitlessScans);
        assertNull("one quiet week is not evidence of anything", this.stored().ignoredAt);
    }

    /**
     * <b>Only a search covering months of history gives up.</b>
     *
     * <p>The distinction @parawanderer insisted on: a young tag searching a short history and
     * finding nothing means very little, and treating that as death would set aside tags that
     * were about to report.
     */
    @Test
    public void asearchAcrossMonthsOfHistoryWithNothingInItGivesUp() {
        this.storeAFetchThatFound(false, true);

        assertNotNull("a tag silent across its whole history should be set aside",
                this.stored().ignoredAt);
    }

    /** And being given up on is undone by anything at all being found later. */
    @Test
    public void agivenUpTagComesBackTheMomentItIsFound() {
        this.storeAFetchThatFound(false, true);
        assertNotNull(this.stored().ignoredAt);

        this.storeAFetchThatFound(true, false);

        assertNull("a tag that reported must stop being ignored", this.stored().ignoredAt);
        assertEquals(0, this.stored().fruitlessScans);
    }

    // ------------------------------------------------------------- what the scheduler asks for

    private List<AccessoryRequest> scheduledRequests() {
        return this.repo.toScheduledAccessoryRequests(
                BeaconRepository.plistFallback(A_TAG, A_PLIST)).blockingFirst();
    }

    private List<AccessoryRequest> manualRequests() {
        return this.repo.toAccessoryRequests(
                BeaconRepository.plistFallback(A_TAG, A_PLIST)).blockingFirst();
    }

    /** A healthy tag is asked about. */
    @Test
    public void ahealthyTagIsIncludedInTheScheduledFetch() {
        assertEquals(1, this.scheduledRequests().size());
    }

    /** A tag given up on is skipped entirely - it is the expensive one. */
    @Test
    public void agivenUpTagIsSkippedByTheScheduledFetch() {
        this.storeAFetchThatFound(false, true);

        assertTrue("an ignored tag must not be searched for automatically",
                this.scheduledRequests().isEmpty());
    }

    /** And so is one still inside its backoff. */
    @Test
    public void atagInsideItsBackoffIsSkippedByTheScheduledFetch() {
        for (int i = 0; i < 4; i++) {
            this.storeAFetchThatFound(false, false);
        }
        assertTrue("this test needs a backoff long enough to still be waiting",
                WideScanBackoff.waitMillisAfter(this.stored().fruitlessScans) > 0);

        assertTrue(this.scheduledRequests().isEmpty());
    }

    /**
     * <b>But a manual refresh asks anyway.</b>
     *
     * <p>The property that keeps this feature from becoming its own bug. Somebody who opens a tag
     * and presses "check now" has just overridden the app's judgement, which they are entitled to
     * do - they may have found the thing. Backing off a button somebody pressed would look
     * exactly like the app ignoring them.
     */
    @Test
    public void amanualRefreshIsNeverThrottledOrSkipped() {
        this.storeAFetchThatFound(false, true);
        assertTrue("premise: the scheduler has given up on it",
                this.scheduledRequests().isEmpty());

        assertEquals("a tag somebody asked about must always be asked about",
                1, this.manualRequests().size());
    }
}
