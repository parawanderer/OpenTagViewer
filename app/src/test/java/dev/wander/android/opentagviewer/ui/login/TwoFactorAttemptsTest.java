package dev.wander.android.opentagviewer.ui.login;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When the app stops asking for a code and signs somebody out.
 *
 * <p>Tiny, and worth having exactly because of what the boundary costs: one attempt too few and
 * a person who fat-fingered a digit loses their session and has to find their Apple password;
 * one too many, or an off-by-one that never exhausts, and the overlay traps them forever on a
 * map that cannot work. Both are silent - neither throws.
 */
public class TwoFactorAttemptsTest {

    @Test
    public void afreshSessionHasEveryAttemptAvailable() {
        final TwoFactorAttempts attempts = new TwoFactorAttempts();

        assertEquals(TwoFactorAttempts.ALLOWED, attempts.remaining());
        assertFalse("nothing has been rejected yet", attempts.isExhausted());
    }

    /** The counting itself, one rejection at a time, down to the edge. */
    @Test
    public void eachRejectedCodeCostsExactlyOneAttempt() {
        final TwoFactorAttempts attempts = new TwoFactorAttempts();

        assertEquals(2, attempts.rejectedOne());
        assertFalse(attempts.isExhausted());

        assertEquals(1, attempts.rejectedOne());
        assertFalse("two wrong codes must not be enough to sign somebody out",
                attempts.isExhausted());

        assertEquals(0, attempts.rejectedOne());
        assertTrue("the third rejection is the one that gives up", attempts.isExhausted());
    }

    /**
     * <b>It never goes negative, and never un-exhausts.</b>
     *
     * <p>Sign-out is asynchronous - clearing the stored login and starting the login screen both
     * take a moment - so a code submitted in that window can land after the third. Counting on
     * past zero would make {@code remaining()} negative and any "attempts left" message absurd;
     * worse, an implementation that wrapped or reset would put the user back to three attempts
     * on a session already being discarded.
     */
    @Test
    public void rejectionsAfterTheLastOneChangeNothing() {
        final TwoFactorAttempts attempts = new TwoFactorAttempts();

        attempts.rejectedOne();
        attempts.rejectedOne();
        attempts.rejectedOne();

        assertEquals(0, attempts.rejectedOne());
        assertEquals(0, attempts.rejectedOne());
        assertEquals("attempts left must never read as negative", 0, attempts.remaining());
        assertTrue(attempts.isExhausted());
    }

    /**
     * <b>The number matches the login screen's hint threshold.</b>
     *
     * <p>Pinned rather than left as a coincidence: the two are the same number on purpose, so
     * the app has one notion of "enough wrong codes". Changing this should be a deliberate act
     * that also considers the other one.
     */
    @Test
    public void theallowanceIsThreeAndThatIsNotAnAccident() {
        assertEquals(3, TwoFactorAttempts.ALLOWED);
    }
}
