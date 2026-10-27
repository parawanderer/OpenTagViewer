package dev.wander.android.opentagviewer.ble;

import androidx.annotation.Nullable;

import lombok.Getter;

/**
 * The advertisement key a Find My frame is broadcasting, reconstructed from the frame and the
 * BLE address it arrived on.
 *
 * <p><b>Why this is separate from {@link FindMyAdvertisement}.</b> That class answers "what did
 * the tag say about itself" - owner-present, battery, the raw status byte - and deliberately
 * does not touch the key, because the only reader that wanted keys resolves them a different
 * way: {@code NearbyTagWatcher} knows which of the user's own tags to expect and matches on
 * derived MAC. A scanner that wants to show *every* Find My device has no expected set, so the
 * key itself is the identity. That is a different question, asked by different callers, and it
 * gets its own class rather than growing a second responsibility inside the parser.
 *
 * <p><b>The layout is the one FindMy.py's {@code scanner.py} implements.</b> A 0x12 frame is a
 * type byte, a length byte, then a payload in one of two shapes:
 *
 * <ul>
 *   <li><b>Separated</b> ({@code len == 0x19}): the tag's owner is away and it begs to be found.
 *       The payload is {@code status | pubkey[6..27] (22B) | topTwoBits | hint}; the first six
 *       key bytes never travel in the payload at all - they *are* the BLE address, with the top
 *       two bits of key byte 0 living in the payload's {@code topTwoBits} byte. Reassembly is
 *       {@code key[0] = (topTwo << 6) | (mac[0] & 0x3f)}, {@code key[1..5] = mac[1..5]},
 *       {@code key[6..27] = payload[1..22]}.</li>
 *   <li><b>Nearby</b> ({@code len == 0x02}): the owner is close. The payload is only
 *       {@code status | topTwoBits}, and the same formula reconstructs just the six-byte prefix:
 *       {@code key[0] = (topTwo << 6) | (mac[0] & 0x3f)}, {@code key[1..5] = mac[1..5]}.</li>
 * </ul>
 *
 * <p><b>The six-byte prefix is the join key across both shapes.</b> Within one key period a tag
 * moving between separated and nearby broadcasts the same advertisement key, so the prefix
 * reconstructed identically from either shape is what ties its frames to one identity - and it
 * is finer than the MAC alone, because the two bits the MAC cannot carry arrive in the payload.
 * Everything about rotation is somebody else's problem: after the ~15 minute roll the prefix is
 * a different identity, which is exactly what the rotation heuristic elsewhere keys on.
 *
 * <p><b>Apple manufacturer data is a TLV chain, not one frame.</b> An iPhone routinely sends
 * several Apple payloads concatenated in the same {@code 0x004C} blob; the Find My frame is not
 * guaranteed to sit first. This walks the chain and takes the first {@code 0x12}, where
 * {@link FindMyAdvertisement#parse} reads offset zero - the difference matters on the crowded
 * end of a scan, which is precisely where a generic scanner runs.
 *
 * <p>Pure parsing, no Android in it: every rule above is covered by a JVM test.
 */
@Getter
public final class FindMyFrameKey {

    /** Apple's offline-finding advertisement type, first byte of the Find My TLV. */
    static final byte TYPE_OFFLINE_FINDING = 0x12;

    /** Length byte of the separated (full-beacon) payload shape. */
    static final int LEN_SEPARATED = 0x19;

    /** Length byte of the nearby (short) payload shape. */
    static final int LEN_NEARBY = 0x02;

    /** The reconstructed six-byte prefix, shared by both payload shapes. */
    private final byte[] keyPrefix;

    /** The whole 28-byte advertisement key, or null on a nearby frame that does not carry it. */
    @Nullable
    private final byte[] fullKey;

    /** The separated frame's hint byte; 0 on a nearby frame, which carries none. */
    private final int hint;

    /** The status byte the frame opened with, kept raw for the same reason it is kept on the advertisement. */
    private final int statusByte;

    private FindMyFrameKey(
            final byte[] keyPrefix,
            @Nullable final byte[] fullKey,
            final int hint,
            final int statusByte) {
        this.keyPrefix = keyPrefix;
        this.fullKey = fullKey;
        this.hint = hint;
        this.statusByte = statusByte;
    }

    /**
     * Reconstructs the key from a Find My TLV and the MAC it was heard on, or null if this is
     * not a Find My frame or is too short to read.
     *
     * @param appleManufacturerData the whole {@code 0x004C} manufacturer blob - a TLV chain
     * @param macAddress the BLE address, {@code "AA:BB:CC:DD:EE:FF"}
     */
    @Nullable
    public static FindMyFrameKey parse(
            @Nullable final byte[] appleManufacturerData, @Nullable final String macAddress) {
        final byte[] mac = macBytes(macAddress);
        if (mac == null) {
            return null;
        }

        final int tlvStart = findFindMyTlv(appleManufacturerData);
        if (tlvStart < 0) {
            return null;
        }

        // tlvStart points at the type byte; +1 is the length, +2 the payload.
        final int payloadLen = appleManufacturerData[tlvStart + 1] & 0xFF;
        final byte[] payload = new byte[payloadLen];
        System.arraycopy(appleManufacturerData, tlvStart + 2, payload, 0, payloadLen);

        if (payloadLen == LEN_NEARBY) {
            final byte[] prefix = new byte[6];
            prefix[0] = (byte) ((payload[1] << 6) | (mac[0] & 0x3F));
            System.arraycopy(mac, 1, prefix, 1, 5);
            return new FindMyFrameKey(prefix, null, 0, payload[0] & 0xFF);
        }

        if (payloadLen == LEN_SEPARATED) {
            final byte[] key = new byte[28];
            key[0] = (byte) ((payload[23] << 6) | (mac[0] & 0x3F));
            System.arraycopy(mac, 1, key, 1, 5);
            System.arraycopy(payload, 1, key, 6, 22);

            final byte[] prefix = new byte[6];
            System.arraycopy(key, 0, prefix, 0, 6);
            return new FindMyFrameKey(prefix, key, payload[24] & 0xFF, payload[0] & 0xFF);
        }

        // A 0x12 with neither known length: not a shape we can reconstruct from.
        return null;
    }

    /**
     * Walks the TLV chain for the first offline-finding frame.
     *
     * @return index of the {@code 0x12} type byte, or -1. Indexing past the returned value is
     *         safe only if the caller checked {@code +2 + len <= data.length} itself - which is
     *         why this also bounds-checks each TLV before returning it.
     */
    static int findFindMyTlv(@Nullable final byte[] data) {
        if (data == null) {
            return -1;
        }

        int offset = 0;
        while (offset + 2 <= data.length) {
            final int type = data[offset];
            final int len = data[offset + 1] & 0xFF;
            if (offset + 2 + len > data.length) {
                // Truncated tail: nothing readable left.
                return -1;
            }
            if (type == TYPE_OFFLINE_FINDING) {
                return offset;
            }
            offset += 2 + len;
        }
        return -1;
    }

    /** {@code "AA:BB:CC:DD:EE:FF"} to six bytes, or null on anything else. */
    @Nullable
    private static byte[] macBytes(@Nullable final String macAddress) {
        if (macAddress == null || macAddress.length() != 17) {
            return null;
        }
        final byte[] mac = new byte[6];
        try {
            for (int i = 0; i < 6; i++) {
                mac[i] = (byte) Integer.parseInt(
                        macAddress.substring(i * 3, i * 3 + 2), 16);
            }
        } catch (final NumberFormatException notHex) {
            return null;
        }
        return mac;
    }

    /** Lowercase hex, the form a name store and a list row both key on. */
    public String keyPrefixHex() {
        return hex(this.keyPrefix);
    }

    /** Lowercase hex of the full key, or null on a nearby frame - same rule as {@link #fullKey}. */
    @Nullable
    public String fullKeyHex() {
        return this.fullKey == null ? null : hex(this.fullKey);
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder out = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
