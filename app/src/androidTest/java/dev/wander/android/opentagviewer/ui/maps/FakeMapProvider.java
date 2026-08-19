package dev.wander.android.opentagviewer.ui.maps;

import android.app.Activity;
import android.view.View;

import java.util.ArrayList;
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
     * Ready immediately, on the caller's thread.
     *
     * <p>A real provider calls back asynchronously once the map surface exists. Doing it
     * synchronously here removes a wait the test would otherwise have to guess at, and the
     * screen's own code path is identical either way - it only ever reacts to the callback.
     */
    @Override
    public void initialize(
            final Activity activity, final int containerViewId, final OnMapReadyCallback callback) {
        this.mapView = new View(activity);
        this.ready = true;

        if (callback != null) {
            callback.onMapReady(this);
        }
    }

    @Override
    public void setMapStyle(final MapStyle mapStyle) {
        this.style = mapStyle;
    }

    @Override
    public String addMarker(final MapMarker marker) {
        final String id = "marker-" + (this.nextId++);
        this.markers.put(id, new PlacedMarker(id, marker));
        return id;
    }

    @Override
    public void removeMarker(final String markerId) {
        this.markers.remove(markerId);
    }

    @Override
    public void setMarkerZIndex(final String markerId, final float zIndex) {
        // Recorded nowhere: nothing asserts stacking order, and pretending to model it would be
        // a fake with opinions of its own.
    }

    @Override
    public void clearMarkers() {
        this.markers.clear();
    }

    @Override
    public String addPolyline(final MapPolyline polyline) {
        final String id = "polyline-" + (this.nextId++);
        this.polylines.put(id, polyline);
        return id;
    }

    @Override
    public void removePolyline(final String polylineId) {
        this.polylines.remove(polylineId);
    }

    @Override
    public void clearPolylines() {
        this.polylines.clear();
    }

    @Override
    public void moveCamera(final double latitude, final double longitude, final float zoom) {
        this.cameraMoves.add(new CameraPosition(latitude, longitude, zoom));
    }

    @Override
    public void animateCamera(
            final double latitude, final double longitude, final float zoom,
            final Runnable callback) {
        this.cameraMoves.add(new CameraPosition(latitude, longitude, zoom));
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

    @Override
    public void setPadding(final int left, final int top, final int right, final int bottom) {
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
    }

    @Override
    public View getMapView() {
        return this.mapView;
    }
}
