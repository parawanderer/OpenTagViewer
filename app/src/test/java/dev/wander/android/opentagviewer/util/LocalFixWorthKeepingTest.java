package dev.wander.android.opentagviewer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rule that keeps a locally heard position from flooding the history.
 *
 * <p>Sightings arrive far faster than positions are worth keeping: a tag in range is heard every
 * second or two, and even the throttled callback fires once a minute. Writing a row each time
 * would put several hundred identical points into a tag's day, each one reverse-geocoded when
 * shown.
 */
public class LocalFixWorthKeepingTest {

    /** Ilvesheim, where the measurements behind this feature were taken. */
    private static final double LAT = 49.4767;
    private static final double LON = 8.5622;

    private static final long NOON = 1_700_000_000_000L;

    @Test
    public void theFirstFixForATagIsAlwaysKept() {
        assertTrue(LocalFixWorthKeeping.worthKeeping(null, null, null, LAT, LON, NOON));
    }

    @Test
    public void standingStillDoesNotWriteAgainStraightAway() {
        assertFalse("a tag beside somebody must not write a row per sighting",
                LocalFixWorthKeeping.worthKeeping(LAT, LON, NOON, LAT, LON, NOON + 60_000));
    }

    @Test
    public void standingStillIsWorthRecordingAgainEventually() {
        assertTrue("still here an hour later is information a history should carry",
                LocalFixWorthKeeping.worthKeeping(
                        LAT, LON, NOON, LAT, LON, NOON + LocalFixWorthKeeping.AGAIN_AFTER_MS));
    }

    /**
     * Roughly 90 metres north, which is past the threshold: a different place.
     */
    @Test
    public void movingFarEnoughWritesAgainImmediately() {
        assertTrue(LocalFixWorthKeeping.worthKeeping(
                LAT, LON, NOON, LAT + 0.0008, LON, NOON + 1_000));
    }

    /**
     * Roughly 5 metres, which is inside GPS noise standing still - two rows here would differ
     * only by the fix wobbling, not by anything having happened.
     */
    @Test
    public void aFixThatOnlyWobbledIsNotADifferentPlace() {
        assertFalse(LocalFixWorthKeeping.worthKeeping(
                LAT, LON, NOON, LAT + 0.000045, LON, NOON + 1_000));
    }

    @Test
    public void distanceIsMeasuredOnTheGlobeRatherThanTheGrid() {
        // A hundredth of a degree of latitude is about 1.11 km anywhere on Earth.
        final double metres = LocalFixWorthKeeping.metresBetween(LAT, LON, LAT + 0.01, LON);

        assertEquals(1110.0, metres, 10.0);
    }

    /**
     * A degree of longitude shrinks toward the poles. A flat approximation without the cosine
     * correction gets this wrong by more the further north the user lives, which is a bug that
     * would never be reported by anybody near the equator.
     */
    @Test
    public void aDegreeOfLongitudeIsShorterThisFarNorth() {
        final double eastWest = LocalFixWorthKeeping.metresBetween(LAT, LON, LAT, LON + 0.01);
        final double northSouth = LocalFixWorthKeeping.metresBetween(LAT, LON, LAT + 0.01, LON);

        assertTrue("east-west (" + eastWest + "m) must be shorter than north-south ("
                + northSouth + "m) at 49 degrees north", eastWest < northSouth * 0.7);
    }

    @Test
    public void theSamePointIsZeroMetresApart() {
        assertEquals(0.0, LocalFixWorthKeeping.metresBetween(LAT, LON, LAT, LON), 0.0001);
    }
}
