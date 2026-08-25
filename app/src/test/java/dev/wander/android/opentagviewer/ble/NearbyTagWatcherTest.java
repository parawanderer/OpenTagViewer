package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.python.AccessoryMacResolver;

/**
 * Covers {@link NearbyTagWatcher#maybeNotifySightingListener} through the package-private
 * constructor - no radio, no Android, an injected clock. The scan itself needs a real adapter
 * and is not exercised here; see {@link NearbyTagWatcher#sightingFrom} for what a JVM test can
 * reach of the scan side.
 *
 * <p>The listener always fires on {@code Schedulers.io()}, deliberately - see the method's own
 * doc - so every test here waits on a latch rather than asserting immediately after the call.
 */
public class NearbyTagWatcherTest {

    private static final String BEACON_ID = "keys-beacon-id";
    private static final String MAC = "AA:BB:CC:DD:EE:FF";
    private static final long AWAIT_SECONDS = 5;

    private static AccessoryMacResolver anyResolver() {
        return json -> Map.of();
    }

    /** A sighting as the scan callback would have built one, for a tag reporting a full battery. */
    private static NearbyTagSighting sightingOf(final String beaconId) {
        return sightingOf(beaconId, FindMyAdvertisement.BatteryLevel.FULL, 0b0000_0000);
    }

    private static NearbyTagSighting sightingOf(
            final String beaconId,
            final FindMyAdvertisement.BatteryLevel level,
            final int statusByte) {
        return new NearbyTagSighting(beaconId, 4321, -60, level, statusByte,
                FindMyAdvertisement.State.SEPARATED, 1_700_000_000_000L);
    }

    /** Records each call and counts down a latch, so a test can wait for the async dispatch. */
    private static final class RecordingListener implements NearbyTagWatcher.SightingListener {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<NearbyTagSighting> sightings = new CopyOnWriteArrayList<>();
        private final CountDownLatch latch;

        RecordingListener(final int expectedCalls) {
            this.latch = new CountDownLatch(expectedCalls);
        }

        @Override
        public void onSighting(final NearbyTagSighting sighting, final String mac) {
            this.calls.add(sighting.getBeaconId());
            this.sightings.add(sighting);
            this.latch.countDown();
        }

        /** Waits for the expected call count, then gives a little more time to catch extras. */
        void awaitThenSettle() throws InterruptedException {
            if (!this.latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                fail("expected call(s) never arrived within " + AWAIT_SECONDS + "s");
            }
            Thread.sleep(100);
        }
    }

    private static NearbyTagWatcher watcherWith(
            final NearbyTagWatcher.SightingListener listener, final long[] clockMs) {
        return new NearbyTagWatcher(
                anyResolver(), listener, 0, new NearbyTagIndex(), () -> clockMs[0]);
    }

    @Test
    public void notifiesTheListenerOnAMatchedSighting() throws InterruptedException {
        final RecordingListener listener = new RecordingListener(1);
        final long[] clock = {0L};
        final NearbyTagWatcher watcher = watcherWith(listener, clock);

        watcher.maybeNotifySightingListener(sightingOf(BEACON_ID), MAC);

        listener.awaitThenSettle();
        assertEquals(1, listener.calls.size());
    }

    @Test
    public void throttlesRepeatedCallsForTheSameBeacon() throws InterruptedException {
        final RecordingListener listener = new RecordingListener(1);
        final long[] clock = {0L};
        final NearbyTagWatcher watcher = watcherWith(listener, clock);

        watcher.maybeNotifySightingListener(sightingOf(BEACON_ID), MAC);
        clock[0] = NearbyTagWatcher.SIGHTING_LISTENER_INTERVAL_MS - 1;
        watcher.maybeNotifySightingListener(sightingOf(BEACON_ID), MAC);

        listener.awaitThenSettle();
        assertEquals("the second call landed inside the throttle window", 1, listener.calls.size());
    }

    @Test
    public void callsAgainOnceTheThrottleWindowHasPassed() throws InterruptedException {
        final RecordingListener listener = new RecordingListener(2);
        final long[] clock = {0L};
        final NearbyTagWatcher watcher = watcherWith(listener, clock);

        watcher.maybeNotifySightingListener(sightingOf(BEACON_ID), MAC);
        clock[0] = NearbyTagWatcher.SIGHTING_LISTENER_INTERVAL_MS;
        watcher.maybeNotifySightingListener(sightingOf(BEACON_ID), MAC);

        listener.awaitThenSettle();
        assertEquals(2, listener.calls.size());
    }

    @Test
    public void aNullListenerIsSimplySkipped() {
        final NearbyTagWatcher watcher = new NearbyTagWatcher(
                anyResolver(), null, 0, new NearbyTagIndex(), () -> 0L);

        // Must not throw.
        watcher.maybeNotifySightingListener(sightingOf(BEACON_ID), MAC);
    }

    /**
     * The listener is handed the whole sighting, because the same advertisement feeds two
     * different writes: the address corrects key alignment, and the battery level is kept for
     * long after the tag has gone quiet. A listener given only an address could not do the
     * second, and a second listener for it would fire on its own schedule rather than this
     * one's throttle.
     */
    @Test
    public void handsOverWhatTheAdvertisementSaidNotJustWhereItCameFrom()
            throws InterruptedException {
        final RecordingListener listener = new RecordingListener(1);
        final long[] clock = {0L};
        final NearbyTagWatcher watcher = watcherWith(listener, clock);

        watcher.maybeNotifySightingListener(
                sightingOf(BEACON_ID, FindMyAdvertisement.BatteryLevel.LOW, 0b1000_0000), MAC);

        listener.awaitThenSettle();
        assertEquals(1, listener.sightings.size());
        assertEquals(FindMyAdvertisement.BatteryLevel.LOW,
                listener.sightings.get(0).getBatteryLevel());
        assertEquals("the raw status byte must survive the hand-over too",
                0b1000_0000, listener.sightings.get(0).getStatusByte());
    }

    @Test
    public void eachBeaconIsThrottledIndependently() throws InterruptedException {
        final RecordingListener listener = new RecordingListener(2);
        final long[] clock = {0L};
        final NearbyTagWatcher watcher = watcherWith(listener, clock);

        watcher.maybeNotifySightingListener(sightingOf(BEACON_ID), MAC);
        watcher.maybeNotifySightingListener(sightingOf("bike-beacon-id"), "11:22:33:44:55:66");

        listener.awaitThenSettle();
        assertTrue("a busy tag must not starve another tag's correction",
                listener.calls.contains(BEACON_ID) && listener.calls.contains("bike-beacon-id"));
    }
}
