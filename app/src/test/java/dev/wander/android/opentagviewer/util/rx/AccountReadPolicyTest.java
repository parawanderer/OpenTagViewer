package dev.wander.android.opentagviewer.util.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When the app re-reads the Apple account on its own.
 *
 * <p>Pure bookkeeping, so it is tested by handing it clock values rather than by waiting. What it
 * decides is invisible either way: reading too often queues in front of the user's own work,
 * reading too rarely means a tag added in Find My never turns up, and neither throws.
 *
 * <p><b>On the JVM, where it always belonged.</b> This ran on the emulator for arithmetic and
 * three booleans - see AGENTS.md rule 13. Moving it also made the change below visible: adding a
 * parameter to the constructor broke nothing in the JVM suite, because the only test of this
 * class was somewhere the JVM suite does not look.
 */
public class AccountReadPolicyTest {

    private static final long SIX_HOURS = 6L * 60 * 60 * 1000;
    private static final long FIFTEEN_MINUTES = 15L * 60 * 1000;
    private static final long A_MINUTE = 60L * 1000;
    private static final long NOON = 1_700_000_000_000L;

    private AccountReadPolicy aPolicy() {
        return new AccountReadPolicy(SIX_HOURS, A_MINUTE);
    }

    /** What the app actually ships now: fifteen minutes on the timer, one on resume. */
    private AccountReadPolicy theRealOne() {
        return new AccountReadPolicy(FIFTEEN_MINUTES, A_MINUTE);
    }

    /** Nothing to read, and nothing to say about it. */
    @Test
    public void anaccountThatWasNeverLinkedIsNeverRead() {
        assertEquals(AccountReadPolicy.Decision.NOT_LINKED,
                this.aPolicy().decide(NOON, false, false));
    }

    /**
     * <b>The first tick after linking reads.</b>
     *
     * <p>"Never read" is not "read at the epoch": somebody who has just connected an account
     * expects their tags shortly, not in six hours.
     */
    @Test
    public void thefirstTickAfterLinkingReads() {
        assertTrue(this.aPolicy().decide(NOON, true, false).shouldRead());
    }

    @Test
    public void asecondTickStraightAfterwardsDoesNot() {
        final AccountReadPolicy policy = this.aPolicy();
        policy.markRead(NOON);

        assertEquals(AccountReadPolicy.Decision.TOO_SOON,
                policy.decide(NOON + 1000, true, false));
    }

    @Test
    public void oncetheIntervalHasPassedItReadsAgain() {
        final AccountReadPolicy policy = this.aPolicy();
        policy.markRead(NOON);

        assertTrue(policy.decide(NOON + SIX_HOURS, true, false).shouldRead());
    }

    /**
     * <b>Busy wins over due, and that order is the point.</b>
     *
     * <p>Calls into Python are serialised and a location fetch for one accessory can run for
     * minutes. A read that decided to go while one was running would not wait politely - it would
     * take the lock the moment that fetch released it, ahead of whatever the user did next.
     */
    @Test
    public void abusyInterpreterIsWaitedOutRatherThanQueuedBehind() {
        final AccountReadPolicy policy = this.aPolicy();
        policy.markRead(NOON - SIX_HOURS * 2);

        assertEquals("a read that is due must still yield to work already running",
                AccountReadPolicy.Decision.BUSY, policy.decide(NOON, true, true));
    }

    /** And skipping for busy does not count as having read - the next free tick still goes. */
    @Test
    public void skippingBecauseOfBusyDoesNotCountAsAread() {
        final AccountReadPolicy policy = this.aPolicy();

        policy.decide(NOON, true, true);

        assertTrue("the skipped read must still be owed", policy.decide(NOON, true, false).shouldRead());
        assertFalse(policy.hasEverRead());
    }

    /**
     * The time recorded is when the read <b>started</b>.
     *
     * <p>A read that took twenty minutes behind a queue must not immediately earn another one for
     * having finished twenty minutes later.
     */
    @Test
    public void theintervalRunsFromWhenTheReadStarted() {
        final AccountReadPolicy policy = this.aPolicy();
        final long started = NOON;

        policy.markRead(started);

        assertEquals(AccountReadPolicy.Decision.TOO_SOON,
                policy.decide(started + SIX_HOURS - 1, true, false));
        assertTrue(policy.decide(started + SIX_HOURS, true, false).shouldRead());
    }

    /**
     * Unlinking forgets when it last read.
     *
     * <p>Otherwise linking a different account would wait out an interval measured against the
     * previous one, and the tags would not appear for hours with nothing explaining why.
     */
    @Test
    public void unlinkingMeansTheNextLinkReadsImmediately() {
        final AccountReadPolicy policy = this.aPolicy();
        policy.markRead(NOON);

        policy.forget();

        assertTrue(policy.decide(NOON + 1000, true, false).shouldRead());
    }

    /** Every decision says why, because this is only ever seen in a log. */
    @Test
    public void everyDecisionExplainsItself() {
        for (final AccountReadPolicy.Decision decision : AccountReadPolicy.Decision.values()) {
            assertFalse(decision + " has nothing to say", decision.reason().isBlank());
        }
    }

    /**
     * <b>Resuming reads sooner than the timer would, and that difference is the feature.</b>
     *
     * <p>Six hours was the old interval, and an iPad picks up a renamed AirTag in seconds - so a
     * tag renamed on somebody's own iPad kept its old name here for the rest of the afternoon.
     * The moment that is noticed is the moment the app is opened, which is why resuming has a
     * floor of its own rather than sharing the timer's.
     *
     * <p>Asserted as the difference rather than as two numbers: at five minutes one goes and the
     * other does not, and swapping the intervals flips exactly this.
     */
    @Test
    public void resumingReadsSoonerThanTheTimerWould() {
        final AccountReadPolicy policy = theRealOne();
        policy.markRead(NOON);

        final long fiveMinutesLater = NOON + 5L * 60 * 1000;

        assertFalse("the timer should still be waiting at five minutes",
                policy.decide(fiveMinutesLater, true, false).shouldRead());
        assertTrue("opening the app at five minutes should read",
                policy.decideOnResume(fiveMinutesLater, true, false).shouldRead());
    }

    /** But resuming still has a floor, or flicking between two apps costs a Python call each. */
    @Test
    public void resumingTwiceInAMomentOnlyReadsOnce() {
        final AccountReadPolicy policy = theRealOne();
        policy.markRead(NOON);

        assertEquals(AccountReadPolicy.Decision.TOO_SOON,
                policy.decideOnResume(NOON + 30_000L, true, false));
        assertTrue("and it opens up again once the floor has passed",
                policy.decideOnResume(NOON + A_MINUTE + 1, true, false).shouldRead());
    }

    /** The timer does come round, and fifteen minutes is when. */
    @Test
    public void thetimerReadsAgainAfterFifteenMinutes() {
        final AccountReadPolicy policy = theRealOne();
        policy.markRead(NOON);

        assertFalse(policy.decide(NOON + FIFTEEN_MINUTES - 1, true, false).shouldRead());
        assertTrue(policy.decide(NOON + FIFTEEN_MINUTES, true, false).shouldRead());
    }

    /** Resuming is subject to the same two guards as the timer, not a way round them. */
    @Test
    public void resumingObeysBusyAndUnlinkedToo() {
        assertEquals(AccountReadPolicy.Decision.NOT_LINKED,
                theRealOne().decideOnResume(NOON, false, false));
        assertEquals(AccountReadPolicy.Decision.BUSY,
                theRealOne().decideOnResume(NOON, true, true));
    }

    /**
     * And zero really is "never read" rather than "read in 1970".
     *
     * <p>{@code hasEverRead()} is {@code lastReadAt > 0}, so marking a read at the epoch marks
     * nothing - which is worth pinning, because a test that does it by accident passes every
     * interval check and looks like the policy is broken.
     */
    @Test
    public void markingAReadAtTheEpochMarksNothing() {
        final AccountReadPolicy policy = theRealOne();
        policy.markRead(0L);

        assertTrue(policy.decide(1L, true, false).shouldRead());
    }
}
