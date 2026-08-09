package dev.wander.android.opentagviewer.util.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for keeping the selected tag's marker on top of the ones it overlaps.
 * <br>
 * This behaviour was deleted by a refactor and nobody noticed for a fork's worth of commits,
 * because losing it throws nothing: the marker is still added, still at the right coordinates,
 * still tappable. It is simply underneath another one, so selecting a card moves the camera and
 * appears to do nothing at all.
 * <br>
 * The constants and the field it used were left behind unreferenced, which made the code look
 * present when it was not. A test asserting the calls actually happen is the only thing that
 * catches that.
 */
public class MarkerFocusTest {

    private static final String WALLET = "B1CE4F0C-2489-486E-8295-45690FACF1E8";
    private static final String KEYS = "46E881A1-3CD9-4965-AEA9-2D95414661E7";
    private static final String BAG = "049BC649-8733-4FA1-B31D-5BBDDE97E398";

    /** Records every re-order, so a test can assert the order as well as the result. */
    private static final class FakeMarkers implements MarkerFocus.Markers {
        final Map<String, String> markerIdsByBeaconId = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public String markerIdFor(String beaconId) {
            return this.markerIdsByBeaconId.get(beaconId);
        }

        @Override
        public void setZIndex(String markerId, float zIndex) {
            this.calls.add(markerId + (zIndex == MarkerFocus.ZINDEX_TOP ? ":top" : ":default"));
        }

        void draw(final String... beaconIds) {
            for (final String beaconId : beaconIds) {
                // The providers key markers by the beacon id they were built with.
                this.markerIdsByBeaconId.put(beaconId, beaconId);
            }
        }
    }

    private FakeMarkers markers;
    private MarkerFocus focus;

    @Before
    public void setUp() {
        this.markers = new FakeMarkers();
        this.focus = new MarkerFocus(this.markers);
    }

    // --- raising and lowering -------------------------------------------------------------

    @Test
    public void selectingATagRaisesItsMarker() {
        this.markers.draw(WALLET);

        this.focus.focus(WALLET);

        assertEquals(List.of(WALLET + ":top"), this.markers.calls);
        assertEquals(WALLET, this.focus.focusedBeaconId());
    }

    @Test
    public void selectingAnotherTagLowersThePreviousOne() {
        this.markers.draw(WALLET, KEYS);
        this.focus.focus(WALLET);
        this.markers.calls.clear();

        this.focus.focus(KEYS);

        // Lower first, then raise: leaving both raised puts them back at the same level.
        assertEquals(List.of(WALLET + ":default", KEYS + ":top"), this.markers.calls);
        assertEquals(KEYS, this.focus.focusedBeaconId());
    }

    @Test
    public void onlyOneTagIsEverRaised() {
        this.markers.draw(WALLET, KEYS, BAG);

        this.focus.focus(WALLET);
        this.focus.focus(KEYS);
        this.focus.focus(BAG);

        assertEquals(List.of(
                WALLET + ":top",
                WALLET + ":default", KEYS + ":top",
                KEYS + ":default", BAG + ":top"), this.markers.calls);
    }

    @Test
    public void reselectingTheSameTagChangesNothing() {
        this.markers.draw(WALLET);
        this.focus.focus(WALLET);
        this.markers.calls.clear();

        // Swiping the card list settles on the same card repeatedly; re-issuing the raise
        // every time would be pure churn against the map.
        this.focus.focus(WALLET);

        assertEquals(List.of(), this.markers.calls);
        assertEquals(WALLET, this.focus.focusedBeaconId());
    }

    // --- markers that are not there ---------------------------------------------------------

    @Test
    public void selectingATagWithNoMarkerYetDoesNothing() {
        // A card can be selected during the first draw, before its marker exists.
        this.focus.focus(WALLET);

        assertEquals(List.of(), this.markers.calls);
        assertNull(this.focus.focusedBeaconId());
    }

    @Test
    public void aTagWithNoMarkerDoesNotUnseatTheCurrentOne() {
        this.markers.draw(WALLET);
        this.focus.focus(WALLET);
        this.markers.calls.clear();

        this.focus.focus(KEYS); // no marker drawn for it

        // Lowering the wallet here would leave the pile with nothing raised at all.
        assertEquals(List.of(), this.markers.calls);
        assertEquals(WALLET, this.focus.focusedBeaconId());
    }

    @Test
    public void aPreviousMarkerThatHasSinceGoneIsSkippedRatherThanCrashing() {
        this.markers.draw(WALLET, KEYS);
        this.focus.focus(WALLET);
        this.markers.markerIdsByBeaconId.remove(WALLET); // its tag was deleted
        this.markers.calls.clear();

        this.focus.focus(KEYS);

        assertEquals(List.of(KEYS + ":top"), this.markers.calls);
        assertEquals(KEYS, this.focus.focusedBeaconId());
    }

    @Test
    public void aNullBeaconIdIsIgnored() {
        this.focus.focus(null);

        assertEquals(List.of(), this.markers.calls);
        assertNull(this.focus.focusedBeaconId());
    }

    // --- surviving a redraw -----------------------------------------------------------------

    @Test
    public void aRebuiltMarkerForTheSelectedTagIsBuiltRaised() {
        this.markers.draw(WALLET, KEYS);
        this.focus.focus(WALLET);

        // Every refresh removes and re-adds the markers. Raising only on selection would drop
        // the tag back under the pile a minute later, without the user touching anything.
        assertEquals(MarkerFocus.ZINDEX_TOP, this.focus.zIndexFor(WALLET), 0.0f);
        assertEquals(MarkerFocus.ZINDEX_DEFAULT, this.focus.zIndexFor(KEYS), 0.0f);
    }

    @Test
    public void anUnselectedTagIsBuiltAtTheDefaultLevel() {
        assertEquals(MarkerFocus.ZINDEX_DEFAULT, this.focus.zIndexFor(WALLET), 0.0f);
        assertEquals(MarkerFocus.ZINDEX_DEFAULT, this.focus.zIndexFor(null), 0.0f);
    }

    @Test
    public void theRaisedLevelIsAboveTheDefaultOne() {
        // Sounds trivial; it is the entire behaviour, and a swap would be silent.
        org.junit.Assert.assertTrue(MarkerFocus.ZINDEX_TOP > MarkerFocus.ZINDEX_DEFAULT);
    }

    @Test
    public void clearingForgetsTheSelection() {
        this.markers.draw(WALLET);
        this.focus.focus(WALLET);

        this.focus.clear();

        assertNull(this.focus.focusedBeaconId());
        assertEquals(MarkerFocus.ZINDEX_DEFAULT, this.focus.zIndexFor(WALLET), 0.0f);
    }
}
