package dev.wander.android.opentagviewer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import dev.wander.android.opentagviewer.data.model.BeaconInformation;

/**
 * Which tags are shown - and, because it is the same decision, which are searched for.
 *
 * <p>The classification itself is covered by {@code OwnDeviceClassificationTest}. What is pinned
 * here is what the filter does with it, including the two properties the screens depend on:
 * order is untouched, and nothing is dropped that was not meant to be.
 */
public class TagVisibilityTest {

    private static final BeaconInformation AIRTAG = accessory("airtag");
    private static final BeaconInformation CHIPOLO = accessory("chipolo");
    private static final BeaconInformation IPAD = device("ipad", "iPad13,18");
    private static final BeaconInformation MACBOOK = device("macbook", "MacBookAir10,1");

    /** Off - the default - and the owner's own devices are gone. */
    @Test
    public void theownersDevicesAreLeftOutByDefault() {
        final List<BeaconInformation> shown = TagVisibility.visible(
                Arrays.asList(AIRTAG, IPAD, CHIPOLO, MACBOOK), false);

        assertEquals(Arrays.asList("airtag", "chipolo"), idsOf(shown));
    }

    /** On, and everything is there. */
    @Test
    public void turningItOnShowsEverything() {
        final List<BeaconInformation> all = Arrays.asList(AIRTAG, IPAD, CHIPOLO, MACBOOK);

        assertEquals(Arrays.asList("airtag", "ipad", "chipolo", "macbook"),
                idsOf(TagVisibility.visible(all, true)));
    }

    /**
     * <b>The order that came in is the order that comes out.</b>
     *
     * <p>This filter runs before anything the user arranged is applied, and a filter that
     * reordered would silently undo a drag. Kept as a property rather than a comment because
     * {@code Stream.filter} preserving encounter order is easy to break by reaching for a
     * {@code Set} on the way through.
     */
    @Test
    public void filteringDoesNotReorderWhatIsLeft() {
        final List<BeaconInformation> reversed = Arrays.asList(CHIPOLO, MACBOOK, AIRTAG, IPAD);

        assertEquals(Arrays.asList("chipolo", "airtag"),
                idsOf(TagVisibility.visible(reversed, false)));
    }

    /**
     * <b>Nothing is dropped when there is nothing to drop</b>, and the same list comes straight
     * back when the setting is on - a person with no Apple devices in their account, which is
     * most people, pays nothing for this.
     */
    @Test
    public void alistOfOnlyAccessoriesSurvivesWhicheverWayTheSettingIs() {
        final List<BeaconInformation> onlyTags = Arrays.asList(AIRTAG, CHIPOLO);

        assertEquals(2, TagVisibility.visible(onlyTags, false).size());
        assertSame("with the setting on the list is handed straight back",
                onlyTags, TagVisibility.visible(onlyTags, true));
    }

    /** An empty list is not a special case anywhere. */
    @Test
    public void anemptyListStaysEmpty() {
        assertEquals(0, TagVisibility.visible(List.of(), false).size());
        assertEquals(0, TagVisibility.visible(List.of(), true).size());
    }

    /**
     * <b>Hiding everything is allowed and must not be confused with a failure.</b>
     *
     * <p>Somebody whose account holds only their own devices and no tags at all sees an empty
     * list. That is correct, and the screens have an empty state for it - what must not happen
     * is this throwing or handing back the unfiltered list as a "sensible fallback".
     */
    @Test
    public void anaccountOfNothingButDevicesShowsNothing() {
        assertEquals(0, TagVisibility.visible(Arrays.asList(IPAD, MACBOOK), false).size());
    }

    private static List<String> idsOf(final List<BeaconInformation> beacons) {
        return beacons.stream().map(BeaconInformation::getBeaconId).collect(Collectors.toList());
    }

    private static BeaconInformation accessory(final String id) {
        return BeaconInformation.builder().beaconId(id).model("").build();
    }

    private static BeaconInformation device(final String id, final String model) {
        return BeaconInformation.builder().beaconId(id).model(model).build();
    }
}
