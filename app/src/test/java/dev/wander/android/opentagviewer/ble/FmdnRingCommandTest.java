package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class FmdnRingCommandTest {
    private static final byte[] RING_KEY = {1, 2, 3, 4, 5, 6, 7, 8};
    /** Version 1, then the nonce a1..a8 - what a Beacon Actions read returns. */
    private static final byte[] READ = FmdnRingCommand.unhex("01a1a2a3a4a5a6a7a8");

    @Test
    public void authKeyMatchesTheSpecifiedHmac() {
        // Reference: hmac.new(ring_key, 01|nonce|05|0c|ff012c00 (no trailing direction byte), sha256).digest()[:8] in Python
        final byte[] auth = FmdnRingCommand.authKey(RING_KEY, 1, FmdnRingCommand.unhex("a1a2a3a4a5a6a7a8"),
                FmdnRingCommand.DATA_ID_RING, (byte) 12, new byte[]{(byte) 0xff, 0x01, 0x2c, 0x00});
        assertEquals("43e3b0b455361e86", FmdnRingCommand.hex(auth));
    }

    @Test
    public void ringWriteLayout() {
        final byte[] write = FmdnRingCommand.ringWrite(RING_KEY, READ, FmdnRingCommand.COMPONENTS_ALL, 300,
                FmdnRingCommand.VOLUME_DEFAULT);
        assertEquals(14, write.length);
        assertEquals(0x05, write[0]);
        assertEquals(12, write[1]);
        assertEquals("43e3b0b455361e86", FmdnRingCommand.hex(java.util.Arrays.copyOfRange(write, 2, 10)));
        assertArrayEquals(new byte[]{(byte) 0xff, 0x01, 0x2c, 0x00}, java.util.Arrays.copyOfRange(write, 10, 14));
    }

    @Test
    public void stopUsesNoComponents() {
        final byte[] write = FmdnRingCommand.ringWrite(RING_KEY, READ, FmdnRingCommand.COMPONENTS_STOP, 300,
                FmdnRingCommand.VOLUME_DEFAULT);
        assertEquals(0x00, write[10]);
    }

    @Test
    public void eidMatching() {
        final String eid = "0123456789abcdef0123456789abcdef01234567";
        final byte[] frame = FmdnRingCommand.unhex("41" + eid + "a0");
        assertTrue(FmdnRingCommand.matchesEid(frame, List.of(eid)));
        assertFalse(FmdnRingCommand.matchesEid(FmdnRingCommand.unhex("10" + eid + "a0"), List.of(eid)));
        assertFalse(FmdnRingCommand.matchesEid(frame, List.of("00" + eid.substring(2))));
    }
}
