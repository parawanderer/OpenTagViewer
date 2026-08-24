package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.BatteryLevel;
import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.State;

/** A JVM test: the ageing rule is what matters here, and it needs no radio to check. */
public class NearbyTagSightingsTest {

    private static final String KEYS = "keys-beacon-id";
    private static final String BIKE = "bike-beacon-id";

    private static NearbyTagSighting seen(final String beaconId, final long atMs) {
        return new NearbyTagSighting(beaconId, -50, BatteryLevel.FULL, 0x00, State.SEPARATED, atMs);
    }

    @Test
    public void aTagNeverSeenHasNoSighting() {
        assertNull(new NearbyTagSightings().freshFor(KEYS, 0L));
    }

    @Test
    public void aJustSeenTagIsReported() {
        final NearbyTagSightings sightings = new NearbyTagSightings();
        sightings.record(seen(KEYS, 1_000L));

        final NearbyTagSighting fresh = sightings.freshFor(KEYS, 1_000L);
        assertNotNull(fresh);
        assertEquals(KEYS, fresh.getBeaconId());
    }

    /**
     * <b>The claim has to expire on its own.</b> Nothing tells us a tag left; we just stop
     * hearing it. A badge that stays would read "nearby" for a tag already down the road.
     */
    @Test
    public void aSightingStopsCountingOnceItIsTooOld() {
        final NearbyTagSightings sightings = new NearbyTagSightings();
        sightings.record(seen(KEYS, 0L));

        assertNotNull(sightings.freshFor(KEYS, NearbyTagSightings.FRESH_FOR_MS - 1));
        assertNull(sightings.freshFor(KEYS, NearbyTagSightings.FRESH_FOR_MS));
    }

    @Test
    public void beingSeenAgainRenewsIt() {
        final NearbyTagSightings sightings = new NearbyTagSightings();
        sightings.record(seen(KEYS, 0L));
        sightings.record(seen(KEYS, 20_000L));

        assertNotNull("the later sighting should carry it past the first one's expiry",
                sightings.freshFor(KEYS, 40_000L));
    }

    @Test
    public void theLatestSightingWins() {
        final NearbyTagSightings sightings = new NearbyTagSightings();
        sightings.record(new NearbyTagSighting(KEYS, -90, BatteryLevel.FULL, 0x00, State.SEPARATED, 0L));
        sightings.record(new NearbyTagSighting(KEYS, -40, BatteryLevel.LOW, 0x80, State.SEPARATED, 100L));

        final NearbyTagSighting fresh = sightings.freshFor(KEYS, 100L);
        assertEquals(-40, fresh.getRssi());
        assertEquals(BatteryLevel.LOW, fresh.getBatteryLevel());
    }

    @Test
    public void tagsAreTrackedIndependently() {
        final NearbyTagSightings sightings = new NearbyTagSightings();
        sightings.record(seen(KEYS, 0L));

        assertNotNull(sightings.freshFor(KEYS, 0L));
        assertNull(sightings.freshFor(BIKE, 0L));
    }

    /** Scanning has stopped, so nothing on screen may keep claiming to be current. */
    @Test
    public void clearingDropsEverything() {
        final NearbyTagSightings sightings = new NearbyTagSightings();
        sightings.record(seen(KEYS, 0L));
        sightings.clear();

        assertNull(sightings.freshFor(KEYS, 0L));
    }
}
