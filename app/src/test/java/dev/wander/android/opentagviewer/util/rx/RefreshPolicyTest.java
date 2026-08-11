package dev.wander.android.opentagviewer.util.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.wander.android.opentagviewer.util.rx.RefreshPolicy.Decision;

/**
 * Tests for when the map refreshes and how much history it asks for.
 * <br>
 * Every rule here was previously a branch inside a scheduled callback in {@code MapsActivity},
 * reachable only by leaving the app open for a minute and watching. Both bugs it encodes were
 * found that way: refreshes queueing up behind a long fetch, and the refresh button appearing
 * to spin forever.
 */
public class RefreshPolicyTest {

    private static final long ONE_MINUTE = 60_000L;
    private static final long ONE_HOUR = 60L * ONE_MINUTE;
    private static final int MAX_HOURS = 24;

    /** A plausible wall-clock reading, since that is what the caller passes in. */
    private static final long NOW = 1_754_700_000_000L;

    private RefreshPolicy policy() {
        return new RefreshPolicy(ONE_MINUTE, MAX_HOURS);
    }

    private Decision decide(final RefreshPolicy policy, final long now) {
        return policy.decide(now, true, true, false);
    }

    // --- when a refresh is allowed --------------------------------------------------------

    @Test
    public void refreshesOnceTheIntervalHasPassed() {
        final RefreshPolicy policy = policy();
        policy.markFetched(0L);

        final Decision decision = this.decide(policy, ONE_MINUTE);

        assertTrue(decision.shouldRefresh());
        assertEquals(Decision.REFRESH, decision);
    }

    @Test
    public void refreshesOnTheFirstTickWhenNothingHasBeenFetchedYet() {
        // now is wall-clock milliseconds, so an unset lastFetchAt of 0 is decades in the past.
        assertTrue(this.decide(policy(), NOW).shouldRefresh());
    }

    @Test
    public void waitsUntilTheFullIntervalHasPassed() {
        final RefreshPolicy policy = policy();
        policy.markFetched(0L);

        assertEquals(Decision.TOO_SOON, this.decide(policy, ONE_MINUTE - 1));
    }

    @Test
    public void doesNotRefreshBeforeTheAppleServiceExists() {
        assertEquals(Decision.SERVICE_NOT_READY, policy().decide(ONE_HOUR, false, true, false));
    }

    @Test
    public void doesNotRefreshBeforeTheFirstFetchHasFinished() {
        assertEquals(Decision.INITIAL_FETCH_INCOMPLETE, policy().decide(ONE_HOUR, true, false, false));
    }

    /**
     * The backlog bug.
     * <br>
     * Calls into Python are serialised, so without this check the tick does not skip - it
     * blocks on the lock. During a multi-minute first fetch the queue grew by one a minute and
     * then fired all at once.
     */
    @Test
    public void skipsTheTickWhileAFetchIsStillRunning() {
        final RefreshPolicy policy = policy();
        policy.markFetched(0L);

        // Long past the interval, so only the in-progress check can be holding it back.
        assertEquals(Decision.FETCH_IN_PROGRESS, policy.decide(ONE_HOUR, true, true, true));
    }

    @Test
    public void skippingWhileBusyDoesNotConsumeTheInterval() {
        final RefreshPolicy policy = policy();
        policy.markFetched(0L);

        assertFalse(policy.decide(ONE_HOUR, true, true, true).shouldRefresh());
        // The skipped tick must not count as a refresh, or the next one waits another minute.
        assertTrue(policy.decide(ONE_HOUR, true, true, false).shouldRefresh());
    }

    @Test
    public void reportsAReadableReasonForEveryRefusal() {
        for (final Decision decision : Decision.values()) {
            assertFalse("reason must not be blank: " + decision, decision.reason().trim().isEmpty());
        }
    }

    // --- how far back to fetch ------------------------------------------------------------

    @Test
    public void asksForTheFullWindowWhenNothingHasBeenFetchedYet() {
        assertEquals(MAX_HOURS, policy().hoursToGoBack(ONE_HOUR * 100));
    }

    @Test
    public void capsTheWindowAtTheMaximum() {
        final RefreshPolicy policy = policy();
        policy.markFetched(0L);

        // A tag left alone for a week still only asks for 24 hours.
        assertEquals(MAX_HOURS, policy.hoursToGoBack(ONE_HOUR * 24 * 7));
    }

    @Test
    public void roundsUpSoNoWindowIsMissed() {
        final RefreshPolicy policy = policy();
        policy.markFetched(0L);

        // 90 minutes since the last fetch needs 2 hours; 1 would drop the first half hour.
        assertEquals(2, policy.hoursToGoBack(ONE_HOUR + 30 * ONE_MINUTE));
        assertEquals(1, policy.hoursToGoBack(ONE_MINUTE));
    }

    /**
     * Asking Apple for zero hours returns nothing, and nothing is indistinguishable from a tag
     * that has not been seen - the refresh would look successful and the tag would look lost.
     * Reachable from the manual refresh button, which is not interval-gated.
     */
    @Test
    public void neverAsksForZeroHours() {
        final RefreshPolicy policy = policy();
        policy.markFetched(1_000L);

        assertEquals(1, policy.hoursToGoBack(1_000L));
        assertEquals(1, policy.hoursToGoBack(1_001L));
    }

    @Test
    public void neverAsksForANegativeWindowIfTheClockGoesBackwards() {
        final RefreshPolicy policy = policy();
        policy.markFetched(ONE_HOUR * 5);

        // A device time change, or an NTP correction, must not produce a nonsense request.
        assertEquals(1, policy.hoursToGoBack(ONE_HOUR));
    }

    // --- bookkeeping ----------------------------------------------------------------------

    @Test
    public void recordsTheTimeTheFetchStartedRatherThanWhenItFinished() {
        final RefreshPolicy policy = policy();
        final long fetchStarted = ONE_HOUR;

        policy.markFetched(fetchStarted);

        // Recording the finish time would leave a gap in history the width of the fetch,
        // which for a tag with no alignment record is minutes.
        assertEquals(fetchStarted, policy.lastFetchAt());
        assertEquals(ONE_MINUTE, policy.millisSinceLastFetch(fetchStarted + ONE_MINUTE));
    }

    @Test
    public void saysSoWhenThereHasBeenNoFetchAtAll() {
        // Reporting the interval since the epoch reads as a 56-year-old fetch, which sends
        // whoever is reading the log looking for a clock bug that is not there.
        assertFalse(policy().hasEverFetched());
        assertEquals("no successful fetch yet", policy().describeTimeSinceLastFetch(NOW));
    }

    @Test
    public void reportsTheRealIntervalOnceSomethingHasBeenFetched() {
        final RefreshPolicy policy = policy();
        policy.markFetched(NOW);

        assertTrue(policy.hasEverFetched());
        assertEquals("60000 ms since the last fetch", policy.describeTimeSinceLastFetch(NOW + ONE_MINUTE));
    }

    @Test
    public void aSuccessfulRefreshStartsTheIntervalAgain() {
        final RefreshPolicy policy = policy();
        policy.markFetched(0L);

        assertTrue(this.decide(policy, ONE_MINUTE).shouldRefresh());
        policy.markFetched(ONE_MINUTE);
        assertEquals(Decision.TOO_SOON, this.decide(policy, ONE_MINUTE + 1));
    }

    // ------------------------------------------------------------------------------------
    // The shared instance.
    //
    // Changing the theme, language or map provider makes AppCompat relaunch every activity in
    // the process. Held per activity, this was rebuilt with it - so the map came back
    // believing it had never fetched and asked Apple for every tag's history again. Toggling
    // dark mode three times meant three full fetches.
    // ------------------------------------------------------------------------------------

    @Test
    public void everybodyGetsTheSamePolicy() {
        RefreshPolicy.resetShared();

        assertSame("a rebuilt screen must not get a policy of its own",
                RefreshPolicy.shared(ONE_MINUTE, 24), RefreshPolicy.shared(ONE_MINUTE, 24));
    }

    /**
     * The point of sharing it: a fetch made before a rebuild still counts afterwards.
     */
    @Test
    public void aFetchIsStillRememberedAfterTheScreenIsRebuilt() {
        RefreshPolicy.resetShared();

        // The screen fetches, then something recreates it...
        RefreshPolicy.shared(ONE_MINUTE, 24).markFetched(NOW);

        // ...and the rebuilt one asks whether it should fetch again.
        final RefreshPolicy afterRebuild = RefreshPolicy.shared(ONE_MINUTE, 24);

        assertTrue("it has fetched, and must know it", afterRebuild.hasEverFetched());
        assertEquals("asking Apple again immediately is the bug",
                Decision.TOO_SOON, this.decide(afterRebuild, NOW + 1));
    }

    /** And once the interval really has passed, it fetches as normal. */
    @Test
    public void theSharedPolicyStillAllowsARefreshWhenTheIntervalHasPassed() {
        RefreshPolicy.resetShared();
        RefreshPolicy.shared(ONE_MINUTE, 24).markFetched(NOW);

        assertTrue(this.decide(RefreshPolicy.shared(ONE_MINUTE, 24), NOW + ONE_MINUTE + 1)
                .shouldRefresh());
    }
}
