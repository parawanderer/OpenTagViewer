package dev.wander.android.opentagviewer.ui.maps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The seam that lets a test put a map on a device that cannot have one.
 *
 * <p>The instrumented suite runs on {@code aosp-atd}, which has no Play Services, so a real
 * provider cannot initialise there. Rule 7 already put providers behind {@link IMapProvider} -
 * this adds the one thing missing, a way to hand a different one to the screens.
 *
 * <p><b>What this does not yet do is launch {@code MapsActivity}.</b> Probing that established
 * something worth writing down: Play Services is <i>not</i> the blocker - the screen reaches map
 * initialisation happily without it. What stops it is that the screen requires a usable
 * signed-in session, and restoring one goes through {@code PythonAuthService.restoreAccount},
 * which is static and has no seam. A stored blob that is not a real encrypted session fails to
 * restore, and the screen then redirects to login before drawing anything.
 *
 * <p>So the end-to-end journey needs an account seam next, not a map one. That is the useful
 * result of building this, and it is recorded here rather than in a test that only passes when
 * run on its own.
 */
@RunWith(AndroidJUnit4.class)
public class MapProviderSubstitutionTest {

    @After
    public void putTheRealOneBack() {
        MapProviderFactory.reset();
    }

    /** Without the hook, production behaviour is untouched. */
    @Test
    public void bydefaultAReadProviderIsBuilt() {
        MapProviderFactory.reset();

        assertNotNull(MapProviderFactory.create(MapProviderFactory.PROVIDER_GOOGLE));
    }

    /** With it, every screen gets the substitute regardless of the configured provider. */
    @Test
    public void asubstituteIsHandedOutInsteadOfAnyRealProvider() {
        final FakeMapProvider fake = new FakeMapProvider();
        MapProviderFactory.replaceWith(() -> fake);

        assertSame(fake, MapProviderFactory.create(MapProviderFactory.PROVIDER_GOOGLE));
        assertSame(fake, MapProviderFactory.create(MapProviderFactory.PROVIDER_AMAP));
        assertSame(fake, MapProviderFactory.create(null));
    }

    /** And resetting genuinely restores it, or the next test inherits a fake map. */
    @Test
    public void resettingRestoresTheRealFactory() {
        MapProviderFactory.replaceWith(FakeMapProvider::new);
        MapProviderFactory.reset();

        assertTrue("reset must hand back a real provider again",
                MapProviderFactory.create(MapProviderFactory.PROVIDER_GOOGLE)
                        instanceof GoogleMapProvider);
    }

    /**
     * The fake records what it is asked for, which is the half a test actually asserts on.
     *
     * <p>"Is there a marker for each tag, in the right place" says what the app decided; a
     * screenshot of a map says what Google drew.
     */
    @Test
    public void thefakeRecordsWhatTheScreenAsksOfIt() {
        final FakeMapProvider fake = new FakeMapProvider();

        final String id = fake.addMarker(MapMarker.builder()
                .position(52.37, 4.90)
                .title("Bike")
                .build());
        fake.moveCamera(52.37, 4.90, 15f);

        assertEquals(1, fake.markerCount());
        assertEquals("Bike", fake.markers().get(0).marker.getTitle());
        assertEquals(1, fake.cameraMoves().size());
        assertEquals(15f, fake.cameraMoves().get(0).getZoom(), 0.001f);

        fake.removeMarker(id);
        assertEquals(0, fake.markerCount());
    }
}
