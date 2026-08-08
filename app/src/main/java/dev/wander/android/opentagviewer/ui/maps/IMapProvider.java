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
     * @param darkMode true为夜间模式,false为白天模式
     */
    void setMapStyle(boolean darkMode);
    
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
}
