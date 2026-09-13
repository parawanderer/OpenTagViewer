package dev.wander.android.opentagviewer.ui.maps;

import android.app.Activity;
import android.view.View;

/**
 * 地图提供商抽象接口
 * 定义统一的地图操作API,支持多地图SDK切换
 * 
 * 实现类:
 * - GoogleMapProvider: Google Maps实现
 * - AMapProvider: 高德地图实现
 */
public interface IMapProvider {
    
    /**
     * 初始化地图
     * @param activity 当前Activity
     * @param containerViewId 地图容器View的ID
     * @param callback 地图就绪回调
     */
    void initialize(Activity activity, int containerViewId, OnMapReadyCallback callback);
    
    /**
     * 设置地图样式
     */
    void setMapStyle(MapStyle mapStyle);
    
    /**
     * 添加标记点
     * @param marker 标记点数据
     * @return 标记点ID
     */
    String addMarker(MapMarker marker);
    
    /**
     * 移除标记点
     * @param markerId 标记点ID
     */
    void removeMarker(String markerId);

    /**
     * 设置标记点的绘制顺序（数值大的显示在上层）
     * <br>
     * Raises or lowers an existing marker. Tags kept in the same place overlap completely at
     * anything but the closest zoom, so selecting one has to bring it to the front - otherwise
     * the user taps a card and nothing visibly happens.
     *
     * @param markerId 标记点ID
     * @param zIndex 绘制顺序
     */
    void setMarkerZIndex(String markerId, float zIndex);

    /**
     * 清除所有标记点
     */
    void clearMarkers();
    
    /**
     * 添加路径线
     * @param polyline 路径线数据
     * @return 路径线ID
     */
    String addPolyline(MapPolyline polyline);
    
    /**
     * 移除路径线
     * @param polylineId 路径线ID
     */
    void removePolyline(String polylineId);
    
    /**
     * 清除所有路径线
     */
    void clearPolylines();
    
    /**
     * 移动相机（不带动画）
     * @param latitude 纬度
     * @param longitude 经度
     * @param zoom 缩放级别
     */
    void moveCamera(double latitude, double longitude, float zoom);
    
    /**
     * 动画移动相机
     * @param latitude 纬度
     * @param longitude 经度
     * @param zoom 缩放级别
     * @param callback 动画完成回调(可为null)
     */
    void animateCamera(double latitude, double longitude, float zoom, Runnable callback);
    
    /**
     * 设置地图点击监听器
     * @param listener 点击监听器
     */
    void setOnMapClickListener(OnMapClickListener listener);
    
    /**
     * 设置标记点击监听器
     * @param listener 点击监听器
     */
    void setOnMarkerClickListener(OnMarkerClickListener listener);
    
    /**
     * 设置地图内边距
     * @param left 左边距
     * @param top 上边距
     * @param right 右边距
     * @param bottom 下边距
     */
    void setPadding(int left, int top, int right, int bottom);
    
    /**
     * 获取当前相机位置
     * @return 相机位置信息
     */
    CameraPosition getCameraPosition();
    
    /**
     * 设置我的位置按钮是否可见
     * @param enabled true为可见
     */
    void setMyLocationButtonEnabled(boolean enabled);
    
    /**
     * 设置旋转手势是否可用
     * @param enabled true为可用
     */
    void setRotateGesturesEnabled(boolean enabled);
    
    /**
     * 设置指南针是否可见
     * @param enabled true为可见
     */
    void setCompassEnabled(boolean enabled);
    
    /**
     * 设置地图工具栏是否可见
     * @param enabled true为可见
     */
    void setMapToolbarEnabled(boolean enabled);
    
    /**
     * 清除地图上所有覆盖物（标记点、路径线等）
     */
    void clear();
    
    /**
     * 获取地图View对象
     * @return 地图View
     */
    View getMapView();

    /**
     * The zoom this provider opens a tag at.
     *
     * <p><b>Per provider, because a zoom level is not a distance.</b> The number is an index into
     * whatever tile pyramid the provider draws, and two providers at the same index do not
     * necessarily show the same amount of street - so one value shared across all of them means
     * one of them is wrong, and which one depends on who last tuned it.
     *
     * <p>Google's scale is the reference (level 16 is "streets"), so that is the default and
     * nothing that was working changes by this existing.
     */
    default float initialZoom() {
        return 16.0f;
    }

    /**
     * Forwarded from the hosting activity, for providers whose map view needs the callback.
     *
     * <p><b>Default no-ops so that a provider which does not care says nothing</b>, and - the
     * actual point - so the activity never asks which provider it has. It used to: three
     * {@code instanceof AMapProvider} blocks, one per callback, and the next provider added
     * copied them. That is the branching {@code IMapProvider} exists to prevent (AGENTS.md rule
     * 7), and a new implementation should need no edit in {@code MapsActivity} at all.
     *
     * <p>AMap's SDK requires all three; osmdroid requires resume and pause; Google's
     * {@code MapView} is managed by the fragment and requires none.
     */
    default void onResume() {
    }

    /** See {@link #onResume()}. */
    default void onPause() {
    }

    /** See {@link #onResume()}. */
    default void onDestroy() {
    }
    
    /**
     * 地图就绪回调接口
     */
    interface OnMapReadyCallback {
        /**
         * 地图已就绪,可以开始操作
         * @param mapProvider 地图提供商实例
         */
        void onMapReady(IMapProvider mapProvider);
    }
    
    /**
     * 地图点击监听器
     */
    interface OnMapClickListener {
        /**
         * 地图被点击
         * @param latitude 点击位置的纬度
         * @param longitude 点击位置的经度
         */
        void onMapClick(double latitude, double longitude);
    }
    
    /**
     * 标记点击监听器
     */
    interface OnMarkerClickListener {
        /**
         * 标记点被点击
         * @param markerId 标记点ID
         * @return true表示消费事件,false表示不消费
         */
        boolean onMarkerClick(String markerId);
    }
    
    /**
     * 相机位置信息
     */
    class CameraPosition {
        private final double latitude;
        private final double longitude;
        private final float zoom;
        
        public CameraPosition(double latitude, double longitude, float zoom) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.zoom = zoom;
        }
        
        public double getLatitude() {
            return latitude;
        }
        
        public double getLongitude() {
            return longitude;
        }
        
        public float getZoom() {
            return zoom;
        }
    }

    enum MapStyle {
        LIGHT,
        DARK,
        FOLLOW_SYSTEM
    }
}
