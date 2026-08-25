package dev.wander.android.opentagviewer.util.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The cache that stops the location indicator blinking on every sighting.
 *
 * <p>Android lights the indicator whenever an app touches location, and the sighting path
 * touches it per sighting, per tag, twice - once to place the sighting and once for the
 * left-behind rule. With the background service running that is a chip flashing every few
 * seconds, which reads as the app tracking somebody far harder than it does.
 */
public class CachedPhoneLocationTest {

    private static final double LAT = 49.4767;
    private static final double LON = 8.5622;

    /** Counts reads, so "how often did this touch location" is the thing being asserted. */
    private static final class CountingLocation implements PhoneLocation {
        private final AtomicInteger reads = new AtomicInteger();
        private Fix answer;

        private CountingLocation(final Fix answer) {
            this.answer = answer;
        }

        @Override
        public Fix lastKnown() {
            this.reads.incrementAndGet();
            return this.answer;
        }
    }

    @Test
    public void repeatedCallsTouchLocationOnce() {
        final CountingLocation real = new CountingLocation(new PhoneLocation.Fix(LAT, LON, 8));
        final long[] clock = {1_000L};
        final CachedPhoneLocation cached = new CachedPhoneLocation(real, () -> clock[0]);

        cached.lastKnown();
        cached.lastKnown();
        cached.lastKnown();

        assertEquals("three sightings must not be three location accesses", 1, real.reads.get());
    }

    @Test
    public void theCacheExpires() {
        final CountingLocation real = new CountingLocation(new PhoneLocation.Fix(LAT, LON, 8));
        final long[] clock = {1_000L};
        final CachedPhoneLocation cached = new CachedPhoneLocation(real, () -> clock[0]);

        cached.lastKnown();
        clock[0] += CachedPhoneLocation.FRESH_FOR_MS;
        cached.lastKnown();

        assertEquals(2, real.reads.get());
    }

    /**
     * <b>The correction that makes caching honest.</b> A minute-old position handed back at the
     * accuracy of a fresh fix would be drawn on the map as a tight circle around somewhere the
     * phone no longer is. Walking pace times the age is the width it can still claim.
     */
    @Test
    public void aCachedFixReportsTheAccuracyItCanStillClaim() {
        final CountingLocation real = new CountingLocation(new PhoneLocation.Fix(LAT, LON, 8));
        final long[] clock = {1_000L};
        final CachedPhoneLocation cached = new CachedPhoneLocation(real, () -> clock[0]);

        cached.lastKnown();
        clock[0] += 30_000L;
        final PhoneLocation.Fix stale = cached.lastKnown();

        assertEquals("the position itself does not move", LAT, stale.getLatitude(), 0.000001);
        assertTrue("thirty seconds of walking is tens of metres, and must be admitted",
                stale.getAccuracyMetres() > 8 + 30);
    }

    @Test
    public void aFreshFixIsNotWidened() {
        final CountingLocation real = new CountingLocation(new PhoneLocation.Fix(LAT, LON, 8));
        final long[] clock = {1_000L};

        assertEquals(8, new CachedPhoneLocation(real, () -> clock[0]).lastKnown()
                .getAccuracyMetres());
    }

    /**
     * Having no fix is a state that lasts, so retrying it per sighting would light the indicator
     * exactly as often as succeeding, for an answer that will not have changed.
     */
    @Test
    public void havingNoFixIsRememberedToo() {
        final CountingLocation real = new CountingLocation(null);
        final long[] clock = {1_000L};
        final CachedPhoneLocation cached = new CachedPhoneLocation(real, () -> clock[0]);

        assertNull(cached.lastKnown());
        assertNull(cached.lastKnown());

        assertEquals(1, real.reads.get());
    }
}
