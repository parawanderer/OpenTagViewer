package dev.wander.android.opentagviewer.util.rx;

import androidx.annotation.Nullable;

/**
 * Keeps the selected tag's marker drawn above the others.
 * <br>
 * Tags kept together - a wallet, keys and a bag by the front door - resolve to positions metres
 * apart, which at anything but the closest zoom is a single pile of markers. The map draws
 * whichever it likes on top, so without raising the selected one, tapping a card moves the
 * camera to a marker that stays hidden underneath another and the tap looks ignored.
 * <br>
 * This existed before the map provider abstraction, as {@code Marker.setZIndex} on the Google
 * marker directly, and was dropped when markers moved behind {@code IMapProvider} -
 * {@code MapPolyline} kept its zIndex, {@code MapMarker} never gained one. It went unnoticed
 * because nothing failed: the constants and the field it used stayed behind as unreferenced
 * fossils, so the code still looked present.
 * <br>
 * Free of Android types so the ordering can be tested. Called from the main thread only.
 */
public final class MarkerFocus {

    public static final float ZINDEX_DEFAULT = 0.0f;
    public static final float ZINDEX_TOP = 10.0f;

    /** The map, reduced to what focusing needs. */
    public interface Markers {
        /** @return the marker id currently drawn for this beacon, or null if there is none */
        @Nullable
        String markerIdFor(String beaconId);

        void setZIndex(String markerId, float zIndex);
    }

    private final Markers markers;
    private String focusedBeaconId = null;

    public MarkerFocus(final Markers markers) {
        this.markers = markers;
    }

    /**
     * Raises this beacon's marker and lowers whichever was raised before.
     * <br>
     * Does nothing at all if the beacon has no marker yet, which happens when a card is
     * selected during the first draw. Lowering the previous one first would leave the pile
     * with nothing raised.
     */
    public void focus(final String beaconId) {
        if (beaconId == null || beaconId.equals(this.focusedBeaconId)) {
            return;
        }

        final String markerId = this.markers.markerIdFor(beaconId);
        if (markerId == null) {
            return;
        }

        if (this.focusedBeaconId != null) {
            final String previousMarkerId = this.markers.markerIdFor(this.focusedBeaconId);
            if (previousMarkerId != null) {
                this.markers.setZIndex(previousMarkerId, ZINDEX_DEFAULT);
            }
        }

        this.markers.setZIndex(markerId, ZINDEX_TOP);
        this.focusedBeaconId = beaconId;
    }

    /**
     * The draw order a freshly built marker should carry.
     * <br>
     * Every refresh removes and re-adds the markers, so the focused one has to be rebuilt
     * raised. Raising it only on selection would let the next refresh drop it back under the
     * pile without the user touching anything.
     */
    public float zIndexFor(final String beaconId) {
        return beaconId != null && beaconId.equals(this.focusedBeaconId) ? ZINDEX_TOP : ZINDEX_DEFAULT;
    }

    @Nullable
    public String focusedBeaconId() {
        return this.focusedBeaconId;
    }

    /** Forgets the focus, for when the markers are cleared out from under it. */
    public void clear() {
        this.focusedBeaconId = null;
    }
}
