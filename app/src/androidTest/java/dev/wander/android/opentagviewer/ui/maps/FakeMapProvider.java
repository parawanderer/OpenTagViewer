package dev.wander.android.opentagviewer.ui.maps;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A map that draws nothing and remembers everything.
 *
 * <p><b>Why this exists.</b> The instrumented suite runs on the {@code aosp-atd} managed device,
 * which has no Play Services - so a real map cannot initialise and {@code MapsActivity} had never
 * been started by any test. The map, the tag carousel, history and delete are the most-used parts
 * of the app and had no coverage at all; a change to the carousel could compile, pass and crash on
 * launch.
 *
 * <p>Rule 7 is what makes this cheap. Providers are already behind {@link IMapProvider} - a third
 * party added MapLibre in about eighty lines - so this is one more implementation rather than a
 * change to any screen.
 *
 * <p><b>It records rather than renders</b>, which is the more useful half anyway. "Is there a
 * marker for each tag, at the right place" is a better assertion than a screenshot of a map: it
 * says what the app decided, not what Google drew.
 */
public class FakeMapProvider implements IMapProvider {

    /** A marker as the screen asked for it, kept so a test can ask what was placed where. */
    public static final class PlacedMarker {
        public final String id;
        public final MapMarker marker;

        PlacedMarker(final String id, final MapMarker marker) {
            this.id = id;
            this.marker = marker;
        }
    }

    private final Map<String, PlacedMarker> markers = new LinkedHashMap<>();
    private final Map<String, MapPolyline> polylines = new LinkedHashMap<>();
    private final List<CameraPosition> cameraMoves = new ArrayList<>();

    /** Z-index per marker, as {@link #setMarkerZIndex} was told. Decides draw order. */
    private final Map<String, Float> raised = new LinkedHashMap<>();

    /** The last padding asked for - see {@link #setPadding}. Null until something asks. */
    private int[] padding;

    private View mapView;
    private MapStyle style;
    private int nextId = 0;

    /** Set once {@link #initialize} has called its callback back, as a real provider would. */
    private boolean ready = false;

    // ------------------------------------------------------------------ what a test asks

    public List<PlacedMarker> markers() {
        return new ArrayList<>(this.markers.values());
    }

    public int markerCount() {
        return this.markers.size();
    }

    public List<MapPolyline> polylines() {
        return new ArrayList<>(this.polylines.values());
    }

    public List<CameraPosition> cameraMoves() {
        return new ArrayList<>(this.cameraMoves);
    }

    public boolean isReady() {
        return this.ready;
    }

    public MapStyle style() {
        return this.style;
    }

    // ------------------------------------------------------------------ IMapProvider

    /**
     * Ready immediately, on the caller's thread, with a view that actually draws.
     *
     * <p>A real provider calls back asynchronously once the map surface exists. Doing it
     * synchronously here removes a wait the test would otherwise have to guess at, and the
     * screen's own code path is identical either way - it only ever reacts to the callback.
     *
     * <p><b>The view is added to the container, as the real providers do.</b> Google's replaces
     * that container with a {@code SupportMapFragment}; this puts a {@link FakeMapView} in it.
     * Both container ids in this app - {@code R.id.map} and {@code R.id.history_map} - are
     * {@code FrameLayout}s, so there is somewhere to put it. If there is not, nothing is added
     * and everything else here still works: recording never depended on being on screen.
     */
    @Override
    public void initialize(
            final Activity activity, final int containerViewId, final OnMapReadyCallback callback) {

        // **A map starts empty, and this one has to as well.** The real factory builds a fresh
        // provider for every screen, so nothing one screen drew can appear on the next. A test
        // hands out a single instance instead - which is what lets it be asked what was drawn -
        // and without this that instance carries its markers from screen to screen.
        //
        // It showed up as the tag pin from the map still sitting on the history screen,
        // underneath that screen's own route. Nothing asserted on it, so nothing failed; it was
        // visible only because the fake draws now. That is the same way a fake stops being
        // useful as inventing marker ids was: plausible, quiet, and not what the app does.
        this.markers.clear();
        this.polylines.clear();
        this.raised.clear();
        this.cameraMoves.clear();

        final FakeMapView drawn = new FakeMapView(activity, this);
        this.mapView = drawn;

        final View container = activity.findViewById(containerViewId);
        if (container instanceof ViewGroup) {
            ((ViewGroup) container).addView(drawn, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        this.ready = true;

        if (callback != null) {
            callback.onMapReady(this);
        }
    }

    /** Ask the drawn view to repaint, from whichever thread just changed something. */
    private void redraw() {
        final View drawn = this.mapView;
        if (drawn != null) {
            drawn.postInvalidateOnAnimation();
        }
    }

    @Override
    public void setMapStyle(final MapStyle mapStyle) {
        this.style = mapStyle;
        this.redraw();
    }

    /**
     * <b>Hands back the id it was given, as both real providers do.</b>
     *
     * <p>This used to invent {@code "marker-0"}, {@code "marker-1"} and so on, which reads like a
     * reasonable thing for a fake to do and is wrong: {@code GoogleMapProvider} and
     * {@code AMapProvider} both {@code return marker.getId()} and key their own maps by it, and
     * {@code MapsActivity} relies on that - it calls {@code removeMarker(beaconId)} to replace a
     * tag's pin.
     *
     * <p>So against the fake that removal matched nothing and markers quietly accumulated. Any
     * test of redrawing would have measured the fake's divergence rather than the app, which is
     * the specific way a fake stops being useful.
     */
    @Override
    public String addMarker(final MapMarker marker) {
        final String id = marker.getId() != null
                ? marker.getId()
                : "unidentified-" + (this.nextId++);

        this.markers.put(id, new PlacedMarker(id, marker));
        this.redraw();
        return id;
    }

    @Override
    public void removeMarker(final String markerId) {
        this.markers.remove(markerId);
        this.raised.remove(markerId);
        this.redraw();
    }

    /**
     * <b>Recorded, now that something draws.</b>
     *
     * <p>This used to do nothing, on the reasoning that nothing asserted stacking order and a
     * fake with opinions is worse than a fake without. That held while the view was blank. It
     * does not now: tags in one building overlap completely at anything but the closest zoom,
     * which is exactly why the screen raises the selected one, and a fake that ignored it would
     * draw the selected pin underneath whichever tag happened to be added last - and the
     * screenshot would show the app failing to do the thing it does.
     */
    @Override
    public void setMarkerZIndex(final String markerId, final float zIndex) {
        this.raised.put(markerId, zIndex);
        this.redraw();
    }

    @Override
    public void clearMarkers() {
        this.markers.clear();
        this.raised.clear();
        this.redraw();
    }

    @Override
    public String addPolyline(final MapPolyline polyline) {
        final String id = "polyline-" + (this.nextId++);
        this.polylines.put(id, polyline);
        this.redraw();
        return id;
    }

    @Override
    public void removePolyline(final String polylineId) {
        this.polylines.remove(polylineId);
        this.redraw();
    }

    @Override
    public void clearPolylines() {
        this.polylines.clear();
        this.redraw();
    }

    @Override
    public void moveCamera(final double latitude, final double longitude, final float zoom) {
        this.cameraMoves.add(new CameraPosition(latitude, longitude, zoom));
        this.redraw();
    }

    @Override
    public void animateCamera(
            final double latitude, final double longitude, final float zoom,
            final Runnable callback) {
        this.cameraMoves.add(new CameraPosition(latitude, longitude, zoom));
        this.redraw();
        if (callback != null) {
            callback.run();
        }
    }

    @Override
    public void setOnMapClickListener(final OnMapClickListener listener) {
    }

    @Override
    public void setOnMarkerClickListener(final OnMarkerClickListener listener) {
    }

    /**
     * <b>Recorded, because it is how the map keeps its content out from under the sheet.</b>
     *
     * <p>This used to be ignored. On a real map, padding shrinks the region the camera centres
     * within - so the history screen tracks the bottom sheet with it, and a route or a marker
     * is framed in the strip that is still visible rather than behind the list. Drop the
     * padding and everything still draws; it just draws underneath the sheet, where the user
     * cannot see it, and nothing anywhere reports a problem.
     */
    @Override
    public void setPadding(final int left, final int top, final int right, final int bottom) {
        this.padding = new int[] {left, top, right, bottom};
    }

    /** The last padding the screen asked for, as {left, top, right, bottom}. */
    public int[] padding() {
        return this.padding == null ? null : this.padding.clone();
    }

    @Override
    public CameraPosition getCameraPosition() {
        return this.cameraMoves.isEmpty()
                ? new CameraPosition(0, 0, 0)
                : this.cameraMoves.get(this.cameraMoves.size() - 1);
    }

    @Override
    public void setMyLocationButtonEnabled(final boolean enabled) {
    }

    @Override
    public void setRotateGesturesEnabled(final boolean enabled) {
    }

    @Override
    public void setCompassEnabled(final boolean enabled) {
    }

    @Override
    public void setMapToolbarEnabled(final boolean enabled) {
    }

    @Override
    public void clear() {
        this.markers.clear();
        this.polylines.clear();
        this.raised.clear();
        this.redraw();
    }

    @Override
    public View getMapView() {
        return this.mapView;
    }

    // ------------------------------------------------------------------ what the eye sees

    /** Markers in the order they should be painted: lowest z-index first, ties in add order. */
    List<PlacedMarker> inDrawOrder() {
        final List<PlacedMarker> ordered = new ArrayList<>(this.markers.values());
        ordered.sort(Comparator.comparingDouble(
                placed -> this.raised.getOrDefault(placed.id, placed.marker.getZIndex())));
        return ordered;
    }

    /** Where the camera is, or a default nobody set - the same answer as an untouched map. */
    CameraPosition whereTheCameraIs() {
        return this.getCameraPosition();
    }

    List<MapPolyline> linesToDraw() {
        return new ArrayList<>(this.polylines.values());
    }
}
