package dev.wander.android.opentagviewer.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rule behind "you are leaving without your keys".
 *
 * <p>An alert that fires when it should not is worse than none: people turn off a feature that
 * cries wolf, and then it is not there on the day it matters. So the rule is deliberately hard
 * to satisfy - the tag must have gone quiet <i>and</i> the phone must have gone somewhere else.
 */
public class LeftBehindTest {

    /** The cafe. */
    private static final double LAT = 49.4767;
    private static final double LON = 8.5622;

    private static final long NOON = 1_700_000_000_000L;
    private static final long WELL_PAST = NOON + LeftBehind.QUIET_FOR_MS + 1;

    /** Roughly 220 metres north, which is past the threshold. */
    private static final double FAR_LAT = LAT + 0.002;

    @Test
    public void quietAndFarAwayIsLeftBehind() {
        assertTrue(LeftBehind.looksLeftBehind(NOON, LAT, LON, WELL_PAST, FAR_LAT, LON));
    }

    /**
     * <b>Silence alone is not enough, and this is the case that matters.</b> A tag in a pocket
     * with a body between it and the phone misses scan windows, and the background scan uses the
     * cheapest mode the platform offers. Alerting on that would fire on every walk.
     */
    @Test
    public void quietButStillInTheSamePlaceIsNotLeftBehind() {
        assertFalse(LeftBehind.looksLeftBehind(NOON, LAT, LON, WELL_PAST, LAT, LON));
    }

    /** Moving away while the tag is still being heard means it came along. */
    @Test
    public void farAwayButHeardRecentlyIsNotLeftBehind() {
        assertFalse(LeftBehind.looksLeftBehind(
                NOON, LAT, LON, NOON + LeftBehind.QUIET_FOR_MS - 1, FAR_LAT, LON));
    }

    @Test
    public void aTagThisPhoneHasNeverHeardIsNotLeftBehind() {
        assertFalse(LeftBehind.looksLeftBehind(null, null, null, WELL_PAST, FAR_LAT, LON));
    }

    /**
     * Without a position for the last sighting there is no way to tell moving away from standing
     * still, and silence on its own does not earn an alert.
     */
    @Test
    public void withoutAPositionForTheLastSightingNothingIsClaimed() {
        assertFalse(LeftBehind.looksLeftBehind(NOON, null, null, WELL_PAST, FAR_LAT, LON));
    }

    /** A few metres of GPS wobble is not going somewhere else. */
    @Test
    public void gpsWobbleIsNotMovingAway() {
        assertFalse(LeftBehind.looksLeftBehind(
                NOON, LAT, LON, WELL_PAST, LAT + 0.00005, LON));
    }

    /** The threshold is well past Bluetooth range, so it cannot fire from the same room. */
    @Test
    public void theDistanceThresholdIsWellPastBluetoothRange() {
        assertTrue("a tag would still be audible at this range",
                LeftBehind.MOVED_AWAY_METRES > 50.0);
    }
}
