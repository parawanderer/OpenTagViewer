package dev.wander.android.opentagviewer.ble;

import androidx.annotation.StringRes;

import dev.wander.android.opentagviewer.R;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Which string resources describe a sighting, decided without touching a {@code Context}.
 *
 * <p>Split out so the choice is covered by a JVM test. Formatting it needs resources and a
 * locale, but choosing <i>which</i> resource does not, and the choice is the part with rules in
 * it.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NearbyTagLabel {

    /**
     * The short battery word for a tag card, e.g. "low".
     *
     * <p>Deliberately not the {@code battery_level_*} strings the debug panel uses. Those spell
     * out a percentage range and a caveat, which is right for a diagnostics row and far too long
     * for a line that also has to carry "Nearby" on a card sized to a phone.
     */
    @StringRes
    public static int shortBatteryLabel(final FindMyAdvertisement.BatteryLevel level) {
        switch (level) {
            case MEDIUM: return R.string.battery_short_medium;
            case LOW: return R.string.battery_short_low;
            case VERY_LOW: return R.string.battery_short_very_low;
            case FULL:
            default: return R.string.battery_short_full;
        }
    }

    /**
     * A signal strength word for a sighting's RSSI - "strong", "medium" or "weak".
     *
     * <p><b>Deliberately not a distance.</b> An earlier version of this feature converted RSSI
     * to metres through the standard log-distance path loss model, calibrated against a real
     * accessory at a measured 50 cm. Moved to 2 m, the same accessory read RSSI values that
     * overlapped the readings taken at 50 cm - the noise from multipath and antenna orientation
     * on a desk was larger than the signal difference between those two distances, so no
     * calibration constant could have told them apart. A number would have kept implying a
     * precision the underlying signal does not have. A strength word makes a weaker claim that
     * is actually true: the reading went up or down, which is still useful for homing in on a
     * tag while moving, without pretending to say by how far.
     *
     * <p>Thresholds are not calibrated to a particular distance for that reason - they only
     * need to separate stronger readings from weaker ones as someone moves.
     */
    @StringRes
    public static int signalStrengthLabel(final int rssi) {
        if (rssi >= -60) {
            return R.string.signal_strength_strong;
        }
        if (rssi >= -75) {
            return R.string.signal_strength_medium;
        }
        return R.string.signal_strength_weak;
    }
}
