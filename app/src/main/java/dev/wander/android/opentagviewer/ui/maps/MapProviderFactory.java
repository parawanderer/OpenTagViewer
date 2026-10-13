package dev.wander.android.opentagviewer.ui.maps;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.util.Set;
import java.util.function.Supplier;

/**
 * 地图提供商工厂类
 * 根据用户设置创建对应的地图提供商实例
 */
public class MapProviderFactory {
    private static final String TAG = MapProviderFactory.class.getSimpleName();
    
    public static final String PROVIDER_GOOGLE = "google";
    public static final String PROVIDER_AMAP = "amap";
    public static final String PROVIDER_OSM = "osm";
    
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

    /**
     * Values the Maps key takes in a build that has no real one, so Google would draw an empty
     * grey map. {@code DEFAULT_API_KEY} comes from {@code local.defaults.properties} when there is
     * no {@code secrets.properties}; {@code maps_key_default_value} is what
     * {@code build-debug.yml} writes when the repository secret is unavailable, as on fork PRs.
     */
    private static final Set<String> PLACEHOLDER_KEYS = Set.of("DEFAULT_API_KEY", "maps_key_default_value");

    /**
     * The provider to actually use. A build without a usable Google Maps key gets OpenStreetMap,
     * which needs none, instead of a map that cannot load.
     */
    public static String effectiveProvider(final Context context, final String providerType) {
        String key = null;
        try {
            final Bundle meta = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA).metaData;
            key = meta == null ? null : meta.getString("com.google.android.geo.API_KEY");
        } catch (final Exception e) {
            Log.w(TAG, "Could not read the Maps API key; treating it as absent", e);
        }
        return providerFor(providerType, key);
    }

    /** {@link #effectiveProvider(Context, String)} once the key has been read. */
    static String providerFor(final String providerType, final String googleMapsKey) {
        if (PROVIDER_AMAP.equals(providerType) || PROVIDER_OSM.equals(providerType)) {
            return providerType;
        }
        // Google, unset or unknown - all of which create() turns into Google Maps.
        if (googleMapsKey == null || googleMapsKey.isBlank() || PLACEHOLDER_KEYS.contains(googleMapsKey)) {
            Log.w(TAG, "No usable Google Maps key in this build; using OpenStreetMap");
            return PROVIDER_OSM;
        }
        return providerType;
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
        } else if (PROVIDER_OSM.equals(providerType)) {
            Log.d(TAG, "Creating OpenStreetMap provider");
            return new OsmMapProvider();
        } else {
            Log.w(TAG, "Unknown provider type: " + providerType + ", defaulting to Google Maps");
            return new GoogleMapProvider();
        }
    }
}

