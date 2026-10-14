package dev.wander.android.opentagviewer.ble;

import androidx.annotation.Nullable;

import lombok.Builder;
import lombok.Getter;

/**
 * Any Find My device this phone's radio just heard - most of them strangers.
 *
 * <p><b>What this is not:</b> {@link NearbyTagSighting}. That class is a sighting of one of the
 * user's *own* tags, resolved through the rolling-key index so it knows the beacon it heard.
 * This one knows only what the air said: a key prefix, a MAC, a signal strength. The device
 * behind it belongs to anybody - or is one of the user's own seen before the index caught it.
 * The two coexist on purpose rather than one growing a "maybe unidentified" mode: matching is
 * the watcher's whole contract, and not matching is this one's.
 *
 * <p>{@link #keyPrefix} is the identity a caller should join on: the same device in nearby and
 * separated states yields the same six bytes, and a different device almost never does. The MAC
 * is carried because it is interesting on screen and for a GATT connect, but it is not the
 * identity - the prefix is finer (it carries the two key bits the MAC cannot express), and
 * {@link FindMyFrameKey} explains the reconstruction.
 */
@Getter
@Builder
public final class NearbyDeviceSighting {

    /** The BLE address the frame arrived on. Not an identity - see the class doc. */
    private final String mac;

    /** Six-byte advertisement-key prefix as lowercase hex; the join key for both frame shapes. */
    private final String keyPrefix;

    /**
     * The whole advertisement key as lowercase hex, when the frame carried it.
     *
     * <p>Null on a nearby ({@code len == 0x02}) frame, which transmits only the prefix. Worth
     * keeping because the separated form is the one a Find My network lookup could be built
     * from, and because two prefixes that agree do not necessarily agree here.
     */
    @Nullable
    private final String fullKey;

    /** Signal strength in dBm. Negative; closer to zero is nearer. */
    private final int rssi;

    private final FindMyAdvertisement.BatteryLevel batteryLevel;

    /** The raw status byte, kept for the same reason {@link NearbyTagSighting} keeps it. */
    private final int statusByte;

    /** The separated frame's hint byte; 0 on a nearby frame. */
    private final int hint;

    /** Whether the tag said it was separated from its owner. */
    private final FindMyAdvertisement.State state;

    /** Wall-clock time of the sighting, for ageing rows on the list. */
    private final long seenAtMs;

    /**
     * Builds a sighting from a scan result's raw parts, or null when the frame is not Find My.
     *
     * <p>Package-private and taking the pieces rather than {@code ScanResult}, so the JVM test
     * parses bytes without constructing Android classes.
     */
    @Nullable
    static NearbyDeviceSighting fromAdvertisement(
            final String mac,
            @Nullable final byte[] appleManufacturerData,
            final int rssi,
            final long seenAtMs) {
        final FindMyAdvertisement advertisement =
                FindMyAdvertisement.parse(tlvBytes(appleManufacturerData));
        final FindMyFrameKey key = FindMyFrameKey.parse(appleManufacturerData, mac);
        if (advertisement == null || key == null) {
            return null;
        }

        return NearbyDeviceSighting.builder()
                .mac(mac)
                .keyPrefix(key.keyPrefixHex())
                .fullKey(key.fullKeyHex())
                .rssi(rssi)
                .batteryLevel(advertisement.getBatteryLevel())
                .statusByte(advertisement.getStatusByte())
                .hint(key.getHint())
                .state(advertisement.getState())
                .seenAtMs(seenAtMs)
                .build();
    }

    /**
     * The Find My TLV alone, or null when the blob does not start with one.
     *
     * <p>{@link FindMyAdvertisement#parse} reads offset zero, so it is handed the whole blob as
     * long as the Find My frame leads it - which {@link FindMyFrameKey#parse} already verified
     * by the time this runs on the same data. Frames where 0x12 is not first fall out the same
     * {@code null} both checks would produce anyway.
     */
    @Nullable
    private static byte[] tlvBytes(@Nullable final byte[] data) {
        if (data == null || data.length < 3 || data[0] != FindMyFrameKey.TYPE_OFFLINE_FINDING) {
            return null;
        }
        return data;
    }
}
