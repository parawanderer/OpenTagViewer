package dev.wander.android.opentagviewer.python.icloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * A 503 from Apple is its own answer, and specifically not the two it used to be mistaken for.
 *
 * <p>Issue #176: one account met the same 503 at three call sites in three minutes. Nothing
 * distinguished it, so it arrived as {@code UNKNOWN} and the screen showed an HTTP status and a
 * sentence about Grand Slam - which reads as a bug in this app, and was reported as one.
 *
 * <p>Pure mapping and a pure predicate, so JVM - rule 13.
 */
public class AppleDecliningIsItsOwnFailureTest {

    @Test
    public void thebridgesWireValueMaps() {
        assertEquals(ICloudFailure.APPLE_DECLINED, ICloudFailure.fromWire("apple_declined"));
    }

    /**
     * <b>The expensive mistake, in the direction that costs a working session.</b>
     *
     * <p>{@code CREDENTIALS_REJECTED} ends in {@code SignInAgain}. Reaching it over a fault that
     * clears itself in minutes signs somebody out for nothing.
     */
    @Test
    public void itisNotRejectedCredentials() {
        assertNotEquals(ICloudFailure.CREDENTIALS_REJECTED, ICloudFailure.fromWire("apple_declined"));
    }

    /**
     * And it must not reach the sign-out path through the shared predicate either.
     *
     * <p>Rule 15's whole point is that one question decides this for every screen. A new failure
     * that answered it wrongly would take every caller with it.
     */
    @Test
    public void itdoesNotAskAnybodyToSignInAgain() {
        assertFalse(ICloudFailures.meansSignInAgain(
                new ICloudException(ICloudFailure.APPLE_DECLINED, "refused with HTTP 503")));
    }

    /**
     * <b>The other direction: it is no longer UNKNOWN.</b>
     *
     * <p>Which is what it was when #176 was filed.
     */
    @Test
    public void itisNoLongerUnknown() {
        assertNotEquals(ICloudFailure.UNKNOWN, ICloudFailure.fromWire("apple_declined"));
    }

    /**
     * An unrecognised reason still degrades rather than throwing.
     *
     * <p>A reason added in Python and not yet known here has to become a poor screen, never a
     * crash on the screen that reports failures.
     */
    @Test
    public void areasonThisBuildHasNeverHeardOfIsStillUnknown() {
        assertEquals(ICloudFailure.UNKNOWN, ICloudFailure.fromWire("something_invented_later"));
        assertEquals(ICloudFailure.UNKNOWN, ICloudFailure.fromWire(null));
    }
}
