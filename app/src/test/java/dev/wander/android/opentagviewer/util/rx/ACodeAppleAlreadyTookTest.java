package dev.wander.android.opentagviewer.util.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

/**
 * Telling a code Apple already took from a code the user got wrong.
 *
 * <p>The difference decides whether somebody is sent back to retype a code that can never be
 * accepted, and eventually told to change their Anisette server for a fault that had nothing to
 * do with Anisette.
 */
public class ACodeAppleAlreadyTookTest {

    /** How the failure actually arrives: a Chaquopy PyException carrying the Python class name. */
    private static Throwable fromTheBridge(final String message) {
        return new RuntimeException("com.chaquo.python.PyException: " + message);
    }

    @Test
    public void a503AfterTheCodeWasTakenIsRecognised() {
        assertTrue(ACodeAppleAlreadyTook.spentIt(fromTheBridge(
                "UnhandledProtocolError: Error response for GSA request: 503")));
    }

    @Test
    public void itIsFoundThroughAWrappingException() {
        final Throwable wrapped = new IllegalStateException("submitting the code failed",
                fromTheBridge("UnhandledProtocolError: Error response for GSA request: 503"));

        assertTrue("the bridge's exception is usually wrapped by the time a screen sees it",
                ACodeAppleAlreadyTook.spentIt(wrapped));
    }

    /**
     * <b>The one it must not claim.</b> A rejected code is a typo, and the screen's existing
     * behaviour - clear the box, let them try again - is exactly right for it.
     */
    @Test
    public void aRejectedCodeIsNotThis() {
        assertFalse(ACodeAppleAlreadyTook.spentIt(fromTheBridge(
                "InvalidCredentialsError: The verification code was not accepted")));
    }

    @Test
    public void nothingAtAllIsNotThis() {
        assertFalse(ACodeAppleAlreadyTook.spentIt(null));
        assertFalse(ACodeAppleAlreadyTook.spentIt(new RuntimeException()));
    }

    /** A cycle in the cause chain must not hang the screen. See ICloudFailures, same guard. */
    @Test(timeout = 2000)
    public void aSelfReferencingCauseDoesNotSpin() {
        final Throwable loop = new RuntimeException("something") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertFalse(ACodeAppleAlreadyTook.spentIt(loop));
    }

    // ---------------------------------------------------------------- the waits

    /**
     * <b>These numbers are a measurement, and this test exists so lowering them is a decision.</b>
     *
     * <p>The one observed recovery: the 503, then a full manual round - Apple ID, password,
     * choose delivery, wait, type the code - which was refused at the password step, then another
     * round that worked. A round is the better part of a minute, so the account was still
     * refusing about a minute after the 503, and what worked was roughly two rounds out.
     *
     * <p>So a first wait materially under a minute is known to be too short. If this test is in
     * the way, the thing to change is the evidence, not the constant.
     */
    @Test
    public void theFirstWaitIsNotShorterThanAMinute() {
        assertTrue("a wait under a minute is known to be too short - see the class comment",
                ACodeAppleAlreadyTook.waitBefore(0) >= TimeUnit.SECONDS.toMillis(60));
    }

    @Test
    public void theSecondWaitIsLongerStill() {
        assertTrue("the second attempt has to reach further out than the first",
                ACodeAppleAlreadyTook.waitBefore(1) >= TimeUnit.SECONDS.toMillis(120));
    }

    @Test
    public void thereAreExactlyTwoAttemptsAndThenItGivesUp() {
        assertEquals(2, ACodeAppleAlreadyTook.WAITS_MS.length);
        assertEquals("past the last wait there is nothing left to try",
                -1, ACodeAppleAlreadyTook.waitBefore(2));
        assertEquals(-1, ACodeAppleAlreadyTook.waitBefore(99));
    }

    @Test
    public void aNonsenseAttemptNumberDoesNotWait() {
        assertEquals(-1, ACodeAppleAlreadyTook.waitBefore(-1));
    }
}
