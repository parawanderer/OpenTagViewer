package dev.wander.android.opentagviewer.ble;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The bytes of a Google Find Hub (FMDN) owner "ring" request, per the FMDN accessory
 * specification's Beacon Actions characteristic.
 *
 * <p>Write layout: {@code dataId(0x05) | len(0x0C) | auth(8) | components | timeout(u16 BE,
 * deciseconds) | volume}. The one-time authentication key binds the nonce the tracker handed out
 * on the preceding read: {@code HMAC-SHA256(ringKey, version | nonce | dataId | len | additional)[:8]}, where {@code ringKey = SHA256(EIK | 0x02)[:8]} is derived by the googlefind
 * service, which holds the identity key.
 *
 * <p>Pure Java so it can be tested on the JVM.
 */
public final class FmdnRingCommand {
    public static final byte DATA_ID_RING = 0x05;
    /** Ring every component the tracker has. */
    public static final byte COMPONENTS_ALL = (byte) 0xFF;
    /** Components 0 means stop. */
    public static final byte COMPONENTS_STOP = 0x00;
    public static final byte VOLUME_DEFAULT = 0x00;
    // Data length counts the 8-byte authentication key plus the additional data (a length of 4
    // made a real tracker drop the link)
    private static final byte LENGTH = 12;
    /** Direction byte some firmware expects appended to the HMAC input (fallback variants only). */
    private static final byte FROM_SEEKER = 0x01;

    private FmdnRingCommand() {
    }

    static byte[] additionalData(final byte components, final int timeoutDeciseconds, final byte volume) {
        return new byte[]{components, (byte) (timeoutDeciseconds >> 8), (byte) timeoutDeciseconds, volume};
    }

    /** The 8-byte one-time authentication key. */
    public static byte[] authKey(final byte[] ringKey, final int version, final byte[] nonce,
                                 final byte dataId, final byte length, final byte[] additional) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ringKey, "HmacSHA256"));
            mac.update((byte) version);
            mac.update(nonce);
            mac.update(dataId);
            mac.update(length);
            mac.update(additional);
            return Arrays.copyOf(mac.doFinal(), 8);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The value to write, given what the tracker's Beacon Actions characteristic returned on read
     * ({@code version | nonce(8)}).
     */
    public static byte[] ringWrite(final byte[] ringKey, final byte[] readValue, final byte components,
                                   final int timeoutDeciseconds, final byte volume) {
        return ringWrite(ringKey, readValue, components, timeoutDeciseconds, volume, VARIANTS[0]);
    }

    /**
     * One way of phrasing the ring request. Trackers differ: some predate the volume byte, and
     * implementations disagree on the trailing direction byte in the HMAC input.
     */
    public static final class Variant {
        public final boolean withVolume;
        public final boolean trailingDirection;

        public Variant(final boolean withVolume, final boolean trailingDirection) {
            this.withVolume = withVolume;
            this.trailingDirection = trailingDirection;
        }

        @Override
        public String toString() {
            return (withVolume ? "4-byte" : "3-byte") + (trailingDirection ? "+dir" : "");
        }
    }

    /**
     * Tried in this order. The first is what a real tracker accepted: four bytes of additional
     * data and no trailing direction byte (that byte belongs to the tracker's own notification
     * authentication, not to the seeker's request). The rest are fallbacks for other firmware.
     */
    public static final Variant[] VARIANTS = {
            new Variant(true, false), new Variant(false, false),
            new Variant(true, true), new Variant(false, true),
    };

    public static byte[] ringWrite(final byte[] ringKey, final byte[] readValue, final byte components,
                                   final int timeoutDeciseconds, final byte volume, final Variant variant) {
        if (readValue == null || readValue.length < 9) {
            throw new IllegalArgumentException("Beacon Actions read returned no nonce");
        }
        final int version = readValue[0] & 0xFF;
        final byte[] nonce = Arrays.copyOfRange(readValue, 1, 9);
        final byte[] full = additionalData(components, timeoutDeciseconds, volume);
        final byte[] additional = variant.withVolume ? full : Arrays.copyOf(full, 3);
        final byte length = (byte) (8 + additional.length);
        final byte[] auth;
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ringKey, "HmacSHA256"));
            mac.update((byte) version);
            mac.update(nonce);
            mac.update(DATA_ID_RING);
            mac.update(length);
            mac.update(additional);
            if (variant.trailingDirection) mac.update(FROM_SEEKER);
            auth = Arrays.copyOf(mac.doFinal(), 8);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        final byte[] out = new byte[2 + 8 + additional.length];
        out[0] = DATA_ID_RING;
        out[1] = length;
        System.arraycopy(auth, 0, out, 2, 8);
        System.arraycopy(additional, 0, out, 10, additional.length);
        return out;
    }

    /**
     * Whether FMDN service data (UUID 0xFEAA: frame type, then the 20-byte EID) carries one of
     * the given EIDs, as lowercase hex.
     */
    public static boolean matchesEid(final byte[] serviceData, final Collection<String> eidsHex) {
        if (serviceData == null || serviceData.length < 21) return false;
        final int frame = serviceData[0] & 0xFF;
        if (frame != 0x40 && frame != 0x41) return false;
        return eidsHex.contains(hex(Arrays.copyOfRange(serviceData, 1, 21)));
    }

    public static String hex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    public static byte[] unhex(final String hex) {
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
