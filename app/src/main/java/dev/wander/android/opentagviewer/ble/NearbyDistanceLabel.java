package dev.wander.android.opentagviewer.ble;

import androidx.annotation.StringRes;

import java.util.Locale;

import dev.wander.android.opentagviewer.R;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Which unit a {@link RssiDistance} estimate should show in, and the rounded number for it.
 *
 * <p>Split from {@link RssiDistance} for the same reason {@link NearbyTagLabel} is split from
 * the strings it points at: the unit decision is locale logic with rules in it, reachable by a
 * JVM test without a {@code Context}; turning the chosen resource into words on screen still
 * needs one.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NearbyDistanceLabel {

    private static final double METRES_TO_FEET = 3.28084;

    /**
     * Countries that read a short distance in feet rather than metres.
     *
     * <p>The three ICU's own locale data lists as customary-unit countries. The United Kingdom
     * is deliberately not here despite miles-for-road-distances - the everyday, arm's-length
     * distances this label is for are given in metres there, same as on the continent.
     */
    private static boolean usesImperialUnits(final Locale locale) {
        final String country = locale.getCountry();
        return "US".equals(country) || "LR".equals(country) || "MM".equals(country);
    }

    /**
     * The estimate rounded to a whole number in whichever unit {@code locale} prefers.
     *
     * <p>Clamped to at least one. This is already a rough estimate - see {@link RssiDistance} -
     * and "~0 m" would read as a more exact claim than "~1 m" while being no truer.
     */
    public static int roundedValueFor(final double metres, final Locale locale) {
        final double inPreferredUnit = usesImperialUnits(locale) ? metres * METRES_TO_FEET : metres;
        return Math.max(1, (int) Math.round(inPreferredUnit));
    }

    /** The unit word to format {@link #roundedValueFor} with, e.g. {@code "%1$d m"}. */
    @StringRes
    public static int unitStringFor(final Locale locale) {
        return usesImperialUnits(locale) ? R.string.distance_feet : R.string.distance_metres;
    }
}
