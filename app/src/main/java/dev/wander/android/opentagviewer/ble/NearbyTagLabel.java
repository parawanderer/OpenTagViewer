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
}
