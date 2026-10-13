package dev.wander.android.opentagviewer.ui.maps;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MapProviderFactoryTest {

    private static final String A_REAL_KEY = "AIzaSyExampleExampleExampleExample123";

    @Test
    public void googleWithARealKeyStaysGoogle() {
        assertEquals(MapProviderFactory.PROVIDER_GOOGLE,
                MapProviderFactory.effectiveProvider(MapProviderFactory.PROVIDER_GOOGLE, A_REAL_KEY));
    }

    @Test
    public void googleWithoutAUsableKeyFallsBackToOpenStreetMap() {
        for (final String key : new String[]{null, "", "  ", "DEFAULT_API_KEY", "maps_key_default_value"}) {
            assertEquals("key: " + key, MapProviderFactory.PROVIDER_OSM,
                    MapProviderFactory.effectiveProvider(MapProviderFactory.PROVIDER_GOOGLE, key));
        }
    }

    /** create() turns an unset or unknown provider into Google, so those need the key too. */
    @Test
    public void anUnsetOrUnknownProviderIsTreatedAsGoogle() {
        assertEquals(MapProviderFactory.PROVIDER_OSM,
                MapProviderFactory.effectiveProvider(null, "DEFAULT_API_KEY"));
        assertEquals(MapProviderFactory.PROVIDER_OSM,
                MapProviderFactory.effectiveProvider("bing", null));
        assertEquals("bing", MapProviderFactory.effectiveProvider("bing", A_REAL_KEY));
    }

    @Test
    public void otherProvidersNeverDependOnTheGoogleKey() {
        assertEquals(MapProviderFactory.PROVIDER_AMAP,
                MapProviderFactory.effectiveProvider(MapProviderFactory.PROVIDER_AMAP, null));
        assertEquals(MapProviderFactory.PROVIDER_OSM,
                MapProviderFactory.effectiveProvider(MapProviderFactory.PROVIDER_OSM, null));
    }
}
