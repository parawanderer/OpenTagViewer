package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.BatteryLevel;
import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.State;

/**
 * The payload rules, pinned against real captures.
 *
 * <p>Every byte sequence below was observed in an actual scan on a Pixel 10 Pro, rather than
 * constructed from the spec, so a change here fails against what accessories really send.
 */
public class FindMyAdvertisementTest {

    /** A separated accessory, battery full. Captured from the author's own tag. */
    private static final byte[] SEPARATED_FULL = {0x12, 0x19, 0x20};

    /** A separated accessory reporting a low battery. Captured from a stranger's tag nearby. */
    private static final byte[] SEPARATED_LOW = {0x12, 0x19, (byte) 0x90};

    /** The short form, sent while the owner is present. Captured repeatedly. */
    private static final byte[] OWNER_NEARBY = {0x12, 0x02, 0x00};

    @Test
    public void readsTheSeparatedState() {
        assertEquals(State.SEPARATED, FindMyAdvertisement.parse(SEPARATED_FULL).getState());
    }

    @Test
    public void readsTheOwnerNearbyState() {
        assertEquals(State.OWNER_NEARBY, FindMyAdvertisement.parse(OWNER_NEARBY).getState());
    }

    /**
     * <b>Only 0x19 means separated.</b> Anything else is the short form, which is how AirGuard
     * reads it too. Pinned because treating an unknown length as "separated" would have us
     * announce a tag as reachable when it is not.
     */
    @Test
    public void anyLengthOtherThanTheFullBeaconCountsAsOwnerNearby() {
        assertEquals(State.OWNER_NEARBY,
                FindMyAdvertisement.parse(new byte[] {0x12, 0x0A, 0x00}).getState());
    }

    @Test
    public void readsTheBatteryLevelFromTheTopTwoBits() {
        assertEquals(BatteryLevel.FULL, FindMyAdvertisement.parse(SEPARATED_FULL).getBatteryLevel());
        assertEquals(BatteryLevel.LOW, FindMyAdvertisement.parse(SEPARATED_LOW).getBatteryLevel());
    }

    @Test
    public void coversAllFourBatteryLevels() {
        assertEquals(BatteryLevel.FULL,
                FindMyAdvertisement.parse(new byte[] {0x12, 0x19, 0x00}).getBatteryLevel());
        assertEquals(BatteryLevel.MEDIUM,
                FindMyAdvertisement.parse(new byte[] {0x12, 0x19, 0x40}).getBatteryLevel());
        assertEquals(BatteryLevel.LOW,
                FindMyAdvertisement.parse(new byte[] {0x12, 0x19, (byte) 0x80}).getBatteryLevel());
        assertEquals(BatteryLevel.VERY_LOW,
                FindMyAdvertisement.parse(new byte[] {0x12, 0x19, (byte) 0xC0}).getBatteryLevel());
    }

    /**
     * The status byte is kept raw as well as interpreted. A bug report quoting 0x90 is
     * answerable; one quoting "Low" is not, if the reading itself is what is wrong.
     */
    @Test
    public void keepsTheRawStatusByteUnsigned() {
        assertEquals(0x90, FindMyAdvertisement.parse(SEPARATED_LOW).getStatusByte());
    }

    // --- what is not a Find My advertisement -------------------------------------------------

    @Test
    public void ignoresDevicesWithNoAppleData() {
        assertNull(FindMyAdvertisement.parse(null));
    }

    /**
     * Apple broadcasts plenty of other types - handoff, nearby-info, and so on. Captured
     * examples: 0x10, 0x0F, 0x13, 0x09. None of them are ours.
     */
    @Test
    public void ignoresOtherAppleAdvertisementTypes() {
        assertNull(FindMyAdvertisement.parse(new byte[] {0x10, 0x05, 0x03}));
        assertNull(FindMyAdvertisement.parse(new byte[] {0x0F, 0x05, (byte) 0x90}));
        assertNull(FindMyAdvertisement.parse(new byte[] {0x13, 0x08, 0x4A}));
    }

    @Test
    public void ignoresAPayloadTooShortToRead() {
        assertNull(FindMyAdvertisement.parse(new byte[] {0x12, 0x19}));
        assertNull(FindMyAdvertisement.parse(new byte[] {}));
    }
}
