package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.python.AppDependencies;
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
import dev.wander.android.opentagviewer.util.rx.RefreshPolicy;

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
 * <p><b>Drawing anything needs a session that restores, which is why {@code apple_test_double}
 * exists.</b> {@code handleAuthAndShowDevices} <i>zips</i> the cached-beacon stream with
 * {@code PythonAuthService.restoreAccount}, so a session that will not restore disposes the
 * drawing side before it emits - and no session a test can write by hand will deserialise into a
 * FindMy.py account. Confirmed rather than assumed: taking the double out again turns all three
 * marker tests red.
 *
 * <p>Restoring needs no network - {@code getAccount} is {@code AppleAccount.from_json} and
 * nothing else - so the double replaces only that and the fetch. Everything between them is the
 * shipping code.
 *
 * <p>With the fetch reachable too, the chain runs end to end: the session restores, the fetch
 * crosses the bridge, Python serialises what the account returned, the repository stores it, and
 * the screen redraws the tag where it now is. Which also makes redrawing testable - the cached
 * pin is drawn and then replaced - so "one marker, not two" is asserted rather than deferred.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class TheMapDrawsWhatIsStoredTest {

    private static final String A_TEST_USER = "map-draws@example.com";

    private static final String FOUND_TAG = "a-located-tag";
    private static final String NEVER_SEEN_TAG = "a-tag-nobody-walked-past";

    private static final double LATITUDE = 52.370216;
    private static final double LONGITUDE = 4.895168;

    /** Somewhere a fetch could not be confused with the cached report. */
    private static final double FETCHED_LATITUDE = 48.858370;
    private static final double FETCHED_LONGITUDE = 2.294481;

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
    private PyObject appleDouble;

    @Before
    public void seedTwoTagsAndSubstituteTheMap() {
        // Or the map/login/settings screen loads Apple's real ADI library: a download,
        // a dlopen and a native initialise, none of which this test is about - and two
        // screens reaching it at once segfaults the process and aborts the whole run.
        // See issue #135.
        AppDependencies.replaceAnisette(whateverTheSettingsSay -> FakeAnisetteSource.ready());
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

        // **Otherwise the startup fetch is skipped.** RefreshPolicy is a process-wide singleton
        // that survives an activity being rebuilt - deliberately, so a theme change does not cost
        // a full walk of every tag's key history - which also means one test's fetch suppresses
        // the next test's.
        RefreshPolicy.resetShared();

        this.fake = new FakeMapProvider();
        MapProviderFactory.replaceWith(() -> this.fake);
    }

    /**
     * Make the stored session restore, so the drawing side of the zip actually runs.
     *
     * <p>Not every test wants this: {@link #anunrestorableSessionSendsYouBackToSignIn} needs the
     * opposite. So it is opt-in rather than setup, and torn down either way.
     */
    private void givenTheSessionRestores() {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(getInstrumentation().getTargetContext()));
        }
        this.appleDouble = Python.getInstance().getModule("apple_test_double");
        this.appleDouble.callAttr("installWithNothingToReport");
    }

    /**
     * The same, but every fetch comes back with a location - so the fetch path runs too.
     *
     * <p>Two hours ago, which is inside every window the app asks for, so nothing here depends
     * on which window a given path chose.
     */
    private void givenTheAccountReports(final double latitude, final double longitude) {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(getInstrumentation().getTargetContext()));
        }
        this.appleDouble = Python.getInstance().getModule("apple_test_double");
        this.appleDouble.callAttr("install", latitude, longitude, 2.0);
    }

    @After
    public void putEverythingBack() {
        // Put the real Anisette source back, or the next class inherits the fake.
        AppDependencies.reset();
        if (this.scenario != null) {
            this.scenario.close();
        }
        if (this.appleDouble != null) {
            this.appleDouble.callAttr("uninstall");
        }
        MapProviderFactory.reset();
        this.forgetThem();
        this.guard.restore();
    }

    private void insert(final long importId, final String id, final String name) {
        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(id).importId(importId).content(A_PLIST).version("0.0.2")
                .fromAccount(false).isRemoved(false)
                // **Stored, so nothing tries to derive it from the plist above.** That plist is
                // shaped like a real one and is not one - its private key is eighteen bytes
                // where a real key is twenty-eight - so FindMy.py refuses it, the request list
                // comes back empty, and the fetch never happens. Silently: a conversion failure
                // is logged per accessory and the batch carries on with what is left, which is
                // nothing. What is in here does not matter, because apple_test_double replaces
                // the function that reads it.
                .accessoryJson("{\"type\": \"accessory\", \"id\": \"" + id + "\"}")
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
     * <b>A tag with a stored location gets a marker.</b>
     *
     * <p>The assertion this screen most needed and could not have until the session restored.
     * The double returns nothing from the fetch, so what is drawn here is what the database
     * already held - which is the behaviour worth pinning: somebody opening the app sees where
     * their things were before Apple is asked anything.
     */
    @Test
    public void astoredLocationIsDrawn() {
        this.givenTheSessionRestores();
        this.openTheMap();

        Eventually.check(() -> assertEquals("the stored tag was not drawn",
                1, this.markersFor(FOUND_TAG).size()));
    }

    /** And where the report says, not merely somewhere. */
    @Test
    public void themarkerIsAtTheStoredCoordinates() {
        this.givenTheSessionRestores();
        this.openTheMap();

        Eventually.check(() -> assertEquals(1, this.markersFor(FOUND_TAG).size()));

        final MapMarker drawn = this.markersFor(FOUND_TAG).get(0).marker;

        assertEquals(LATITUDE, drawn.getLatitude(), 0.000001);
        assertEquals(LONGITUDE, drawn.getLongitude(), 0.000001);
    }

    /**
     * <b>A tag nobody has ever walked past gets no marker.</b>
     *
     * <p>Intended, and it looks like a fault from the outside - the warning-level log about a tag
     * that cannot be drawn reads like one. Pinned so nobody later fixes the non-problem by
     * putting a marker at 0,0, which is in the Atlantic.
     */
    @Test
    public void atagWithNoLocationIsNotDrawnAtAll() {
        this.givenTheSessionRestores();
        this.openTheMap();

        Eventually.check(() -> assertEquals(1, this.markersFor(FOUND_TAG).size()));

        assertTrue("a tag with no location was given a marker anyway",
                this.markersFor(NEVER_SEEN_TAG).isEmpty());
    }

    /**
     * Every marker the fake was asked to place for one beacon.
     *
     * <p><b>Matched on the marker's id, which is the beacon id.</b> Not on the title: the screen
     * does not set one - a pin carries the tag's emoji or icon as its bitmap and nothing else -
     * so a title-based filter matches nothing and reads as "the map drew nothing", which is a
     * long way from the truth and cost a debugging round here.
     */
    private List<FakeMapProvider.PlacedMarker> markersFor(final String beaconId) {
        return this.fake.markers().stream()
                .filter(placed -> beaconId.equals(placed.marker.getId()))
                .collect(Collectors.toList());
    }

    /**
     * <b>A fetched location replaces the stored one, and the pin moves.</b>
     *
     * <p>The whole chain in one assertion: the session restores, the fetch runs through the real
     * bridge, Python serialises what the account returned, the repository stores it, and the
     * screen redraws the tag where it now is. Every step between the two doubles is shipping
     * code.
     *
     * <p>The two coordinates are a continent apart on purpose. A fetch that silently returned
     * the cached report would pass an "is there a marker" test perfectly.
     */
    @Test
    public void afetchedLocationMovesThePin() {
        this.givenTheAccountReports(FETCHED_LATITUDE, FETCHED_LONGITUDE);
        this.openTheMap();

        // **Asked first, and kept.** When this failed, "the pin is in the wrong place" was true
        // and useless - the fetch had never reached Python at all, because the request list came
        // back empty and nothing says so out loud. One question separates "the fetch is wrong"
        // from "there was no fetch", and it is worth a line.
        Eventually.check(() -> assertTrue("python was never asked to fetch anything",
                this.appleDouble.callAttr("howManyFetches").toInt() > 0));

        Eventually.check(() -> {
            assertEquals(1, this.markersFor(FOUND_TAG).size());

            final MapMarker drawn = this.markersFor(FOUND_TAG).get(0).marker;
            assertEquals("the pin is still on the cached location, so the fetch never landed",
                    FETCHED_LATITUDE, drawn.getLatitude(), 0.000001);
            assertEquals(FETCHED_LONGITUDE, drawn.getLongitude(), 0.000001);
        });
    }

    /**
     * <b>And redrawing replaces the pin rather than stacking another on it.</b>
     *
     * <p>Reachable now only because the fetch is: the screen draws the cached location, then
     * draws the fetched one over it, so a run of this test redraws the same tag for real. There
     * must be one marker at the end, not two.
     *
     * <p>Worth an assertion of its own because the failure is invisible on a real map - two pins
     * at nearly the same place look like one - and permanent, since nothing ever removes the
     * stale one.
     */
    @Test
    public void redrawingReplacesThePinRatherThanStackingOne() {
        this.givenTheAccountReports(FETCHED_LATITUDE, FETCHED_LONGITUDE);
        this.openTheMap();

        Eventually.check(() -> assertEquals(FETCHED_LATITUDE,
                this.markersFor(FOUND_TAG).get(0).marker.getLatitude(), 0.000001));

        assertEquals("the tag was drawn twice and the older pin was never removed",
                1, this.markersFor(FOUND_TAG).size());
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
