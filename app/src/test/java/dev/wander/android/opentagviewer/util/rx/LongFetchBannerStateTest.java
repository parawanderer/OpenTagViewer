package dev.wander.android.opentagviewer.util.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Tests for the "still locating your tags" banner's bookkeeping.
 * <br>
 * Fetches overlap here - a manual refresh can start while the periodic one is running, and the
 * import path starts one of its own - so the show/hide decisions are a counter, not a boolean.
 * Getting that counter wrong leaves a banner up forever or hides one that should still be
 * showing. Neither throws, so before this the only detector was a person watching the screen
 * for several minutes.
 */
public class LongFetchBannerStateTest {

    private LongFetchBannerState state;

    @Before
    public void setUp() {
        this.state = new LongFetchBannerState();
    }

    // --- showing and hiding ---------------------------------------------------------------

    @Test
    public void theFirstFetchAsksForTheBannerToBeScheduled() {
        assertTrue(this.state.fetchStarted());
        assertTrue(this.state.isFetching());
    }

    @Test
    public void anOverlappingFetchDoesNotRescheduleTheBanner() {
        this.state.fetchStarted();

        // Rescheduling would push the six-second delay out again on every refresh, so a
        // permanently busy app would never show the banner at all.
        assertFalse(this.state.fetchStarted());
    }

    @Test
    public void theBannerStaysUpUntilTheLastFetchFinishes() {
        this.state.fetchStarted();
        this.state.fetchStarted();

        assertFalse("one of two finishing must not hide it", this.state.fetchFinished());
        assertTrue(this.state.isFetching());

        assertTrue(this.state.fetchFinished());
        assertFalse(this.state.isFetching());
    }

    @Test
    public void aSingleFetchHidesTheBannerWhenItFinishes() {
        this.state.fetchStarted();
        assertTrue(this.state.fetchFinished());
    }

    /**
     * doFinally can run without a matching subscribe if a stream is disposed early, and a
     * counter allowed below zero would mean the next real start was not seen as the first -
     * so the banner would never be scheduled again for the rest of the session.
     */
    @Test
    public void anUnbalancedFinishCannotBreakTheNextFetch() {
        assertTrue(this.state.fetchFinished());
        this.state.fetchFinished();
        this.state.fetchFinished();

        assertFalse(this.state.isFetching());
        assertTrue("the next fetch must still schedule the banner", this.state.fetchStarted());
    }

    @Test
    public void aFinishedBatchCanStartAgain() {
        this.state.fetchStarted();
        this.state.fetchFinished();

        assertTrue(this.state.fetchStarted());
    }

    // --- only for tags with nothing to show ------------------------------------------------

    /** Three tags fetching; which of them already have a position or a Bluetooth sighting. */
    private static Predicate<String> found(final String... ids) {
        final Set<String> located = Set.of(ids);
        return located::contains;
    }

    private void aSlowBatchOf(final String... ids) {
        this.state.fetchStarted();
        this.state.lookingFor(List.of(ids));
        this.state.becameDue();
    }

    @Test
    public void allTagsNearbyMeansNoBanner() {
        aSlowBatchOf("a", "b", "c");

        // @parawanderer's case: every tag heard over Bluetooth, so there is nothing to wait for.
        assertFalse(this.state.shouldShow(found("a", "b", "c")));
    }

    @Test
    public void aTagWithNothingToShowPutsItUpAndIsCounted() {
        aSlowBatchOf("a", "b", "c");

        assertTrue(this.state.shouldShow(found("a")));
        assertEquals("only the tags still missing", 2, this.state.stillMissing(found("a")));
    }

    @Test
    public void itComesDownOnceTheLastMissingTagIsFound() {
        aSlowBatchOf("a", "b");
        assertTrue(this.state.shouldShow(found()));

        assertFalse("the batch may still be running", this.state.shouldShow(found("a", "b")));
        assertTrue(this.state.isFetching());
    }

    @Test
    public void nothingShowsBeforeTheDelayHasPassed() {
        this.state.fetchStarted();
        this.state.lookingFor(List.of("a"));

        assertFalse("a quick fetch should never put it up", this.state.shouldShow(found()));
    }

    @Test
    public void aNewBatchForgetsThePreviousBatchesTags() {
        aSlowBatchOf("a", "b", "c");
        this.state.fetchFinished();

        this.state.fetchStarted();

        // Otherwise a stale count flashes up before the new batch says what it fetches.
        assertEquals(0, this.state.stillMissing(found()));
        assertFalse(this.state.shouldShow(found()));
    }

    @Test
    public void anOverlappingFetchAddsItsTagsRatherThanReplacing() {
        aSlowBatchOf("a");

        this.state.fetchStarted();
        this.state.lookingFor(List.of("b"));

        assertEquals(2, this.state.stillMissing(found()));
    }

    @Test
    public void becomingDueAfterTheBatchEndedShowsNothing() {
        this.state.fetchStarted();
        this.state.lookingFor(List.of("a"));
        this.state.fetchFinished();

        // The delayed show can fire just after a quick batch finished.
        this.state.becameDue();

        assertFalse(this.state.shouldShow(found()));
    }
}
