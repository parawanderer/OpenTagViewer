package dev.wander.android.opentagviewer.ui.maps;

import android.app.Activity;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentManager;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.wander.android.opentagviewer.R;

/**
 * Google Maps地图提供商实现
 */
public class GoogleMapProvider implements IMapProvider, OnMapReadyCallback {
    private static final String TAG = GoogleMapProvider.class.getSimpleName();
    
    private Activity activity;
    private GoogleMap googleMap;
    private SupportMapFragment mapFragment;
    private OnMapReadyCallback callback;
    
    // 存储标记点和路径线
    private final Map<String, Marker> markers = new HashMap<>();
    private final Map<String, Polyline> polylines = new HashMap<>();
    
    // 监听器
    private OnMapClickListener onMapClickListener;
    private OnMarkerClickListener onMarkerClickListener;
    
    @Override
    public void initialize(Activity activity, int containerViewId, OnMapReadyCallback callback) {
        this.activity = activity;
        this.callback = callback;
        
        if (!(activity instanceof androidx.fragment.app.FragmentActivity)) {
            throw new IllegalArgumentException("Activity must be a FragmentActivity to use Google Maps");
        }
        
        FragmentManager fragmentManager = ((androidx.fragment.app.FragmentActivity) activity).getSupportFragmentManager();
        
        // 查找或创建SupportMapFragment
        mapFragment = (SupportMapFragment) fragmentManager.findFragmentById(containerViewId);
        if (mapFragment == null) {
            mapFragment = SupportMapFragment.newInstance();
            fragmentManager.beginTransaction()
                    .replace(containerViewId, mapFragment)
                    .commit();
        }
        
        mapFragment.getMapAsync(this);
    }
    
    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        this.googleMap = googleMap;
        
        // 设置默认UI设置
        googleMap.getUiSettings().setMyLocationButtonEnabled(false);
        googleMap.getUiSettings().setRotateGesturesEnabled(false);
        googleMap.getUiSettings().setCompassEnabled(false);
        googleMap.getUiSettings().setMapToolbarEnabled(false);
        
        // 设置监听器
        if (onMapClickListener != null) {
            googleMap.setOnMapClickListener(point -> {
                if (onMapClickListener != null) {
                    onMapClickListener.onMapClick(point.latitude, point.longitude);
                }
            });
        }
        
        if (onMarkerClickListener != null) {
            googleMap.setOnMarkerClickListener(marker -> {
                if (onMarkerClickListener != null) {
                    // 查找标记点ID
                    String markerId = findMarkerId(marker);
                    return onMarkerClickListener.onMarkerClick(markerId);
                }
                return false;
            });
        }
        
        // 通知回调
        if (callback != null) {
            callback.onMapReady(this);
        }
    }
    
    private String findMarkerId(Marker marker) {
        for (Map.Entry<String, Marker> entry : markers.entrySet()) {
            if (entry.getValue().equals(marker)) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    @Override
    public void setMapStyle(boolean darkMode) {
        if (googleMap == null) return;
        
        if (darkMode) {
            MapStyleOptions style = MapStyleOptions.loadRawResourceStyle(
                    activity.getApplicationContext(),
                    R.raw.map_dark_style
            );
            googleMap.setMapStyle(style);
        } else {
            googleMap.setMapStyle(null);
        }
    }
    
    @Override
    public String addMarker(MapMarker marker) {
        if (googleMap == null) {
            Log.w(TAG, "GoogleMap is not ready yet, cannot add marker");
            return marker.getId();
        }
        
        LatLng position = new LatLng(marker.getLatitude(), marker.getLongitude());
        MarkerOptions options = new MarkerOptions()
                .position(position)
                .title(marker.getTitle())
                .snippet(marker.getSnippet())
                .draggable(marker.isDraggable())
                .visible(marker.isVisible())
                .alpha(marker.getAlpha());
        
        // 设置图标
        if (marker.getIconBitmap() != null) {
            try {
                options.icon(BitmapDescriptorFactory.fromBitmap(marker.getIconBitmap()));
            } catch (Exception e) {
                Log.e(TAG, "Failed to create icon from bitmap, falling back to default", e);
                options.icon(BitmapDescriptorFactory.defaultMarker());
            }
        } else if (marker.getIconResourceId() != 0) {
            options.icon(BitmapDescriptorFactory.fromResource(marker.getIconResourceId()));
        } else if (!marker.isUseDefaultIcon()) {
            // 如果没有指定图标且不使用默认图标，使用默认图标
            options.icon(BitmapDescriptorFactory.defaultMarker());
        }
        
        Marker googleMarker = googleMap.addMarker(options);
        markers.put(marker.getId(), googleMarker);
        
        return marker.getId();
    }
    
    @Override
    public void removeMarker(String markerId) {
        Marker marker = markers.remove(markerId);
        if (marker != null) {
            marker.remove();
        }
    }
    
    @Override
    public void clearMarkers() {
        for (Marker marker : markers.values()) {
            marker.remove();
        }
        markers.clear();
    }
    
    @Override
    public String addPolyline(MapPolyline polyline) {
        if (googleMap == null) {
            Log.w(TAG, "GoogleMap is not ready yet, cannot add polyline");
            return polyline.getId();
        }
        
        List<LatLng> points = polyline.getPoints().stream()
                .map(p -> new LatLng(p.getLatitude(), p.getLongitude()))
                .collect(Collectors.toList());
        
        PolylineOptions options = new PolylineOptions()
                .addAll(points)
                .color(polyline.getColor())
                .width(polyline.getWidth())
                .zIndex(polyline.getZIndex())
                .geodesic(polyline.isGeodesic())
                .visible(polyline.isVisible())
                .clickable(false);
        
        // 设置透明度
        int alpha = (int) (polyline.getAlpha() * 255);
        int colorWithAlpha = (alpha << 24) | (polyline.getColor() & 0x00FFFFFF);
        options.color(colorWithAlpha);
        
        Polyline googlePolyline = googleMap.addPolyline(options);
        polylines.put(polyline.getId(), googlePolyline);
        
        return polyline.getId();
    }
    
    @Override
    public void removePolyline(String polylineId) {
        Polyline polyline = polylines.remove(polylineId);
        if (polyline != null) {
            polyline.remove();
        }
    }
    
    @Override
    public void clearPolylines() {
        for (Polyline polyline : polylines.values()) {
            polyline.remove();
        }
        polylines.clear();
    }
    
    @Override
    public void moveCamera(double latitude, double longitude, float zoom) {
        if (googleMap == null) return;
        LatLng position = new LatLng(latitude, longitude);
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(position, zoom));
    }
    
    @Override
    public void animateCamera(double latitude, double longitude, float zoom, Runnable callback) {
        if (googleMap == null) return;
        LatLng position = new LatLng(latitude, longitude);
        
        if (callback != null) {
            googleMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(position, zoom),
                    new GoogleMap.CancelableCallback() {
                        @Override
                        public void onFinish() {
                            callback.run();
                        }
                        
                        @Override
                        public void onCancel() {
                            callback.run();
                        }
                    }
            );
        } else {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, zoom));
        }
    }
    
    @Override
    public void setOnMapClickListener(OnMapClickListener listener) {
        this.onMapClickListener = listener;
        if (googleMap != null && listener != null) {
            googleMap.setOnMapClickListener(point -> {
                listener.onMapClick(point.latitude, point.longitude);
            });
        }
    }
    
    @Override
    public void setOnMarkerClickListener(OnMarkerClickListener listener) {
        this.onMarkerClickListener = listener;
        if (googleMap != null && listener != null) {
            googleMap.setOnMarkerClickListener(marker -> {
                String markerId = findMarkerId(marker);
                return listener.onMarkerClick(markerId);
            });
        }
    }
    
    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        if (googleMap == null) return;
        googleMap.setPadding(left, top, right, bottom);
    }
    
    @Override
    public CameraPosition getCameraPosition() {
        if (googleMap == null) return null;
        com.google.android.gms.maps.model.CameraPosition position = googleMap.getCameraPosition();
        return new CameraPosition(
                position.target.latitude,
                position.target.longitude,
                position.zoom
        );
    }
    
    @Override
    public void setMyLocationButtonEnabled(boolean enabled) {
        if (googleMap == null) return;
        googleMap.getUiSettings().setMyLocationButtonEnabled(enabled);
    }
    
    @Override
    public void setRotateGesturesEnabled(boolean enabled) {
        if (googleMap == null) return;
        googleMap.getUiSettings().setRotateGesturesEnabled(enabled);
    }
    
    @Override
    public void setCompassEnabled(boolean enabled) {
        if (googleMap == null) return;
        googleMap.getUiSettings().setCompassEnabled(enabled);
    }
    
    @Override
    public void setMapToolbarEnabled(boolean enabled) {
        if (googleMap == null) return;
        googleMap.getUiSettings().setMapToolbarEnabled(enabled);
    }
    
    @Override
    public void clear() {
        clearMarkers();
        clearPolylines();
    }
    
    @Override
    public View getMapView() {
        return mapFragment != null ? mapFragment.getView() : null;
    }
    
    /**
     * 获取底层的GoogleMap对象（用于特殊操作）
     */
    public GoogleMap getGoogleMap() {
        return googleMap;
    }
    
    /**
     * 动画移动到边界框
     */
    public void animateCameraToBounds(List<MapPolyline.LatLng> points, int padding) {
        if (googleMap == null || points == null || points.size() < 2) return;
        
        LatLngBounds.Builder builder = new LatLngBounds.Builder();
        for (MapPolyline.LatLng point : points) {
            builder.include(new LatLng(point.getLatitude(), point.getLongitude()));
        }
        LatLngBounds bounds = builder.build();
        googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding));
    }
}

