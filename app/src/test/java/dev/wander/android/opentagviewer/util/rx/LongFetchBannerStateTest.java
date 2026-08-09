package dev.wander.android.opentagviewer.util.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

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

    // --- the count ------------------------------------------------------------------------

    @Test
    public void aNewBatchClearsThePreviousBatchesCount() {
        this.state.fetchStarted();
        this.state.setProgress(3, 5);
        this.state.fetchFinished();

        this.state.fetchStarted();

        // Otherwise "3 of 5" flashes up before the new batch reports its own total.
        assertFalse(this.state.hasCount());
        assertEquals(0, this.state.total());
    }

    @Test
    public void anOverlappingFetchDoesNotClearTheCountInProgress() {
        this.state.fetchStarted();
        this.state.setProgress(2, 6);

        this.state.fetchStarted();

        assertTrue(this.state.hasCount());
        assertEquals(3, this.state.displayedPosition());
    }

    @Test
    public void showsNoCountForASingleTag() {
        this.state.fetchStarted();
        this.state.setProgress(0, 1);

        // "1 of 1" tells the user nothing they cannot already see.
        assertFalse(this.state.hasCount());
    }

    @Test
    public void showsNoCountBeforeTheTotalIsKnown() {
        this.state.fetchStarted();

        assertFalse(this.state.hasCount());
    }

    @Test
    public void countsTheTagBeingWorkedOnRatherThanTheOnesFinished() {
        this.state.fetchStarted();
        this.state.setProgress(0, 3);

        // Zero done means the first one is in progress, so it reads "1 of 3", not "0 of 3".
        assertEquals(1, this.state.displayedPosition());
        assertEquals(3, this.state.total());

        this.state.setProgress(1, 3);
        assertEquals(2, this.state.displayedPosition());
    }

    @Test
    public void theCountCannotOvershootTheTotal() {
        this.state.fetchStarted();
        this.state.setProgress(3, 3);

        // The final completion reports 3 of 3 done, which would otherwise read "4 of 3" in
        // the moment between the last accessory finishing and the banner being hidden.
        assertEquals(3, this.state.displayedPosition());
    }
}
