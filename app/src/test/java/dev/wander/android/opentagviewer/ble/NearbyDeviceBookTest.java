package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.BatteryLevel;
import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.State;

import java.util.List;

/**
 * The aggregation rules of {@link NearbyDeviceBook}: newest per prefix, pruned when silent,
 * sorted for the list.
 */
public class NearbyDeviceBookTest {

    private static NearbyDeviceSighting sighting(
            final String prefix, final int rssi, final long seenAtMs) {
        return NearbyDeviceSighting.builder()
                .mac("AA:BB:CC:DD:EE:FF")
                .keyPrefix(prefix)
                .rssi(rssi)
                .batteryLevel(BatteryLevel.FULL)
                .statusByte(0x10)
                .state(State.SEPARATED)
                .seenAtMs(seenAtMs)
                .build();
    }

    @Test
    public void keepsOneRowPerPrefixHoldingTheNewestSighting() {
        final NearbyDeviceBook book = new NearbyDeviceBook();
        book.hear(sighting("aabbccddeeff", -80, 1_000));
        book.hear(sighting("aabbccddeeff", -60, 2_000));

        final List<NearbyDeviceSighting> rows = book.snapshot(2_000);
        assertEquals(1, rows.size());
        assertEquals(-60, rows.get(0).getRssi());
    }

    @Test
    public void ignoresAStaleResendOfTheSamePrefix() {
        // An out-of-order delivery - e.g. a batched callback - must not resurrect an older
        // RSSI over the newest one.
        final NearbyDeviceBook book = new NearbyDeviceBook();
        book.hear(sighting("aabbccddeeff", -60, 2_000));
        book.hear(sighting("aabbccddeeff", -95, 1_000));

        assertEquals(-60, book.snapshot(2_000).get(0).getRssi());
    }

    @Test
    public void sortsStrongestSignalFirst() {
        final NearbyDeviceBook book = new NearbyDeviceBook();
        book.hear(sighting("111111111111", -80, 1_000));
        book.hear(sighting("222222222222", -50, 1_000));

        assertEquals("222222222222", book.snapshot(1_000).get(0).getKeyPrefix());
    }

    @Test
    public void prunesAPrefixThatWentQuiet() {
        // Departure sends nothing: silence is the only signal. A tag unheard past
        // GONE_AFTER_MS must fall out of the list rather than read as still present.
        final NearbyDeviceBook book = new NearbyDeviceBook();
        book.hear(sighting("aabbccddeeff", -60, 1_000));

        assertEquals(1, book.snapshot(30_999).size());
        assertTrue(book.snapshot(31_001).isEmpty());
    }
}
