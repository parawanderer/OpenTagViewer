package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Sighting assembly out of a manufacturer-data blob - mostly "the Find My TLV is found where
 * it sits".
 *
 * <p>Apple manufacturer data is a TLV chain and the 0x12 record does not have to lead it:
 * Nearby Info and Handoff records routinely sit in front. A scanner that only understands the
 * leading position silently drops every such device.
 */
public class NearbyDeviceSightingTest {

    private static final String MAC = "AA:BB:CC:DD:EE:FF";

    /** Same separated frame {@link FindMyFrameKeyTest} reconstructs, so the expected key matches. */
    private static byte[] separatedFrame() {
        final byte[] frame = new byte[27];
        frame[0] = 0x12;
        frame[1] = 0x19;
        frame[2] = 0x10; // status
        for (int i = 0; i < 22; i++) {
            frame[3 + i] = (byte) (i + 1);
        }
        frame[25] = 0x02; // top two bits of key byte 0
        frame[26] = 0x42; // hint
        return frame;
    }

    /** A nearby frame: {@code 0x12, 0x02, status, topTwo}. */
    private static byte[] nearbyFrame() {
        return new byte[]{0x12, 0x02, 0x10, 0x01};
    }

    /** A Nearby Info record ({@code 0x10, len 1}) concatenated in front of the frame. */
    private static byte[] prefixed(final byte[] frame) {
        final byte[] blob = new byte[3 + frame.length];
        blob[0] = 0x10;
        blob[1] = 0x01;
        blob[2] = 0x00;
        System.arraycopy(frame, 0, blob, 3, frame.length);
        return blob;
    }

    @Test
    public void separatedFrameAtOffsetZeroParses() {
        final NearbyDeviceSighting sighting = NearbyDeviceSighting.fromAdvertisement(
                MAC, separatedFrame(), -60, 1000L);

        assertNotNull(sighting);
        assertEquals("aabbccddeeff", sighting.getKeyPrefix());
        assertNotNull(sighting.getFullKey());
        assertEquals(-60, sighting.getRssi());
        assertEquals(1000L, sighting.getSeenAtMs());
        assertEquals(0x10, sighting.getStatusByte());
        assertEquals(0x42, sighting.getHint());
    }

    @Test
    public void separatedFrameBehindAnotherTlvStillParses() {
        final NearbyDeviceSighting sighting = NearbyDeviceSighting.fromAdvertisement(
                MAC, prefixed(separatedFrame()), -60, 1000L);

        assertNotNull(sighting);
        assertEquals("aabbccddeeff", sighting.getKeyPrefix());
        assertNotNull(sighting.getFullKey());
    }

    @Test
    public void nearbyFrameBehindAnotherTlvStillParses() {
        final NearbyDeviceSighting sighting = NearbyDeviceSighting.fromAdvertisement(
                MAC, prefixed(nearbyFrame()), -60, 1000L);

        assertNotNull(sighting);
        // key[0] = (0x01 << 6) | (0xAA & 0x3f) = 0x6A; the MAC supplies bytes 1..5.
        assertEquals("6abbccddeeff", sighting.getKeyPrefix());
        assertNull(sighting.getFullKey());
    }

    @Test
    public void blobWithoutFindMyRecordYieldsNoSighting() {
        assertNull(NearbyDeviceSighting.fromAdvertisement(
                MAC, new byte[]{0x10, 0x01, 0x00, 0x0C, 0x02, 0x01, 0x02}, -60, 1000L));
    }

    @Test
    public void truncatedRecordYieldsNoSighting() {
        // Advertised length runs past the blob's end.
        assertNull(NearbyDeviceSighting.fromAdvertisement(
                MAC, new byte[]{0x10, 0x01, 0x00, 0x12, 0x19, 0x10}, -60, 1000L));
    }
}
