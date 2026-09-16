package dev.wander.android.opentagviewer.ble;

import androidx.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * What an Apple Find My advertisement says about the accessory that sent it.
 *
 * <p>Pure parsing of the manufacturer payload, with no Android in it, so the rules below are
 * covered by a JVM test rather than only by holding a tag and hoping.
 *
 * <p><b>Two payload shapes, and the difference is the whole point.</b> An accessory separated
 * from its owner broadcasts the full offline-finding beacon carrying its public key; one whose
 * owner is present broadcasts a two-byte short form instead. The length byte is what tells them
 * apart, which is also how AirGuard's {@code AppleFindMy.getConnectionState} reads it.
 *
 * <p><b>In practice only the separated form is useful here</b>, and not because the short form is
 * hard to parse. Measured on a real accessory: with its owner's phone in the room, the accessory
 * did not appear in a scan at all, while every short-form advertisement seen came from something
 * else nearby. That fits how the protocol works, since an accessory that holds a connection to
 * its owner has no reason to advertise, and it is why a "not seen" result cannot be reported as
 * "out of range" - see {@code NearbyTagSighting}.
 */
@AllArgsConstructor
@Getter
public final class FindMyAdvertisement {

    /** Apple's Bluetooth SIG company identifier. */
    public static final int APPLE_COMPANY_ID = 0x004C;

    /** Apple's "offline finding" advertisement type, the first payload byte. Package-visible
     * so {@link NearbyTagWatcher} can hand it to the hardware scan filter - the filter and this
     * parser must agree on what a Find My frame is, so there is one constant, not two. */
    static final byte TYPE_OFFLINE_FINDING = 0x12;

    /** Payload length of the full beacon an accessory sends once separated from its owner. */
    private static final byte LEN_SEPARATED = 0x19;

    /** Whether the sender is currently with its owner. */
    public enum State {
        /** Separated from its owner: broadcasting the full beacon, and reachable over GATT. */
        SEPARATED,
        /** Its owner is nearby. Recorded for completeness; see the class doc on why an
         * accessory in this state is generally not seen at all. */
        OWNER_NEARBY,
    }

    /**
     * Battery level, from the top two bits of the status byte.
     *
     * <p>Same encoding FindMy.py reads (see its {@code BATTERY_LEVEL} map). Coarse by design:
     * the protocol carries four levels, not a percentage.
     */
    public enum BatteryLevel {
        FULL,
        MEDIUM,
        LOW,
        VERY_LOW,
    }

    private final State state;
    private final BatteryLevel batteryLevel;

    /** The raw status byte, kept so a bug report can quote it rather than only our reading. */
    private final int statusByte;

    /**
     * Parses Apple manufacturer data, or returns null when it is not a Find My advertisement.
     *
     * @param appleManufacturerData the payload for {@link #APPLE_COMPANY_ID}, as returned by
     *                              {@code ScanRecord.getManufacturerSpecificData}. Null-safe:
     *                              most devices in any scan carry no Apple data at all.
     */
    @Nullable
    public static FindMyAdvertisement parse(@Nullable final byte[] appleManufacturerData) {
        // Three bytes minimum: type, length, status. The short form is exactly this long, so
        // anything below it cannot be read even to establish the state.
        if (appleManufacturerData == null || appleManufacturerData.length < 3) {
            return null;
        }
        if (appleManufacturerData[0] != TYPE_OFFLINE_FINDING) {
            return null;
        }

        final State state = appleManufacturerData[1] == LEN_SEPARATED
                ? State.SEPARATED
                : State.OWNER_NEARBY;

        final int status = appleManufacturerData[2] & 0xFF;
        return new FindMyAdvertisement(state, batteryLevelOf(status), status);
    }

    /**
     * Bits 6-7 of the status byte, read as a battery level.
     *
     * <p><b>Read on sight, unlike the same byte in a location report.</b>
     * {@code LocationReportFields} decodes its copy only when the whole byte conforms to Apple's
     * Table 5-5 - bit 5 set, reserved bits clear - because an AirTag's does not, and decoding a
     * non-conforming byte against that table produces a confident wrong answer. That gate is not
     * applied here, and the difference is deliberate rather than an oversight:
     *
     * <ul>
     *   <li><b>Only these two bits are used, not the rest of the table.</b> The reserved bits an
     *       AirTag sets wrongly are the ones this does not look at.</li>
     *   <li><b>Two real AirTags, watched across a battery change, move in exactly these two bits
     *       and nowhere else.</b> Observed by @parawanderer on 2026-09-16, over both Bluetooth
     *       and the Find My network:
     *
     *       <pre>
     *   0x90 = 0b10010000   both tags, cells months old     bits 6-7 = 0b10  Low
     *   0x50 = 0b01010000   one tag, cell just replaced     bits 6-7 = 0b01  Medium
     *   0x10 = 0b00010000   the other, cell just replaced   bits 6-7 = 0b00  Full
     *       </pre>
     *
     *       <p>Three of the four states, with bit 4 set and bit 5 clear throughout - the two an
     *       AirTag sets against Table 5-5. A remainder that does not change across three battery
     *       states is a signature, not a field, so the objection to decoding this byte does not
     *       reach bits 6-7. They fall in the order the table gives, downward as a cell ages and
     *       upward when it is replaced. Catley's teardown independently records {@code 0x10}.</li>
     *   <li><b>There is no alternative for these users.</b> The battery on the account record is
     *       written by Apple's own devices, so for somebody without one it is years old or, as
     *       with both tags this was developed against, never written at all. See
     *       {@code LastBleSighting}.</li>
     * </ul>
     *
     * <p><b>What it still does not establish is what the four words are worth.</b> Nothing here
     * calibrates them: "medium" on a cell replaced minutes earlier is the tag's own opinion, and
     * whether that reflects a weak cell, a measurement the tag has not retaken, or a scale that
     * simply does not start at "full" is unknown. Only {@code 0b11}, critically low, has not
     * been seen at all.
     *
     * <p>Still wanted, and a much smaller job than before: these bits read off tags whose actual
     * charge is known, to attach numbers to the words. The raw byte is kept on the advertisement
     * so any such report can quote it rather than only this reading.
     */
    private static BatteryLevel batteryLevelOf(final int statusByte) {
        switch ((statusByte >> 6) & 0b11) {
            case 0b01: return BatteryLevel.MEDIUM;
            case 0b10: return BatteryLevel.LOW;
            case 0b11: return BatteryLevel.VERY_LOW;
            case 0b00:
            default: return BatteryLevel.FULL;
        }
    }
}
