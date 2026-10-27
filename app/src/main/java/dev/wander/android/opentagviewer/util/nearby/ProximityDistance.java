package dev.wander.android.opentagviewer.util.nearby;

import java.util.Locale;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * RSSI turned into a rough metres-away figure for a nearby Find My device.
 *
 * <p><b>A log-distance path-loss estimate, honest about being one.</b> RSSI is what the radio
 * reports, not a tape measure: the same tag at the same distance reads differently through a
 * pocket, a wall, or held against the phone. The number exists to sort "about an arm's length"
 * from "somewhere in the flat", and it is rendered with a "~" on screen so nobody reads more
 * precision into it than the physics carries.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProximityDistance {

    /**
     * RSSI the tag would read at exactly one metre, the model's calibration point.
     *
     * <p>-59 dBm is the conventional figure for a BLE beacon, and the same one AirGuard's
     * distance estimate is built on. A real calibration knob is a radar-screen concern; the
     * list only needs a plausible sort order.
     */
    static final int TX_POWER_AT_1M_DBM = -59;

    /** Path-loss exponent folded into the denominator: free space would be 20, indoor ~25. */
    static final double PATH_LOSS_DENOMINATOR = 25.0;

    /** Estimated metres away. RSSI weaker than nothing meaningful is clamped to the far end. */
    public static double estimateMeters(final int rssi) {
        if (rssi >= 0) {
            return 1.0;
        }
        return Math.pow(10.0, (TX_POWER_AT_1M_DBM - rssi) / PATH_LOSS_DENOMINATOR);
    }

    /**
     * One decimal below ten metres, whole metres beyond it - the precision the physics allows,
     * so "-45 dBm" reads "~2 m" rather than a false-precision "~2.3 m".
     */
    public static String describe(final int rssi) {
        final double meters = estimateMeters(rssi);
        if (meters < 10.0) {
            return String.format(Locale.US, "%.1f", meters);
        }
        return String.format(Locale.US, "%.0f", meters);
    }
}
