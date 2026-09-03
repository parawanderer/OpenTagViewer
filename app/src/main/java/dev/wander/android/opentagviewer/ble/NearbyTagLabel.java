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

    /** Filled dot, for {@link #signalStrengthBars}. */
    private static final char BAR_FILLED = '●';

    /** Hollow dot, for {@link #signalStrengthBars}. */
    private static final char BAR_EMPTY = '○';

    /** How many dots {@link #signalStrengthBars} draws - filled and hollow together. */
    private static final int SIGNAL_BAR_COUNT = 5;

    /**
     * A five-dot signal meter for a sighting's RSSI, e.g. {@code "●●●○○"} - no words, so no
     * string resource and no locale to get it from.
     *
     * <p><b>Deliberately not a distance.</b> An earlier version of this feature converted RSSI
     * to metres through the standard log-distance path loss model, calibrated against a real
     * accessory at a measured 50 cm. Moved to 2 m, the same accessory read RSSI values that
     * overlapped the readings taken at 50 cm - the noise from multipath and antenna orientation
     * on a desk was larger than the signal difference between those two distances, so no
     * calibration constant could have told them apart. A number would have kept implying a
     * precision the underlying signal does not have. A dot count makes a weaker claim that is
     * actually true: the reading went up or down, which is still useful for homing in on a tag
     * while moving, without pretending to say by how far.
     *
     * <p>{@link #signalStrengthLevel}'s thresholds are not calibrated to a particular distance
     * for that reason - they only need to separate stronger readings from weaker ones as someone
     * moves.
     */
    public static String signalStrengthBars(final int rssi) {
        final int filled = signalStrengthLevel(rssi);
        final StringBuilder bars = new StringBuilder(SIGNAL_BAR_COUNT);
        for (int i = 0; i < SIGNAL_BAR_COUNT; i++) {
            bars.append(i < filled ? BAR_FILLED : BAR_EMPTY);
        }
        return bars.toString();
    }

    /**
     * How many of {@link #signalStrengthBars}' five dots are filled, from 1 (weakest) to 5
     * (strongest) - never 0, since a sighting existing at all means some signal was heard.
     *
     * <p>10 dB per step, which is also the noise band the field test behind
     * {@link #signalStrengthBars}'s doc turned up: two readings of the same real accessory,
     * standing still, varied by that much on their own.
     */
    static int signalStrengthLevel(final int rssi) {
        if (rssi >= -55) {
            return 5;
        }
        if (rssi >= -65) {
            return 4;
        }
        if (rssi >= -75) {
            return 3;
        }
        if (rssi >= -85) {
            return 2;
        }
        return 1;
    }
}
