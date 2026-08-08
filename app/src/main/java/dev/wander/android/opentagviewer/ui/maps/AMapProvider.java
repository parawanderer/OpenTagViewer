package dev.wander.android.opentagviewer.ui.maps;

import android.app.Activity;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 高德地图提供商实现
 * 
 * 高德地图Android SDK V9.8.3 (3D地图)
 * 发布日期：2023-12-06
 * 
 * SDK依赖配置：
 * dependencies {
 *     implementation 'com.amap.api:3dmap:9.8.3'
 *     // 注意：3D地图SDK已包含定位功能，无需单独引入location SDK
 * }
 * 
 * API Key配置：
 * 在AndroidManifest.xml中添加：
 * <meta-data
 *     android:name="com.amap.api.v2.apikey"
 *     android:value="YOUR_AMAP_API_KEY" />
 * 
 * 隐私合规：
 * 必须在Application的onCreate中调用隐私合规接口：
 * MapsInitializer.updatePrivacyShow(context, true, true);
 * MapsInitializer.updatePrivacyAgree(context, true);
 * 
 * 参考文档：
 * - 隐私合规：https://lbs.amap.com/api/android-sdk/guide/create-map/dev-attention
 * - SDK初始化：https://lbs.amap.com/api/android-sdk/guide/create-project/android-studio-create-project
 * - 入门指南：https://lbs.amap.com/api/android-sdk/gettingstarted
 */
public class AMapProvider implements IMapProvider {
    private static final String TAG = AMapProvider.class.getSimpleName();
    
    private Activity activity;
    private OnMapReadyCallback callback;
    
    // 存储标记点和路径线
    private final Map<String, Object> markers = new HashMap<>(); // 存储AMap.Marker对象
    private final Map<String, Object> polylines = new HashMap<>(); // 存储Polyline对象
    
    // 监听器
    private OnMapClickListener onMapClickListener;
    private OnMarkerClickListener onMarkerClickListener;
    
    // 高德地图对象
    // 使用3D地图SDK：com.amap.api.maps.AMap
    private Object aMap; // com.amap.api.maps.AMap
    private View mapView; // com.amap.api.maps.TextureMapView
    
    private boolean isDarkMode = false;
    
    @Override
    public void initialize(Activity activity, int containerViewId, OnMapReadyCallback callback) {
        this.activity = activity;
        this.callback = callback;
        
        try {
            // 使用反射加载高德地图3D SDK，避免编译时依赖
            // 高德地图3D地图使用 TextureMapView 或 MapView
            // TextureMapView 性能更好，推荐使用
            Class<?> mapViewClass = Class.forName("com.amap.api.maps.TextureMapView");
            Object mapView = mapViewClass.getConstructor(android.content.Context.class)
                    .newInstance(activity);
            
            this.mapView = (View) mapView;
            
            // 获取AMap对象
            // com.amap.api.maps.AMap
            Class<?> aMapClass = Class.forName("com.amap.api.maps.AMap");
            java.lang.reflect.Method getMapMethod = mapViewClass.getMethod("getMap");
            this.aMap = getMapMethod.invoke(mapView);
            
            // 调用 onCreate 生命周期方法（高德地图必需）
            // TextureMapView.onCreate(Bundle savedInstanceState)
            java.lang.reflect.Method onCreateMethod = mapViewClass.getMethod("onCreate", android.os.Bundle.class);
            onCreateMethod.invoke(mapView, (android.os.Bundle) null);
            Log.d(TAG, "AMap TextureMapView onCreate() called");
            
            // 添加到容器，并设置 LayoutParams 确保 View 填充整个容器
            android.view.ViewGroup container = activity.findViewById(containerViewId);
            if (container != null) {
                android.view.ViewGroup.LayoutParams layoutParams = new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                );
                container.addView((View) mapView, layoutParams);
                Log.d(TAG, "AMap view added to container with MATCH_PARENT layout params");
            } else {
                Log.e(TAG, "Container view not found for id: " + containerViewId);
            }
            
            // 设置地图准备就绪回调
            if (this.callback != null) {
                this.callback.onMapReady(this);
                Log.d(TAG, "AMap onMapReady callback triggered");
            }
            
            Log.i(TAG, "AMap initialization completed successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AMap", e);
            throw new RuntimeException("Failed to initialize AMap SDK. Please ensure AMap SDK is properly configured.", e);
        }
    }
    
    @Override
    public void setMapStyle(boolean darkMode) {
        this.isDarkMode = darkMode;
        if (aMap == null) return;
        
        try {
            // 高德地图设置地图类型和样式
            // com.amap.api.maps.AMap.MAP_TYPE_NORMAL = 1 (白天模式)
            // com.amap.api.maps.AMap.MAP_TYPE_NIGHT = 4 (夜间模式)
            Class<?> aMapClass = Class.forName("com.amap.api.maps.AMap");
            java.lang.reflect.Method setMapTypeMethod = aMapClass.getMethod("setMapType", int.class);
            
            // 设置地图类型：夜间模式或普通模式
            int mapType = darkMode ? 4 : 1; // MAP_TYPE_NIGHT : MAP_TYPE_NORMAL
            setMapTypeMethod.invoke(aMap, mapType);
            
            Log.d(TAG, "Map style set to dark mode: " + darkMode);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set map style", e);
        }
    }
    
    @Override
    public String addMarker(MapMarker marker) {
        if (aMap == null) {
            Log.w(TAG, "AMap is not ready yet, cannot add marker");
            return marker.getId();
        }
        
        try {
            // 坐标转换：WGS84 -> GCJ02
            double[] gcj02 = CoordinateConverter.wgs84ToGcj02(marker.getLatitude(), marker.getLongitude());
            
            // 创建MarkerOptions
            // com.amap.api.maps.model.MarkerOptions
            Class<?> markerOptionsClass = Class.forName("com.amap.api.maps.model.MarkerOptions");
            Object markerOptions = markerOptionsClass.newInstance();
            
            // 设置位置
            // com.amap.api.maps.model.LatLng
            Class<?> latLngClass = Class.forName("com.amap.api.maps.model.LatLng");
            Object latLng = latLngClass.getConstructor(double.class, double.class)
                    .newInstance(gcj02[0], gcj02[1]);
            
            java.lang.reflect.Method positionMethod = markerOptionsClass.getMethod("position", latLngClass);
            positionMethod.invoke(markerOptions, latLng);
            
            // 设置标题和片段
            if (marker.getTitle() != null) {
                java.lang.reflect.Method titleMethod = markerOptionsClass.getMethod("title", String.class);
                titleMethod.invoke(markerOptions, marker.getTitle());
            }
            if (marker.getSnippet() != null) {
                java.lang.reflect.Method snippetMethod = markerOptionsClass.getMethod("snippet", String.class);
                snippetMethod.invoke(markerOptions, marker.getSnippet());
            }
            
            // 设置图标
            if (marker.getIconBitmap() != null) {
                Class<?> bitmapDescriptorClass = Class.forName("com.amap.api.maps.model.BitmapDescriptor");
                Class<?> bitmapDescriptorFactoryClass = Class.forName("com.amap.api.maps.model.BitmapDescriptorFactory");
                java.lang.reflect.Method fromBitmapMethod = bitmapDescriptorFactoryClass.getMethod("fromBitmap", Bitmap.class);
                Object bitmapDescriptor = fromBitmapMethod.invoke(null, marker.getIconBitmap());
                
                java.lang.reflect.Method iconMethod = markerOptionsClass.getMethod("icon", bitmapDescriptorClass);
                iconMethod.invoke(markerOptions, bitmapDescriptor);
            }
            
            // 添加标记
            Class<?> aMapClass = Class.forName("com.amap.api.maps.AMap");
            java.lang.reflect.Method addMarkerMethod = aMapClass.getMethod("addMarker", markerOptionsClass);
            Object amapMarker = addMarkerMethod.invoke(aMap, markerOptions);
            
            markers.put(marker.getId(), amapMarker);
            
            return marker.getId();
        } catch (Exception e) {
            Log.e(TAG, "Failed to add marker", e);
            return marker.getId();
        }
    }
    
    @Override
    public void removeMarker(String markerId) {
        Object marker = markers.remove(markerId);
        if (marker != null) {
            try {
                java.lang.reflect.Method removeMethod = marker.getClass().getMethod("remove");
                removeMethod.invoke(marker);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove marker", e);
            }
        }
    }
    
    @Override
    public void clearMarkers() {
        for (Object marker : markers.values()) {
            try {
                java.lang.reflect.Method removeMethod = marker.getClass().getMethod("remove");
                removeMethod.invoke(marker);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove marker", e);
            }
        }
        markers.clear();
    }
    
    @Override
    public String addPolyline(MapPolyline polyline) {
        if (aMap == null) {
            Log.w(TAG, "AMap is not ready yet, cannot add polyline");
            return polyline.getId();
        }
        
        try {
            // 坐标转换：WGS84 -> GCJ02
            List<MapPolyline.LatLng> gcj02Points = polyline.getPoints().stream()
                    .map(p -> {
                        double[] gcj02 = CoordinateConverter.wgs84ToGcj02(p.getLatitude(), p.getLongitude());
                        return new MapPolyline.LatLng(gcj02[0], gcj02[1]);
                    })
                    .collect(Collectors.toList());
            
            // 创建PolylineOptions
            // com.amap.api.maps.model.PolylineOptions
            Class<?> polylineOptionsClass = Class.forName("com.amap.api.maps.model.PolylineOptions");
            Object polylineOptions = polylineOptionsClass.newInstance();
            
            // 添加点
            // com.amap.api.maps.model.LatLng
            Class<?> latLngClass = Class.forName("com.amap.api.maps.model.LatLng");
            java.lang.reflect.Method addMethod = polylineOptionsClass.getMethod("add", latLngClass);
            
            for (MapPolyline.LatLng point : gcj02Points) {
                Object latLng = latLngClass.getConstructor(double.class, double.class)
                        .newInstance(point.getLatitude(), point.getLongitude());
                addMethod.invoke(polylineOptions, latLng);
            }
            
            // 设置颜色和宽度
            java.lang.reflect.Method colorMethod = polylineOptionsClass.getMethod("color", int.class);
            colorMethod.invoke(polylineOptions, polyline.getColor());
            
            java.lang.reflect.Method widthMethod = polylineOptionsClass.getMethod("width", float.class);
            widthMethod.invoke(polylineOptions, polyline.getWidth());
            
            // 添加路径线
            Class<?> aMapClass = Class.forName("com.amap.api.maps.AMap");
            java.lang.reflect.Method addPolylineMethod = aMapClass.getMethod("addPolyline", polylineOptionsClass);
            Object amapPolyline = addPolylineMethod.invoke(aMap, polylineOptions);
            
            polylines.put(polyline.getId(), amapPolyline);
            
            return polyline.getId();
        } catch (Exception e) {
            Log.e(TAG, "Failed to add polyline", e);
            return polyline.getId();
        }
    }
    
    @Override
    public void removePolyline(String polylineId) {
        Object polyline = polylines.remove(polylineId);
        if (polyline != null) {
            try {
                java.lang.reflect.Method removeMethod = polyline.getClass().getMethod("remove");
                removeMethod.invoke(polyline);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove polyline", e);
            }
        }
    }
    
    @Override
    public void clearPolylines() {
        for (Object polyline : polylines.values()) {
            try {
                java.lang.reflect.Method removeMethod = polyline.getClass().getMethod("remove");
                removeMethod.invoke(polyline);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove polyline", e);
            }
        }
        polylines.clear();
    }
    
    @Override
    public void moveCamera(double latitude, double longitude, float zoom) {
        if (aMap == null) return;
        
        try {
            // 坐标转换：WGS84 -> GCJ02
            double[] gcj02 = CoordinateConverter.wgs84ToGcj02(latitude, longitude);
            
            // com.amap.api.maps.model.LatLng
            Class<?> latLngClass = Class.forName("com.amap.api.maps.model.LatLng");
            Object latLng = latLngClass.getConstructor(double.class, double.class)
                    .newInstance(gcj02[0], gcj02[1]);
            
            // com.amap.api.maps.CameraUpdateFactory
            Class<?> cameraUpdateFactoryClass = Class.forName("com.amap.api.maps.CameraUpdateFactory");
            java.lang.reflect.Method newLatLngZoomMethod = cameraUpdateFactoryClass.getMethod(
                    "newLatLngZoom", latLngClass, float.class);
            Object cameraUpdate = newLatLngZoomMethod.invoke(null, latLng, zoom);
            
            Class<?> aMapClass = Class.forName("com.amap.api.maps.AMap");
            java.lang.reflect.Method moveCameraMethod = aMapClass.getMethod("moveCamera", 
                    Class.forName("com.amap.api.maps.CameraUpdate"));
            moveCameraMethod.invoke(aMap, cameraUpdate);
        } catch (Exception e) {
            Log.e(TAG, "Failed to move camera", e);
        }
    }
    
    @Override
    public void animateCamera(double latitude, double longitude, float zoom, Runnable callback) {
        if (aMap == null) return;
        
        try {
            // 坐标转换：WGS84 -> GCJ02
            double[] gcj02 = CoordinateConverter.wgs84ToGcj02(latitude, longitude);
            
            // com.amap.api.maps.model.LatLng
            Class<?> latLngClass = Class.forName("com.amap.api.maps.model.LatLng");
            Object latLng = latLngClass.getConstructor(double.class, double.class)
                    .newInstance(gcj02[0], gcj02[1]);
            
            // com.amap.api.maps.CameraUpdateFactory
            Class<?> cameraUpdateFactoryClass = Class.forName("com.amap.api.maps.CameraUpdateFactory");
            java.lang.reflect.Method newLatLngZoomMethod = cameraUpdateFactoryClass.getMethod(
                    "newLatLngZoom", latLngClass, float.class);
            Object cameraUpdate = newLatLngZoomMethod.invoke(null, latLng, zoom);
            
            Class<?> aMapClass = Class.forName("com.amap.api.maps.AMap");
            
            if (callback != null) {
                // 创建回调
                // com.amap.api.maps.AMap.CancelableCallback
                Class<?> cancelableCallbackClass = Class.forName("com.amap.api.maps.AMap$CancelableCallback");
                Object callbackObj = java.lang.reflect.Proxy.newProxyInstance(
                        cancelableCallbackClass.getClassLoader(),
                        new Class[]{cancelableCallbackClass},
                        (proxy, method, args) -> {
                            if (method.getName().equals("onFinish") || method.getName().equals("onCancel")) {
                                callback.run();
                            }
                            return null;
                        }
                );
                
                java.lang.reflect.Method animateCameraMethod = aMapClass.getMethod("animateCamera",
                        Class.forName("com.amap.api.maps.CameraUpdate"), 
                        long.class, 
                        cancelableCallbackClass);
                animateCameraMethod.invoke(aMap, cameraUpdate, 1000L, callbackObj);
            } else {
                java.lang.reflect.Method animateCameraMethod = aMapClass.getMethod("animateCamera",
                        Class.forName("com.amap.api.maps.CameraUpdate"));
                animateCameraMethod.invoke(aMap, cameraUpdate);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to animate camera", e);
        }
    }
    
    @Override
    public void setOnMapClickListener(OnMapClickListener listener) {
        this.onMapClickListener = listener;
        if (aMap == null) return;
        
        try {
            Class<?> aMapClass = Class.forName("com.amap.api.maps.AMap");
            Class<?> onMapClickListenerClass = Class.forName("com.amap.api.maps.AMap$OnMapClickListener");
            
            Object listenerObj = java.lang.reflect.Proxy.newProxyInstance(
                    onMapClickListenerClass.getClassLoader(),
                    new Class[]{onMapClickListenerClass},
                    (proxy, method, args) -> {
                        if (method.getName().equals("onMapClick")) {
                            Object latLng = args[0];
                            // 3D SDK中，LatLng的latitude和longitude是public字段
                            java.lang.reflect.Field latitudeField = latLng.getClass().getField("latitude");
                            java.lang.reflect.Field longitudeField = latLng.getClass().getField("longitude");
                            double lat = latitudeField.getDouble(latLng);
                            double lon = longitudeField.getDouble(latLng);
                            
                            // 坐标转换：GCJ02 -> WGS84
                            double[] wgs84 = CoordinateConverter.gcj02ToWgs84(lat, lon);
                            if (onMapClickListener != null) {
                                onMapClickListener.onMapClick(wgs84[0], wgs84[1]);
                            }
                        }
                        return null;
                    }
            );
            
            java.lang.reflect.Method setOnMapClickListenerMethod = aMapClass.getMethod("setOnMapClickListener", onMapClickListenerClass);
            setOnMapClickListenerMethod.invoke(aMap, listenerObj);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set map click listener", e);
        }
    }
    
    @Override
    public void setOnMarkerClickListener(OnMarkerClickListener listener) {
        this.onMarkerClickListener = listener;
        if (aMap == null) return;
        
        try {
            Class<?> aMapClass = Class.forName("com.amap.api.maps.AMap");
            Class<?> onMarkerClickListenerClass = Class.forName("com.amap.api.maps.AMap$OnMarkerClickListener");
            
            Object listenerObj = java.lang.reflect.Proxy.newProxyInstance(
                    onMarkerClickListenerClass.getClassLoader(),
                    new Class[]{onMarkerClickListenerClass},
                    (proxy, method, args) -> {
                        if (method.getName().equals("onMarkerClick")) {
                            Object marker = args[0];
                            String markerId = findMarkerId(marker);
                            if (onMarkerClickListener != null) {
                                return onMarkerClickListener.onMarkerClick(markerId);
                            }
                        }
                        return false;
                    }
            );
            
            java.lang.reflect.Method setOnMarkerClickListenerMethod = aMapClass.getMethod("setOnMarkerClickListener", onMarkerClickListenerClass);
            setOnMarkerClickListenerMethod.invoke(aMap, listenerObj);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set marker click listener", e);
        }
    }
    
    private String findMarkerId(Object marker) {
        for (Map.Entry<String, Object> entry : markers.entrySet()) {
            if (entry.getValue().equals(marker)) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        // 高德地图可能不支持setPadding，或者API不同
        // 需要根据实际SDK调整
        Log.d(TAG, "setPadding called: " + left + ", " + top + ", " + right + ", " + bottom);
    }
    
    @Override
    public CameraPosition getCameraPosition() {
        if (aMap == null) return null;
        
        try {
            Class<?> aMapClass = Class.forName("com.amap.api.maps.AMap");
            java.lang.reflect.Method getCameraPositionMethod = aMapClass.getMethod("getCameraPosition");
            Object cameraPosition = getCameraPositionMethod.invoke(aMap);
            
            // com.amap.api.maps.model.CameraPosition
            // 3D SDK中，target是public字段
            java.lang.reflect.Field targetField = cameraPosition.getClass().getField("target");
            Object target = targetField.get(cameraPosition);
            
            // LatLng的latitude和longitude是public字段
            java.lang.reflect.Field latitudeField = target.getClass().getField("latitude");
            java.lang.reflect.Field longitudeField = target.getClass().getField("longitude");
            double lat = latitudeField.getDouble(target);
            double lon = longitudeField.getDouble(target);
            
            // zoom也是public字段
            java.lang.reflect.Field zoomField = cameraPosition.getClass().getField("zoom");
            float zoom = zoomField.getFloat(cameraPosition);
            
            // 坐标转换：GCJ02 -> WGS84
            double[] wgs84 = CoordinateConverter.gcj02ToWgs84(lat, lon);
            
            return new CameraPosition(wgs84[0], wgs84[1], zoom);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get camera position", e);
            return null;
        }
    }
    
    @Override
    public void setMyLocationButtonEnabled(boolean enabled) {
        if (aMap == null) return;
        try {
            Class<?> aMapClass = Class.forName("com.amap.api.maps.AMap");
            Class<?> uiSettingsClass = Class.forName("com.amap.api.maps.UiSettings");
            java.lang.reflect.Method getUiSettingsMethod = aMapClass.getMethod("getUiSettings");
            Object uiSettings = getUiSettingsMethod.invoke(aMap);
            
            java.lang.reflect.Method setMyLocationButtonEnabledMethod = uiSettingsClass.getMethod("setMyLocationButtonEnabled", boolean.class);
            setMyLocationButtonEnabledMethod.invoke(uiSettings, enabled);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set my location button enabled", e);
        }
    }
    
    @Override
    public void setRotateGesturesEnabled(boolean enabled) {
        if (aMap == null) return;
        try {
            Class<?> aMapClass = Class.forName("com.amap.api.maps.AMap");
            Class<?> uiSettingsClass = Class.forName("com.amap.api.maps.UiSettings");
            java.lang.reflect.Method getUiSettingsMethod = aMapClass.getMethod("getUiSettings");
            Object uiSettings = getUiSettingsMethod.invoke(aMap);
            
            java.lang.reflect.Method setRotateGesturesEnabledMethod = uiSettingsClass.getMethod("setRotateGesturesEnabled", boolean.class);
            setRotateGesturesEnabledMethod.invoke(uiSettings, enabled);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set rotate gestures enabled", e);
        }
    }
    
    @Override
    public void setCompassEnabled(boolean enabled) {
        if (aMap == null) return;
        try {
            Class<?> aMapClass = Class.forName("com.amap.api.maps.AMap");
            Class<?> uiSettingsClass = Class.forName("com.amap.api.maps.UiSettings");
            java.lang.reflect.Method getUiSettingsMethod = aMapClass.getMethod("getUiSettings");
            Object uiSettings = getUiSettingsMethod.invoke(aMap);
            
            java.lang.reflect.Method setCompassEnabledMethod = uiSettingsClass.getMethod("setCompassEnabled", boolean.class);
            setCompassEnabledMethod.invoke(uiSettings, enabled);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set compass enabled", e);
        }
    }
    
    @Override
    public void setMapToolbarEnabled(boolean enabled) {
        // 高德地图可能没有工具栏，或者API不同
        Log.d(TAG, "setMapToolbarEnabled called: " + enabled);
    }
    
    @Override
    public void clear() {
        clearMarkers();
        clearPolylines();
    }
    
    @Override
    public View getMapView() {
        return mapView;
    }
    
    /**
     * 在Activity的onResume中调用
     */
    public void onResume() {
        if (mapView != null) {
            try {
                java.lang.reflect.Method onResumeMethod = mapView.getClass().getMethod("onResume");
                onResumeMethod.invoke(mapView);
            } catch (Exception e) {
                Log.e(TAG, "Failed to call onResume on map view", e);
            }
        }
    }
    
    /**
     * 在Activity的onPause中调用
     */
    public void onPause() {
        if (mapView != null) {
            try {
                java.lang.reflect.Method onPauseMethod = mapView.getClass().getMethod("onPause");
                onPauseMethod.invoke(mapView);
            } catch (Exception e) {
                Log.e(TAG, "Failed to call onPause on map view", e);
            }
        }
    }
    
    /**
     * 在Activity的onDestroy中调用
     */
    public void onDestroy() {
        if (mapView != null) {
            try {
                java.lang.reflect.Method onDestroyMethod = mapView.getClass().getMethod("onDestroy");
                onDestroyMethod.invoke(mapView);
            } catch (Exception e) {
                Log.e(TAG, "Failed to call onDestroy on map view", e);
            }
        }
    }
}

