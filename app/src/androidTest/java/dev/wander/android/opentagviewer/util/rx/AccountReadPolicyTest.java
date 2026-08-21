package dev.wander.android.opentagviewer.util.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * When the app re-reads the Apple account on its own.
 *
 * <p>Pure bookkeeping, so it is tested by handing it clock values rather than by waiting. What it
 * decides is invisible either way: reading too often queues in front of the user's own work,
 * reading too rarely means a tag added in Find My never turns up, and neither throws.
 */
@RunWith(AndroidJUnit4.class)
public class AccountReadPolicyTest {

    private static final long SIX_HOURS = 6L * 60 * 60 * 1000;
    private static final long NOON = 1_700_000_000_000L;

    private AccountReadPolicy aPolicy() {
        return new AccountReadPolicy(SIX_HOURS);
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
}
