package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

import dev.wander.android.opentagviewer.DeviceStateGuard;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.MapsActivity;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * The map screen, driven for the first time.
 *
 * <p><b>{@code MapsActivity} had never been started by a test.</b> The instrumented suite runs on
 * the {@code aosp-atd} managed device, which carries no Play Services, so a real map cannot
 * initialise - and the map, the tag carousel and everything hanging off them went uncovered. A
 * change to any of it could compile, pass the whole suite, and crash on launch.
 *
 * <p>{@link FakeMapProvider} has existed for a while and was only ever exercised directly, which
 * proves the fake works and nothing about the screen. This is the other half: the activity is
 * launched for real, against a database with tags in it, and asked what it drew.
 *
 * <p><b>What is not covered, and why.</b> The obvious thing to assert is "a tag with a stored
 * location gets a marker". It is not here, because the drawing is not reachable without a
 * restorable Apple session: {@code handleAuthAndShowDevices} zips the cached-beacon stream with
 * {@code PythonAuthService.restoreAccount}, so a session that will not restore disposes the
 * drawing side before it emits. A test can supply a session blob, but not one FindMy.py will
 * deserialise into an account - that needs a double on the Python side of the bridge, the same
 * shape as {@code icloud_test_double}, which does not exist for the auth path yet.
 *
 * <p>Written down rather than faked around, because the pragmatic alternative - making the
 * drawing method visible so a test can call it - buys the assertion by making the thing under
 * test slightly worse, and would pin the drawing while leaving the path that actually reaches it
 * uncovered.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class TheMapDrawsWhatIsStoredTest {

    private static final String A_TEST_USER = "map-draws@example.com";

    private static final String FOUND_TAG = "a-located-tag";
    private static final String NEVER_SEEN_TAG = "a-tag-nobody-walked-past";

    private static final double LATITUDE = 52.370216;
    private static final double LONGITUDE = 4.895168;

    private static final String A_PLIST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<plist version=\"1.0\"><dict>"
            + "<key>batteryLevel</key><integer>1</integer>"
            + "<key>model</key><string></string>"
            + "<key>pairingDate</key><date>2025-02-27T20:03:32Z</date>"
            + "<key>privateKey</key><dict><key>key</key><dict>"
            + "<key>data</key><data>bm90LWEtcmVhbC1rZXk=</data></dict></dict>"
            + "<key>productId</key><integer>21760</integer>"
            + "<key>stableIdentifier</key><array><string>2001~#0~#A0</string></array>"
            + "<key>systemVersion</key><string>2.0.73</string>"
            + "<key>vendorId</key><integer>76</integer>"
            + "</dict></plist>";

    private OpenTagViewerDatabase db;
    private DeviceStateGuard guard;
    private FakeMapProvider fake;
    private ActivityScenario<MapsActivity> scenario;

    @Before
    public void seedTwoTagsAndSubstituteTheMap() {
        final Context context = getInstrumentation().getTargetContext();

        this.guard = DeviceStateGuard.capture(context);
        this.db = OpenTagViewerDatabase.getInstance(context);
        this.forgetThem();

        // **Enough of a session to get past the door, and no more.** The screen sends anybody
        // without one straight to the login screen, and everything below happens after that
        // check. Restoring this into a real Apple account fails, on a background thread, which
        // is exactly the state somebody with an expired session is in.
        new UserAuthRepository(UserAuthDataStore.getInstance(context), new AppCryptographyUtil())
                .storeUserAuth("{\"not\":\"a restorable account\"}"
                        .getBytes(StandardCharsets.UTF_8))
                .blockingAwait();

        final long importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2").importedAt(1_700_000_000_000L).exportedAt(1_699_000_000_000L)
                .sourceUser(A_TEST_USER).exportedVia("OpenTagViewer.wizard:test").build());

        this.insert(importId, FOUND_TAG, "Bike");
        this.insert(importId, NEVER_SEEN_TAG, "Wallet");

        this.db.locationReportDao().insertAll(LocationReport.builder()
                .hashId("a-stored-report")
                .beaconId(FOUND_TAG)
                .publishedAt(1_700_000_000_000L)
                .description("Wi-Fi")
                .timestamp(1_700_000_000_000L)
                .confidence(0)
                .latitude(LATITUDE)
                .longitude(LONGITUDE)
                .horizontalAccuracy(83)
                .status(144)
                .lastUpdate(1_700_000_000_000L)
                .build());

        this.fake = new FakeMapProvider();
        MapProviderFactory.replaceWith(() -> this.fake);
    }

    @After
    public void putEverythingBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        MapProviderFactory.reset();
        this.forgetThem();
        this.guard.restore();
    }

    private void insert(final long importId, final String id, final String name) {
        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(id).importId(importId).content(A_PLIST).version("0.0.2")
                .fromAccount(false).isRemoved(false)
                .build());

        this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                .id(id).importId(importId).version("0.0.2").isRemoved(false)
                .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                        + "<key>identifier</key><string>" + id + "</string>"
                        + "<key>name</key><string>" + name + "</string>"
                        + "</dict></plist>")
                .build());
    }

    private void forgetThem() {
        for (final String id : new String[] {FOUND_TAG, NEVER_SEEN_TAG}) {
            this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(id).build());
            this.db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(id).build());
        }
        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
    }

    private void openTheMap() {
        this.scenario = ActivityScenario.launch(new Intent(
                getInstrumentation().getTargetContext(), MapsActivity.class));
    }

    /**
     * <b>It starts at all.</b>
     *
     * <p>Thin, and the single most valuable assertion here: until now nothing launched this
     * screen, so any crash in {@code onCreate} - a missing view id, a null provider, a binding
     * that no longer matches - was found by running the app rather than by running the suite.
     */
    @Test
    public void themapScreenStartsAndTakesTheProviderItIsGiven() {
        this.openTheMap();

        Eventually.check(() -> assertTrue("the map provider was never initialised",
                this.fake.isReady()));
    }

    /**
     * <b>A session that will not restore sends you back to sign in.</b>
     *
     * <p>Deliberate, and narrow: {@code isAccountRestoreFailure} matches only
     * {@code PythonAccountLoginException}, which is what a blob saved by FindMy 0.7.6 produces
     * under 0.9.x. Leaving somebody on a map that can never refresh would be worse than asking
     * them to sign in again.
     *
     * <p>Pinned because the narrowness is the load-bearing part. Widening that check to any
     * restore failure would start throwing people out to login for transient reasons, and the
     * cost of being wrong is a re-login against Apple - not something to trigger on a hiccup.
     */
    @Test
    public void anunrestorableSessionSendsYouBackToSignIn() {
        this.openTheMap();

        Eventually.check(() -> assertEquals(
                "the map stayed open on a session that cannot ever refresh",
                Lifecycle.State.DESTROYED, this.scenario.getState()));
    }

}
