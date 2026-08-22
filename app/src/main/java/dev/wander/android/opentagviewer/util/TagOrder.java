package dev.wander.android.opentagviewer.util;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.wander.android.opentagviewer.data.model.BeaconInformation;

/**
 * What order the user's tags appear in, on the device list and in the map's carousel.
 *
 * <p><b>A pure function, deliberately, and not a {@code SELECT ... ORDER BY}.</b> Half of this
 * rule is the user's arrangement, which is a stored number and would sort fine in SQL; the other
 * half is what to do with a tag that has no arrangement yet, which depends on classifying it -
 * and that classification reads a plist. Doing it in SQL would mean either a column that has to
 * be kept in step with the plist, or no rule at all. Doing it here costs a sort over a list that
 * is single figures long, and makes the whole thing testable on the JVM in milliseconds.
 *
 * <h2>The two halves</h2>
 *
 * <p><b>An arranged tag goes where it was put.</b> Dragging commits a position for <i>every</i>
 * row on screen, not just the one that moved, which is what makes "explicit positions first"
 * safe: after any drag there is no mixed state to reason about. A version that stored only the
 * moved tag's position would send a single dragged row to the top of a list of unarranged ones,
 * which is not what dragging one row to the bottom is asking for.
 *
 * <p><b>An unarranged tag falls in behind, accessories first.</b> This is the order somebody gets
 * before they have ever touched anything, and after importing something new into a list they had
 * already arranged. Accessories lead because they are the ones the app can actually keep up to
 * date; the owner's own devices are only findable through the crowd-sourced network here, so they
 * update rarely and arbitrarily and are the least useful rows on the screen. Most people will
 * never see one - they are hidden entirely unless the setting is on, see {@link TagVisibility}.
 *
 * <p><b>Ties keep the order they arrived in</b>, which is the database's, so nothing shuffles
 * between launches for no reason.
 */
public final class TagOrder {

    private TagOrder() {
    }

    /** Sorts before anything unarranged, so the two halves cannot interleave. */
    private static final int ARRANGED = 0;
    private static final int UNARRANGED = 1;

    /** Within the unarranged half: what the app can keep up to date, then what it cannot. */
    private static final int ACCESSORY = 0;
    private static final int OWN_DEVICE = 1;

    /**
     * The tags in the order they should be shown.
     *
     * <p>Positions are read off each tag's {@code uiOrder}, which the parser fills in from
     * {@code UserBeaconOptions} alongside the nickname and the emoji. They need not be dense or
     * start at zero - only their relative order is read - so a removed tag leaving a gap changes
     * nothing.
     *
     * @param tags what to order. Not modified.
     * @return a new list, same size, same elements.
     */
    public static List<BeaconInformation> sorted(final List<BeaconInformation> tags) {
        final List<BeaconInformation> out = new ArrayList<>(tags);

        // Stable, so anything this comparator calls equal stays in the order it came in.
        out.sort(Comparator
                .comparingInt((BeaconInformation tag) -> half(tag.getUiOrder()))
                .thenComparingInt(TagOrder::within));

        return out;
    }

    /**
     * The positions to store when the user has just finished dragging.
     *
     * <p>Every row gets one, including the ones that did not move - see the class note. The
     * numbers are the list indices, which is the simplest thing that round-trips through
     * {@link #sorted}: dense, ascending, and readable in a database dump.
     *
     * @param inTheOrderShown the tags exactly as they now appear on screen.
     */
    public static Map<String, Integer> positionsFor(final List<BeaconInformation> inTheOrderShown) {
        final Map<String, Integer> positions = new LinkedHashMap<>();

        for (int i = 0; i < inTheOrderShown.size(); i++) {
            positions.put(inTheOrderShown.get(i).getBeaconId(), i);
        }

        return positions;
    }

    private static int half(@Nullable final Integer position) {
        return position == null ? UNARRANGED : ARRANGED;
    }

    private static int within(final BeaconInformation tag) {
        final Integer position = tag.getUiOrder();
        if (position != null) {
            return position;
        }
        return tag.isOwnDevice() ? OWN_DEVICE : ACCESSORY;
    }
}
