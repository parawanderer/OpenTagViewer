package dev.wander.android.opentagviewer.ui.maps;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.drawable.BitmapDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.osmdroid.api.IMapController;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.compass.CompassOverlay;
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider;
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

/**
 * OpenStreetMap provider, via osmdroid.
 *
 * <p>No API key and no account with anyone -- the reason this exists at all is that the other
 * two providers both need one. Unlike {@link AMapProvider}, the SDK is a normal compile-time
 * dependency: osmdroid carries no licensing reason to load it reflectively, so this class calls
 * it directly.
 *
 * <p><b>Coordinates are WGS84 throughout, with no conversion step.</b> {@link AMapProvider}
 * converts every point through {@link CoordinateConverter} because AMap's tiles are drawn on
 * China's GCJ02 offset grid; OSM's are not offset at all, so a point handed to this class is a
 * point drawn on the map, unchanged.
 *
 * <p><b>Always Mapnik, light, regardless of theme.</b> CartoDB's Dark Matter basemap was tried
 * here first as a real dark style, but it now demands an API key on the free tier it was chosen
 * to avoid needing in the first place - defeating the reason to reach for OSM at all. A
 * ColorMatrix filter over Mapnik is the usual workaround for a dark OSM map and was considered,
 * but was dropped in favour of staying with the plain tiles rather than shipping an
 * approximation of a dark map. {@link #setMapStyle} therefore accepts every {@link MapStyle}
 * value but always shows the same tiles.
 */
public class OsmMapProvider implements IMapProvider {
    private static final String TAG = OsmMapProvider.class.getSimpleName();

    private Activity activity;
    private MapView mapView;
    private IMapController controller;

    private final Map<String, Marker> markers = new HashMap<>();
    private final Map<String, Float> markerZIndices = new HashMap<>();
    private final Map<String, Polyline> polylines = new HashMap<>();

    private OnMapClickListener onMapClickListener;
    private OnMarkerClickListener onMarkerClickListener;

    private MapStyle currentMapStyle = MapStyle.FOLLOW_SYSTEM;
    private CopyrightOverlay copyrightOverlay;
    private MyLocationNewOverlay myLocationOverlay;
    private RotationGestureOverlay rotationGestureOverlay;
    private CompassOverlay compassOverlay;

    @Override
    public void initialize(Activity activity, int containerViewId, OnMapReadyCallback callback) {
        this.activity = activity;

        // Required by OSM's tile usage policy (https://operations.osmfoundation.org/policies/tiles/):
        // a User-Agent that identifies the app, and a place on disk for the tile cache rather
        // than re-fetching what was already drawn a moment ago.
        org.osmdroid.config.Configuration.getInstance().setUserAgentValue(activity.getPackageName());
        File cacheDir = new File(activity.getCacheDir(), "osmdroid");
        org.osmdroid.config.Configuration.getInstance().setOsmdroidBasePath(cacheDir);
        org.osmdroid.config.Configuration.getInstance().setOsmdroidTileCache(new File(cacheDir, "tiles"));

        this.mapView = new MapView(activity);
        this.mapView.setTileSource(TileSourceFactory.MAPNIK);
        this.mapView.setMultiTouchControls(true);
        this.controller = this.mapView.getController();

        this.copyrightOverlay = new CopyrightOverlay(activity);
        this.mapView.getOverlays().add(this.copyrightOverlay);

        MapEventsReceiver receiver = new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                if (OsmMapProvider.this.onMapClickListener != null) {
                    OsmMapProvider.this.onMapClickListener.onMapClick(p.getLatitude(), p.getLongitude());
                    return true;
                }
                return false;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return false;
            }
        };
        // Added first (bottom of the draw order) so a tap only reaches it once every marker
        // added afterwards has had a chance to consume that tap instead - osmdroid dispatches
        // touch events from the topmost overlay down.
        this.mapView.getOverlays().add(0, new MapEventsOverlay(receiver));

        ViewGroup container = activity.findViewById(containerViewId);
        if (container != null) {
            ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            container.addView(this.mapView, layoutParams);
        } else {
            Log.e(TAG, "Container view not found for id: " + containerViewId);
        }

        this.setMapStyle(this.currentMapStyle);

        if (callback != null) {
            callback.onMapReady(this);
        }
    }

    @Override
    public void setMapStyle(MapStyle mapStyle) {
        // Every value is accepted - see the class doc for why this never switches tile source.
        this.currentMapStyle = mapStyle == null ? MapStyle.FOLLOW_SYSTEM : mapStyle;
        if (this.mapView == null) return;

        this.mapView.setTileSource(TileSourceFactory.MAPNIK);
        if (this.copyrightOverlay != null) {
            this.copyrightOverlay.setCopyrightNotice("© OpenStreetMap contributors");
        }
        this.mapView.invalidate();
    }

    @Override
    public String addMarker(MapMarker marker) {
        if (this.mapView == null) {
            Log.w(TAG, "Map is not ready yet, cannot add marker");
            return marker.getId();
        }

        Marker osmMarker = new Marker(this.mapView);
        osmMarker.setPosition(new GeoPoint(marker.getLatitude(), marker.getLongitude()));
        osmMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        if (marker.getTitle() != null) {
            osmMarker.setTitle(marker.getTitle());
        }
        if (marker.getSnippet() != null) {
            osmMarker.setSnippet(marker.getSnippet());
        }
        if (marker.getIconBitmap() != null) {
            osmMarker.setIcon(new BitmapDrawable(this.activity.getResources(), marker.getIconBitmap()));
        }
        osmMarker.setAlpha(marker.getAlpha());

        final String markerId = marker.getId();
        osmMarker.setOnMarkerClickListener((clickedMarker, mapView) -> {
            if (this.onMarkerClickListener != null) {
                return this.onMarkerClickListener.onMarkerClick(markerId);
            }
            return false;
        });

        this.markers.put(markerId, osmMarker);
        this.markerZIndices.put(markerId, marker.getZIndex());
        this.mapView.getOverlays().add(osmMarker);
        this.reorderMarkersByZIndex();

        return markerId;
    }

    @Override
    public void removeMarker(String markerId) {
        Marker marker = this.markers.remove(markerId);
        this.markerZIndices.remove(markerId);
        if (marker != null && this.mapView != null) {
            this.mapView.getOverlays().remove(marker);
            this.mapView.invalidate();
        }
    }

    @Override
    public void setMarkerZIndex(String markerId, float zIndex) {
        if (!this.markers.containsKey(markerId)) {
            Log.d(TAG, "No marker to re-order for markerId=" + markerId);
            return;
        }
        this.markerZIndices.put(markerId, zIndex);
        this.reorderMarkersByZIndex();
    }

    /**
     * osmdroid draws overlays in list order with no separate z-index concept, so "raising" a
     * marker means re-inserting every marker into the overlay list sorted by the z-index this
     * class tracks on its behalf. Cheap enough at the scale one account's tags reach.
     */
    private void reorderMarkersByZIndex() {
        if (this.mapView == null) return;

        List<Marker> ordered = this.markers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey((a, b) -> {
                    float za = this.markerZIndices.getOrDefault(a, 0f);
                    float zb = this.markerZIndices.getOrDefault(b, 0f);
                    return Float.compare(za, zb);
                }))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());

        for (Marker m : ordered) {
            this.mapView.getOverlays().remove(m);
        }
        this.mapView.getOverlays().addAll(ordered);
        this.mapView.invalidate();
    }

    @Override
    public void clearMarkers() {
        for (Marker marker : this.markers.values()) {
            this.mapView.getOverlays().remove(marker);
        }
        this.markers.clear();
        this.markerZIndices.clear();
        this.mapView.invalidate();
    }

    @Override
    public String addPolyline(MapPolyline polyline) {
        if (this.mapView == null) {
            Log.w(TAG, "Map is not ready yet, cannot add polyline");
            return polyline.getId();
        }

        Polyline osmPolyline = new Polyline(this.mapView);
        List<GeoPoint> points = polyline.getPoints().stream()
                .map(p -> new GeoPoint(p.getLatitude(), p.getLongitude()))
                .collect(Collectors.toList());
        osmPolyline.setPoints(points);
        osmPolyline.getOutlinePaint().setColor(polyline.getColor());
        osmPolyline.getOutlinePaint().setStrokeWidth(polyline.getWidth());
        osmPolyline.getOutlinePaint().setAlpha(Math.round(polyline.getAlpha() * 255));
        if (polyline.isDotted()) {
            float[] pattern = polyline.getPattern() != null ? polyline.getPattern() : new float[]{10f, 10f};
            osmPolyline.getOutlinePaint().setPathEffect(new DashPathEffect(pattern, 0));
        }
        // Not implemented: `geodesic` draws a great-circle path rather than a straight one
        // between points. osmdroid's Polyline does not do this itself, and the segments this
        // app draws (location history between consecutive fixes) are short enough that the two
        // are visually identical - so this is a documented gap rather than a silent one.

        this.polylines.put(polyline.getId(), osmPolyline);
        this.mapView.getOverlays().add(osmPolyline);
        this.mapView.invalidate();

        return polyline.getId();
    }

    @Override
    public void removePolyline(String polylineId) {
        Polyline polyline = this.polylines.remove(polylineId);
        if (polyline != null && this.mapView != null) {
            this.mapView.getOverlays().remove(polyline);
            this.mapView.invalidate();
        }
    }

    @Override
    public void clearPolylines() {
        for (Polyline polyline : this.polylines.values()) {
            this.mapView.getOverlays().remove(polyline);
        }
        this.polylines.clear();
        this.mapView.invalidate();
    }

    @Override
    public void moveCamera(double latitude, double longitude, float zoom) {
        if (this.controller == null) return;
        this.controller.setCenter(new GeoPoint(latitude, longitude));
        this.controller.setZoom((double) zoom);
    }

    @Override
    public void animateCamera(double latitude, double longitude, float zoom, Runnable callback) {
        if (this.controller == null) return;
        // Position and zoom animated together in one call - a prior version set zoom instantly
        // then animated position alone, which left the zoom looking like it had not moved: the
        // jump to the target zoom happened before the pan animation gave the eye anything to
        // track it against.
        //
        // osmdroid's animateTo has no reliable "animation finished" callback across its public
        // API (unlike Google's AMap.CancelableCallback), so the caller's callback runs once the
        // animation has had time to finish rather than on a true completion event. 300ms is
        // osmdroid's own default MapView animation speed.
        this.controller.animateTo(new GeoPoint(latitude, longitude), (double) zoom, 300L);
        if (callback != null) {
            this.mapView.postDelayed(callback, 300);
        }
    }

    @Override
    public void setOnMapClickListener(OnMapClickListener listener) {
        this.onMapClickListener = listener;
    }

    @Override
    public void setOnMarkerClickListener(OnMarkerClickListener listener) {
        this.onMarkerClickListener = listener;
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        // No equivalent in this osmdroid version: MapView has no padding concept the way
        // Google's GoogleMap.setPadding shifts where the logo/controls and the effective center
        // sit. Matches AMapProvider's own no-op for the same call.
        Log.d(TAG, "setPadding has no osmdroid equivalent; ignoring: " + left + ", " + top + ", " + right + ", " + bottom);
    }

    @Override
    public CameraPosition getCameraPosition() {
        if (this.mapView == null) return null;
        GeoPoint center = (GeoPoint) this.mapView.getMapCenter();
        return new CameraPosition(center.getLatitude(), center.getLongitude(),
                (float) this.mapView.getZoomLevelDouble());
    }

    @Override
    public void setMyLocationButtonEnabled(boolean enabled) {
        // osmdroid has no built-in button of its own the way Google/AMap do; this toggles the
        // location dot/overlay itself, which is the closest equivalent this library offers.
        if (this.mapView == null) return;
        if (enabled) {
            if (this.myLocationOverlay == null) {
                this.myLocationOverlay = new MyLocationNewOverlay(
                        new GpsMyLocationProvider(this.activity), this.mapView);
            }
            this.myLocationOverlay.enableMyLocation();
            if (!this.mapView.getOverlays().contains(this.myLocationOverlay)) {
                this.mapView.getOverlays().add(this.myLocationOverlay);
            }
        } else if (this.myLocationOverlay != null) {
            this.myLocationOverlay.disableMyLocation();
            this.mapView.getOverlays().remove(this.myLocationOverlay);
        }
        this.mapView.invalidate();
    }

    @Override
    public void setRotateGesturesEnabled(boolean enabled) {
        if (this.mapView == null) return;
        if (enabled) {
            if (this.rotationGestureOverlay == null) {
                this.rotationGestureOverlay = new RotationGestureOverlay(this.mapView);
                this.rotationGestureOverlay.setEnabled(true);
            }
            if (!this.mapView.getOverlays().contains(this.rotationGestureOverlay)) {
                this.mapView.getOverlays().add(this.rotationGestureOverlay);
            }
        } else if (this.rotationGestureOverlay != null) {
            this.mapView.getOverlays().remove(this.rotationGestureOverlay);
        }
    }

    @Override
    public void setCompassEnabled(boolean enabled) {
        if (this.mapView == null) return;
        if (enabled) {
            if (this.compassOverlay == null) {
                this.compassOverlay = new CompassOverlay(
                        this.activity, new InternalCompassOrientationProvider(this.activity), this.mapView);
            }
            this.compassOverlay.enableCompass();
            if (!this.mapView.getOverlays().contains(this.compassOverlay)) {
                this.mapView.getOverlays().add(this.compassOverlay);
            }
        } else if (this.compassOverlay != null) {
            this.compassOverlay.disableCompass();
            this.mapView.getOverlays().remove(this.compassOverlay);
        }
    }

    @Override
    public void setMapToolbarEnabled(boolean enabled) {
        // No equivalent in osmdroid: Google's map toolbar (directions/open-in-Maps shortcuts) is
        // a Play Services feature with nothing OSM-side to toggle.
        Log.d(TAG, "setMapToolbarEnabled has no osmdroid equivalent; ignoring: " + enabled);
    }

    @Override
    public void clear() {
        clearMarkers();
        clearPolylines();
    }

    @Override
    public View getMapView() {
        return this.mapView;
    }

    /** Called from the activity's onResume - osmdroid needs this to resume tile loading. */
    /**
     * Two levels closer in than Google's default.
     *
     * <p>osmdroid indexes the same standard tile pyramid, but the tiles it draws carry less
     * label and building detail per level, so the reference level 16 opens on a view that reads
     * as further out than the same number does on Google's. This is a judgement about what the
     * user sees rather than a correction to the number.
     */
    @Override
    public float initialZoom() {
        return 18.0f;
    }

    @Override
    public void onResume() {
        if (this.mapView != null) {
            this.mapView.onResume();
        }
    }

    /** Called from the activity's onPause - osmdroid needs this to release tile resources. */
    @Override
    public void onPause() {
        if (this.mapView != null) {
            this.mapView.onPause();
        }
    }

    /** Called from the activity's onDestroy. */
    @Override
    public void onDestroy() {
        if (this.myLocationOverlay != null) {
            this.myLocationOverlay.disableMyLocation();
        }
        if (this.compassOverlay != null) {
            this.compassOverlay.disableCompass();
        }
        if (this.mapView != null) {
            this.mapView.onDetach();
        }
    }
}
