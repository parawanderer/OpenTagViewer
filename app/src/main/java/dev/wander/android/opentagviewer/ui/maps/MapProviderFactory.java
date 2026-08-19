package dev.wander.android.opentagviewer.ui.maps;

import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.util.function.Supplier;

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
    /**
     * A provider to hand out instead of a real one, or null in production.
     *
     * <p><b>The reason this hook exists.</b> The instrumented tests run on the {@code aosp-atd}
     * managed device, which carries no Play Services - so a screen that builds a real map cannot
     * start there at all, and {@code MapsActivity} has therefore never been launched by a test.
     * The map, the tag carousel, history and delete are the most-used parts of the app and the
     * least covered.
     *
     * <p>Rule 7 is what makes this cheap: providers are already behind {@link IMapProvider}, so a
     * fake is another implementation rather than a change to the screens.
     */
    private static Supplier<IMapProvider> replacement = null;

    @VisibleForTesting
    public static void replaceWith(final Supplier<IMapProvider> factory) {
        replacement = factory;
    }

    /** Put the real ones back. Call from a teardown, or the next test inherits the fake. */
    @VisibleForTesting
    public static void reset() {
        replacement = null;
    }

    public static IMapProvider create(String providerType) {
        if (replacement != null) {
            Log.d(TAG, "Creating a substituted map provider");
            return replacement.get();
        }

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

