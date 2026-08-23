package dev.wander.android.opentagviewer.ble;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * A rough distance estimate from a BLE advertisement's signal strength.
 *
 * <p><b>An estimate, not a measurement, and a rough one.</b> The log-distance path loss model
 * this uses assumes free space and a fixed transmit power; a pocket, a wall, or the accessory's
 * own antenna orientation shifts the reading by metres, not centimetres, and two accessories
 * standing side by side can report different distances for it. It is worth showing anyway
 * because a coarse "closer" or "further" as someone walks is still useful for homing in on a
 * tag by eye - it is not worth presenting as anything more precise than that, and nothing here
 * claims otherwise.
 *
 * <p>No Android in here, so the model is reachable by a JVM test.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RssiDistance {

    /**
     * Assumed RSSI at one metre, in dBm.
     *
     * <p>The de facto default for exactly this reason - most BLE accessories (AirTags among
     * them) do not publish a calibrated transmit power over an advertisement a stranger's phone
     * can read, so there is no per-device value to use instead. A real accessory can plausibly
     * sit several dB either side of this.
     */
    private static final double REFERENCE_RSSI_AT_ONE_METRE = -59.0;

    /**
     * Path loss exponent for free space. Indoors, behind obstacles, or through a pocket, the
     * true exponent runs higher - which this does not attempt to detect, so a reading is always
     * biased toward "closer than it looks" in those cases rather than toward "further".
     */
    private static final double PATH_LOSS_EXPONENT = 2.0;

    /**
     * Estimated distance in metres for one advertisement's RSSI.
     *
     * <p>The standard log-distance path loss model, solved for distance:
     * {@code 10 ^ ((referenceRssi - rssi) / (10 * pathLossExponent))}.
     */
    public static double estimateMetres(final int rssi) {
        return Math.pow(10.0,
                (REFERENCE_RSSI_AT_ONE_METRE - rssi) / (10.0 * PATH_LOSS_EXPONENT));
    }
}
