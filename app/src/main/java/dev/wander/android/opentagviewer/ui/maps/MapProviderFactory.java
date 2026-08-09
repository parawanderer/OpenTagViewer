package dev.wander.android.opentagviewer.ui.maps;

import android.app.Activity;
import android.util.Log;

/**
 * 地图提供商工厂类
 * 根据用户设置创建对应的地图提供商实例
 */
public class MapProviderFactory {
    private static final String TAG = MapProviderFactory.class.getSimpleName();
    
    public static final String PROVIDER_GOOGLE = "google";
    public static final String PROVIDER_AMAP = "amap";
    
    /**
     * 创建地图提供商实例
     * @param providerType 提供商类型 ("google" 或 "amap")
     * @return 地图提供商实例
     */
    public static IMapProvider create(String providerType) {
        if (providerType == null || providerType.isEmpty() || PROVIDER_GOOGLE.equals(providerType)) {
            Log.d(TAG, "Creating Google Maps provider");
            return new GoogleMapProvider();
        } else if (PROVIDER_AMAP.equals(providerType)) {
            Log.d(TAG, "Creating AMap provider");
            return new AMapProvider();
        } else {
            Log.w(TAG, "Unknown provider type: " + providerType + ", defaulting to Google Maps");
            return new GoogleMapProvider();
        }
    }
}

