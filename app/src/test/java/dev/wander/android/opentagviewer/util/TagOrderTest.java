package dev.wander.android.opentagviewer.util;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.Test;

import dev.wander.android.opentagviewer.data.model.BeaconInformation;

/**
 * The order tags appear in, before and after anybody drags one.
 *
 * <p>Pure arithmetic over a short list, so it belongs here rather than on a device - and it is
 * worth having in full, because every failure mode of an ordering rule looks like a UI bug.
 * A tag that jumps position on relaunch, one that vanishes from the bottom of the carousel, a
 * newly imported tag landing in the middle of a hand-made arrangement: all of them are this
 * function, and none of them throw.
 */
public class TagOrderTest {

    // --- before anybody has arranged anything -------------------------------------------------

    /**
     * <b>Accessories lead, the owner's own devices follow.</b>
     *
     * <p>The order a new user gets. Accessories first because they are the ones the app can
     * actually keep up to date - a device is only findable through the crowd-sourced network
     * here, so it updates rarely and is the least useful row on the screen.
     */
    @Test
    public void unarrangedTagsPutAccessoriesBeforeTheOwnersDevices() {
        final List<BeaconInformation> mixed = Arrays.asList(
                device("ipad"), accessory("airtag"), device("macbook"), accessory("chipolo"));

        assertEquals(Arrays.asList("airtag", "chipolo", "ipad", "macbook"),
                idsOf(TagOrder.sorted(mixed)));
    }

    /**
     * <b>Within a group, the order the database gave is kept.</b>
     *
     * <p>The sort has to be stable or tags shuffle between launches for no reason the user can
     * see, which reads as the app losing track of them.
     */
    @Test
    public void tagsThatTieKeepTheOrderTheyArrivedIn() {
        final List<BeaconInformation> asStored = Arrays.asList(
                accessory("c"), accessory("a"), accessory("b"));

        assertEquals("nothing should be re-sorted by id, or by anything else",
                Arrays.asList("c", "a", "b"), idsOf(TagOrder.sorted(asStored)));
    }

    /** An empty list and a single tag are not special cases. */
    @Test
    public void trivialListsComeBackUnchanged() {
        assertEquals(0, TagOrder.sorted(List.of()).size());
        assertEquals(List.of("only"), idsOf(TagOrder.sorted(List.of(accessory("only")))));
    }

    // --- after a drag -------------------------------------------------------------------------

    /** An arrangement is obeyed exactly, including putting a device above an accessory. */
    @Test
    public void anarrangementIsObeyed() {
        final List<BeaconInformation> arranged = Arrays.asList(
                at(accessory("airtag"), 2), at(device("ipad"), 0), at(accessory("chipolo"), 1));

        assertEquals("the default accessories-first rule must not override a deliberate drag",
                Arrays.asList("ipad", "chipolo", "airtag"), idsOf(TagOrder.sorted(arranged)));
    }

    /**
     * <b>Positions need not be dense, or start at zero.</b>
     *
     * <p>Only their relative order is read, so a tag being removed and leaving a gap changes
     * nothing - which is what stops "remove a tag" from needing to renumber every other row.
     */
    @Test
    public void gapsInThePositionsDoNotMatter() {
        final List<BeaconInformation> withGaps = Arrays.asList(
                at(accessory("c"), 90), at(accessory("a"), 7), at(accessory("b"), 41));

        assertEquals(Arrays.asList("a", "b", "c"), idsOf(TagOrder.sorted(withGaps)));
    }

    /**
     * <b>A newly imported tag lands at the end, not in the middle.</b>
     *
     * <p>The case that makes "arranged before unarranged" the right rule. Somebody who has spent
     * a minute ordering six tags and then imports a seventh should find it at the bottom, not
     * inserted wherever its classification happens to put it.
     */
    @Test
    public void anewTagJoinsTheEndOfAnExistingArrangement() {
        final List<BeaconInformation> list = Arrays.asList(
                accessory("just-imported"), at(accessory("first"), 0), at(accessory("second"), 1));

        assertEquals(Arrays.asList("first", "second", "just-imported"), idsOf(TagOrder.sorted(list)));
    }

    /** And several new ones keep the accessories-first rule among themselves. */
    @Test
    public void severalNewTagsFallInBehindInTheDefaultOrder() {
        final List<BeaconInformation> list = Arrays.asList(
                device("new-ipad"), at(accessory("arranged"), 5), accessory("new-tag"));

        assertEquals(Arrays.asList("arranged", "new-tag", "new-ipad"), idsOf(TagOrder.sorted(list)));
    }

    // --- writing an arrangement back ----------------------------------------------------------

    /** Dragging commits a position for every row, which is what keeps the rule coherent. */
    @Test
    public void everyTagGetsAPositionWhenTheListIsArranged() {
        final Map<String, Integer> positions = TagOrder.positionsFor(
                Arrays.asList(accessory("a"), device("b"), accessory("c")));

        assertEquals(Map.of("a", 0, "b", 1, "c", 2), positions);
    }

    /**
     * <b>What is written comes back as what was written.</b>
     *
     * <p>The round trip, which is the property that actually matters: arrange a list, store it,
     * read it back in whatever order the database felt like, and get the arrangement again.
     */
    @Test
    public void anarrangementSurvivesBeingStoredAndReadBackInAnyOrder() {
        final List<BeaconInformation> asArranged = Arrays.asList(
                device("ipad"), accessory("chipolo"), accessory("airtag"));

        final Map<String, Integer> stored = TagOrder.positionsFor(asArranged);

        // Coming back out of the database in an unrelated order, as it well might.
        final List<BeaconInformation> reloaded = withPositions(
                Arrays.asList(accessory("airtag"), device("ipad"), accessory("chipolo")), stored);

        assertEquals(idsOf(asArranged), idsOf(TagOrder.sorted(reloaded)));
    }

    /** Arranging an already-arranged list twice is a no-op, not a slow rotation. */
    @Test
    public void sortingIsIdempotent() {
        final List<BeaconInformation> mixed = Arrays.asList(
                device("ipad"), accessory("airtag"), device("macbook"), accessory("chipolo"));

        final List<BeaconInformation> once = TagOrder.sorted(mixed);

        assertEquals(idsOf(once), idsOf(TagOrder.sorted(once)));
    }

    /** The input list is not modified - callers hold on to theirs. */
    @Test
    public void theinputListIsLeftAlone() {
        final List<BeaconInformation> original = new ArrayList<>(
                Arrays.asList(device("ipad"), accessory("airtag")));

        TagOrder.sorted(original);

        assertEquals(Arrays.asList("ipad", "airtag"), idsOf(original));
    }

    // --- helpers ------------------------------------------------------------------------------

    private static List<String> idsOf(final List<BeaconInformation> beacons) {
        return beacons.stream().map(BeaconInformation::getBeaconId).collect(Collectors.toList());
    }

    private static BeaconInformation accessory(final String id) {
        return BeaconInformation.builder().beaconId(id).model("").build();
    }

    private static BeaconInformation device(final String id) {
        return BeaconInformation.builder().beaconId(id).model("iPad13,18").build();
    }

    private static BeaconInformation at(final BeaconInformation tag, final int position) {
        tag.setUiOrder(position);
        return tag;
    }

    private static List<BeaconInformation> withPositions(
            final List<BeaconInformation> tags, final Map<String, Integer> positions) {

        tags.forEach(tag -> tag.setUiOrder(positions.get(tag.getBeaconId())));
        return tags;
    }
}
