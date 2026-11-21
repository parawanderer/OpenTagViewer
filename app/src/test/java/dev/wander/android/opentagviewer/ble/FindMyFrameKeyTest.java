package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The key reconstruction, pinned against the layout FindMy.py's scanner.py implements.
 *
 * <p>The MAC below is chosen so its bytes differ from every payload byte - a swapped source
 * shows up immediately rather than by coincidence.
 */
public class FindMyFrameKeyTest {

    private static final String MAC = "AA:BB:CC:DD:EE:FF";

    /**
     * A separated frame: {@code 0x12, 0x19, status, 22 key-tail bytes (0x01..0x16), topTwo,
     * hint}. {@code topTwo = 0x02} puts 0b10 in the reconstructed first byte's top bits.
     */
    private static byte[] separatedFrame() {
        final byte[] frame = new byte[27];
        frame[0] = 0x12;
        frame[1] = 0x19;
        frame[2] = 0x10; // status
        for (int i = 0; i < 22; i++) {
            frame[3 + i] = (byte) (i + 1); // key tail, bytes 6..27 of the public key
        }
        frame[25] = 0x02; // top two bits of key byte 0
        frame[26] = 0x42; // hint
        return frame;
    }

    /** A nearby frame: {@code 0x12, 0x02, status, topTwo}. */
    private static byte[] nearbyFrame(final int topTwo) {
        return new byte[]{0x12, 0x02, 0x10, (byte) topTwo};
    }

    @Test
    public void separatedReconstructsTheWholeKey() {
        final FindMyFrameKey key = FindMyFrameKey.parse(separatedFrame(), MAC);

        // key[0] = (0x02 << 6) | (0xAA & 0x3f) = 0x80 | 0x2A = 0xAA
        final byte[] expected = new byte[28];
        expected[0] = (byte) 0xAA;
        expected[1] = (byte) 0xBB;
        expected[2] = (byte) 0xCC;
        expected[3] = (byte) 0xDD;
        expected[4] = (byte) 0xEE;
        expected[5] = (byte) 0xFF;
        for (int i = 0; i < 22; i++) {
            expected[6 + i] = (byte) (i + 1);
        }

        assertArrayEquals(expected, key.getFullKey());
        assertEquals(0x42, key.getHint());
        assertEquals(0x10, key.getStatusByte());
    }

    @Test
    public void separatedAndNearbyYieldTheSamePrefix() {
        // The same tag sending both shapes within one rotation: identical MAC, identical
        // topTwo. The whole point of the prefix - this is the join the list relies on.
        final FindMyFrameKey separated = FindMyFrameKey.parse(separatedFrame(), MAC);
        final FindMyFrameKey nearby = FindMyFrameKey.parse(nearbyFrame(0x02), MAC);

        assertArrayEquals(separated.getKeyPrefix(), nearby.getKeyPrefix());
        assertEquals(separated.keyPrefixHex(), nearby.keyPrefixHex());
    }

    @Test
    public void nearbyCarriesNoFullKey() {
        assertNull(FindMyFrameKey.parse(nearbyFrame(0x02), MAC).getFullKey());
        assertNull(FindMyFrameKey.parse(nearbyFrame(0x02), MAC).fullKeyHex());
    }

    @Test
    public void topBitsChangeThePrefixWhereTheMacCannot() {
        // Two keys sharing a MAC differ only in bits the BLE address cannot express. The
        // prefix must see them - a MAC-keyed list would merge two devices.
        final FindMyFrameKey top2 = FindMyFrameKey.parse(nearbyFrame(0x02), MAC);
        final FindMyFrameKey top3 = FindMyFrameKey.parse(nearbyFrame(0x03), MAC);

        assertEquals((byte) 0xAA, top2.getKeyPrefix()[0]); // 0x80 | 0x2A
        assertEquals((byte) 0xEA, top3.getKeyPrefix()[0]); // 0xC0 | 0x2A
    }

    @Test
    public void findsTheFindMyTlvBehindAnotherAppleFrame() {
        // An iPhone prepends its own payloads in the same 0x004C blob; 0x12 need not lead.
        final byte[] nearby = nearbyFrame(0x02);
        final byte[] blob = new byte[3 + nearby.length];
        blob[0] = 0x10; // nearby-info type
        blob[1] = 0x01;
        blob[2] = 0x00;
        System.arraycopy(nearby, 0, blob, 3, nearby.length);

        final FindMyFrameKey key = FindMyFrameKey.parse(blob, MAC);
        assertEquals("aabbccddeeff".substring(0, 4), key.keyPrefixHex().substring(0, 4));
    }

    @Test
    public void rejectsWhatIsNotFindMy() {
        assertNull(FindMyFrameKey.parse(null, MAC));
        assertNull(FindMyFrameKey.parse(new byte[]{0x12}, MAC));
        assertNull(FindMyFrameKey.parse(new byte[]{0x10, 0x02, 0x00, 0x00}, MAC));
        // A 0x12 of a length that is neither known shape.
        assertNull(FindMyFrameKey.parse(new byte[]{0x12, 0x05, 0, 0, 0, 0, 0}, MAC));
        // A truncated TLV at the tail.
        assertNull(FindMyFrameKey.parse(new byte[]{0x10, 0x01, 0x00, 0x12, 0x19}, MAC));
        // And a MAC that is not one.
        assertNull(FindMyFrameKey.parse(nearbyFrame(0x02), "not-a-mac"));
        assertNull(FindMyFrameKey.parse(nearbyFrame(0x02), null));
    }

    @Test
    public void hexRendersLowercase() {
        assertEquals("aa" + "bbccddeeff",
                FindMyFrameKey.parse(nearbyFrame(0x02), MAC).keyPrefixHex());
    }
}
