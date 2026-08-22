package dev.wander.android.opentagviewer.util;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * The list with one tag picked up from {@code from} and put down at {@code to}.
     *
     * <p>What a drag means, kept here rather than in the adapter so it can be tested without a
     * device. {@code Collections.rotate} semantics: everything between the two shuffles along by
     * one, which is what a finger dragging a row past its neighbours looks like - as against a
     * swap, which would leave the row that was passed over sitting in the wrong place.
     *
     * @return a new list. Out-of-range indices give back an unchanged copy rather than throwing:
     *         this is driven by a gesture, and a view holder can be recycled mid-drag.
     */
    public static List<BeaconInformation> moved(
            final List<BeaconInformation> tags, final int from, final int to) {

        final List<BeaconInformation> out = new ArrayList<>(tags);

        if (from < 0 || to < 0 || from >= out.size() || to >= out.size() || from == to) {
            return out;
        }

        out.add(to, out.remove(from));
        return out;
    }

    /**
     * The list with everything selected brought to the front.
     *
     * <p>The bulk half of arranging, for the case dragging is worst at. Both groups keep the
     * order they were already in - somebody who selected three tags scattered down the list gets
     * them at the top in the order they appeared, not in the order they happened to tick them,
     * because the list is what they are looking at.
     *
     * @param selected beacon ids. Anything in here that is not in {@code tags} is ignored.
     */
    public static List<BeaconInformation> movedToTop(
            final List<BeaconInformation> tags, final Set<String> selected) {

        final List<BeaconInformation> out = new ArrayList<>(tags.size());

        for (final BeaconInformation tag : tags) {
            if (selected.contains(tag.getBeaconId())) {
                out.add(tag);
            }
        }
        for (final BeaconInformation tag : tags) {
            if (!selected.contains(tag.getBeaconId())) {
                out.add(tag);
            }
        }

        return out;
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
