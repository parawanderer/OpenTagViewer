package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.nio.charset.StandardCharsets;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.wander.android.opentagviewer.DeviceStateGuard;
import dev.wander.android.opentagviewer.MapsActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.util.rx.RefreshPolicy;

/**
 * The map, open, with tags on it - the starting point seven tests need and none of them own.
 *
 * <p><b>Written once because it is the same eight-step arrangement every time.</b> Reaching a
 * drawn map means a stored session, a Python double that will restore it, a substituted map
 * provider, a geocoder that answers, beacons with usable accessory state, locations for them to
 * be drawn at, and the shared refresh policy reset so the startup fetch is not skipped. Copying
 * that into each test is how they drift: one forgets {@code RefreshPolicy.resetShared} and
 * passes for a year because the fetch it meant to observe never ran.
 *
 * <p><b>It is a fixture, not a base class.</b> Tests hold one and call {@link #open}; they do not
 * extend it. Inheritance would put the seeding out of sight of the test that depends on it, and
 * the seeding is exactly what a reader needs to see to know what an assertion means.
 *
 * <p>Everything it leaves behind is undone by {@link #putItBack}, which belongs in an
 * {@code @After} - including the device's own settings, which this overwrites.
 */
public final class AMapWithTagsOnIt {

    /** Somewhere no other test reports from, so a stale row cannot be mistaken for this one. */
    public static final double LATITUDE = 48.858370;
    public static final double LONGITUDE = 2.294481;

    public static final String WHERE_THAT_IS = "A made-up street, in a made-up town";

    private static final String A_TEST_USER = "map-fixture@example.com";

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

    private final Context context = getInstrumentation().getTargetContext();
    private final OpenTagViewerDatabase db = OpenTagViewerDatabase.getInstance(this.context);

    private final List<String> beaconIds = new ArrayList<>();

    private DeviceStateGuard guard;
    private FakeMapProvider map;
    private FakeGeocoder geocoder;
    private PyObject appleDouble;
    private ActivityScenario<MapsActivity> scenario;

    // ------------------------------------------------------------------ what a test reads

    public FakeMapProvider map() {
        return this.map;
    }

    public FakeGeocoder geocoder() {
        return this.geocoder;
    }

    public ActivityScenario<MapsActivity> scenario() {
        return this.scenario;
    }

    /** The ids of the tags that were seeded, in the order they were named. */
    public List<String> tagIds() {
        return new ArrayList<>(this.beaconIds);
    }

    // ------------------------------------------------------------------ arranging it

    /**
     * Seed a tag per name, each with a location, and put the fakes in place.
     *
     * <p>Every tag is given a location on purpose. A tag without one gets no card and no pin -
     * intended behaviour, and the reason a fixture that quietly seeded one would make a test
     * about cards fail for a reason nowhere near the card.
     */
    public AMapWithTagsOnIt seed(final String... names) {
        this.guard = DeviceStateGuard.capture(this.context);
        this.grantLocationUpFront();
        this.forgetThem();

        // Enough of a session to get past the door. What is in it does not matter, because
        // apple_test_double replaces the function that would read it.
        new UserAuthRepository(
                UserAuthDataStore.getInstance(this.context), new AppCryptographyUtil())
                .storeUserAuth("{\"not\":\"a restorable account\"}".getBytes(StandardCharsets.UTF_8))
                .blockingAwait();

        // **Otherwise a dialog opens on top of the map and every assertion looks at that.**
        // MapsActivity offers the move to local Anisette to anybody who is signed in and has
        // never chosen a mode - which is exactly what writing a session by hand produces. The
        // offer is correct behaviour; it is just not what these tests are about, and Espresso's
        // default root becomes the dialog, so the map's own controls are "not in the hierarchy".
        //
        // Recorded as already offered rather than answered, because answering it writes an
        // Anisette mode and that is a different thing to be asserting about by accident.
        final UserSettingsRepository settingsRepo = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.context));
        final UserSettings settings = settingsRepo.getUserSettings();
        settings.setAnisetteUpgradeOffered(true);
        // **And the same for the iCloud offer, for exactly the same reason.** A hand-written
        // session with no keychain membership is precisely who that one is for, so without this
        // every test built on this fixture opens the map behind a dialog and Espresso's default
        // root becomes the dialog rather than the map.
        //
        // A test that is *about* the offer must not use this fixture's default - see
        // TheICloudOfferAppearsOnceTest, which arranges its own state.
        settings.setIcloudOfferMade(true);
        settingsRepo.storeUserSettings(settings).blockingAwait();

        final long importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2").importedAt(1_700_000_000_000L).exportedAt(1_699_000_000_000L)
                .sourceUser(A_TEST_USER).exportedVia("OpenTagViewer.wizard:test").build());

        for (int i = 0; i < names.length; i++) {
            final String beaconId = "fixture-tag-" + i;
            this.beaconIds.add(beaconId);

            this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                    .id(beaconId).importId(importId).content(A_PLIST).version("0.0.2")
                    .fromAccount(false).isRemoved(false)
                    // Stored, so nothing tries to derive it from the plist above - that key is
                    // eighteen bytes where a real one is twenty-eight, so FindMy.py refuses it
                    // and the fetch silently never happens.
                    .accessoryJson("{\"type\": \"accessory\", \"id\": \"" + beaconId + "\"}")
                    .build());

            this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                    .id(beaconId).importId(importId).version("0.0.2").isRemoved(false)
                    .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<plist version=\"1.0\"><dict>"
                            + "<key>identifier</key><string>" + beaconId + "</string>"
                            + "<key>name</key><string>" + names[i] + "</string>"
                            + "</dict></plist>")
                    .build());

            // **Reported a couple of hours ago, not on a fixed date in 2023.** The timestamp
            // decides which day the report belongs to, and the history screen asks for one
            // local day at a time - so a fixed past date gives every screen built on this
            // fixture an empty "today", which reads as the history being broken.
            //
            // Two hours rather than minutes, so it is comfortably inside today whatever hour
            // the suite runs at, and inside every window the app asks for.
            final long reportedAt = System.currentTimeMillis() - (2L * 60 * 60 * 1000);

            // Spread apart, so "which card is centred" has a different answer per tag.
            this.db.locationReportDao().insertAll(LocationReport.builder()
                    .hashId(beaconId + "-seeded")
                    .beaconId(beaconId)
                    .publishedAt(reportedAt)
                    .description("Wi-Fi")
                    .timestamp(reportedAt)
                    .confidence(2)
                    .latitude(LATITUDE + (i * 0.001))
                    .longitude(LONGITUDE + (i * 0.001))
                    .horizontalAccuracy(83)
                    .status(144)
                    .lastUpdate(reportedAt)
                    .build());
        }

        this.map = new FakeMapProvider();
        MapProviderFactory.replaceWith(() -> this.map);

        this.geocoder = new FakeGeocoder().saying(LATITUDE, LONGITUDE, WHERE_THAT_IS);
        AppDependencies.replaceGeocoder((ctx, locale) -> this.geocoder);

        // **Otherwise the map loads Apple's real ADI library, and it can take the process down.**
        //
        // MapsActivity asks for Anisette on the way to restoring a session, and the real source
        // dlopens libstoreservicescore.so and initialises it. That is correct in the app. In a
        // suite it happens once per test, in one process, on whichever Rx thread got there - and
        // it segfaults:
        //
        //   AnisetteStatus.of -> LocalAnisette.ensureReady -> LocalAnisette.load
        //     -> AdiLibrary.initialise -> NativeAdi.callWithPath -> libstoreservicescore.so
        //     signal 11 (SIGSEGV) in tid RxCachedThreadS
        //
        // A native crash takes the whole instrumentation with it, so the run does not merely
        // fail - it *stops*, and every remaining test is reported as never having run. That is
        // the intermittent whole-suite abort this repo has been living with; the stack above is
        // from the CI logcat artefact, which is what finally caught it.
        //
        // Nothing built on this fixture is testing Anisette, so none of them should be loading
        // it. The login-flow tests already do this; the map ones never did.
        AppDependencies.replaceAnisette(whateverTheSettingsSay -> FakeAnisetteSource.ready());

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this.context));
        }
        this.appleDouble = Python.getInstance().getModule("apple_test_double");
        // Nothing to report: the cached locations above are what gets drawn, and a fetch that
        // moved every pin would make "where is this tag drawn" depend on the double instead of
        // on the seed. TheWholeAppJourneyTest is where the fetch itself is exercised.
        this.appleDouble.callAttr("installWithNothingToReport");

        // Otherwise a previous test's fetch suppresses this one's: the policy is process-wide
        // and outlives any activity, deliberately.
        RefreshPolicy.resetShared();

        return this;
    }

    /**
     * <b>Granted before the map opens, or nothing after this works.</b>
     *
     * <p>{@code MapsActivity} asks for location the moment it starts. The dialog belongs to the
     * system permission controller, not to this app, so it takes focus, pauses the map, and
     * leaves Espresso reporting "no activity currently resumed" - which reads as the app having
     * crashed. It is expensive to diagnose, too: the root picker waits thirty seconds per retry,
     * so one affected test ran for six minutes before saying anything.
     *
     * <p>Done here rather than with a {@code GrantPermissionRule} in each test, because a rule
     * is per-class and this is a property of arranging the map at all - a new test that used the
     * fixture and forgot the rule would hit the same six-minute wall.
     */
    private void grantLocationUpFront() {
        final String packageName = this.context.getPackageName();

        for (final String permission : new String[] {
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION}) {
            try {
                getInstrumentation().getUiAutomation()
                        .grantRuntimePermission(packageName, permission);
            } catch (final RuntimeException alreadyGrantedOrNotGrantable) {
                // Already held, which is the usual case after the first test in a run.
            }
        }
    }

    /** Launch the map and hand back the scenario. */
    public ActivityScenario<MapsActivity> open() {
        this.scenario = ActivityScenario.launch(new Intent(this.context, MapsActivity.class));
        return this.scenario;
    }

    /**
     * Store an arrangement, as though the user had already dragged the device list into it.
     *
     * <p>Call after {@link #seed} and before {@link #open}. The arguments are seed positions, so
     * {@code seed("Wallet", "Keys", "Bag").arrangedAs(2, 0, 1)} means the user put Bag first.
     *
     * <p>Written through the same DAO the app writes through, rather than by hand, so a test
     * using this cannot pass against a storage shape the app does not actually produce.
     */
    public AMapWithTagsOnIt arrangedAs(final int... seedIndices) {
        final Map<String, Integer> positions = new LinkedHashMap<>();
        for (int position = 0; position < seedIndices.length; position++) {
            positions.put(this.beaconIds.get(seedIndices[position]), position);
        }

        this.db.userBeaconOptionsDao().storeArrangement(positions, System.currentTimeMillis());
        return this;
    }

    /** Apple decides this session needs a verification code again, mid-use. */
    public void theSessionGoesStale() {
        this.appleDouble.callAttr("makeTheSessionNeedACode");
    }

    /** The session needs a code and Apple offers no way to send one - see the double. */
    public void theSessionGoesStaleBeyondRescue() {
        this.appleDouble.callAttr("makeTheSessionUnrescuable");
    }

    /** Whether a code put the session back - the rescue itself, not the UI reacting. */
    public boolean theSessionIsUsableAgain() {
        return this.appleDouble.callAttr("theSessionIsUsableAgain").toBoolean();
    }

    /** Codes actually sent to Apple, right or wrong. */
    public int codesSubmitted() {
        return this.appleDouble.callAttr("howManyCodesSubmitted").toInt();
    }

    /** The names on the tag cards, left to right - which is the order the carousel shows. */
    public List<String> cardNames() {
        final List<String> names = new ArrayList<>();

        for (final View card : this.cards()) {
            final TextView name = card.findViewById(R.id.device_name);
            if (name != null) {
                names.add(name.getText().toString());
            }
        }

        return names;
    }

    // ------------------------------------------------------------------ asking about cards

    /** The tag cards currently on screen, left to right, as the row holds them. */
    public List<View> cards() {
        final List<View> found = new ArrayList<>();

        this.scenario.onActivity(activity -> {
            final ViewGroup row = activity.findViewById(R.id.tags_scroll_container);
            if (row == null) {
                return;
            }
            for (int i = 0; i < row.getChildCount(); i++) {
                found.add(row.getChildAt(i));
            }
        });

        return found;
    }

    /**
     * How far the nearest card's centre is from the middle of the row, in pixels.
     *
     * <p>The number the carousel's settling is judged by: whatever a gesture did, it has to end
     * with one card centred rather than two half-shown.
     */
    public int howFarOffCentreTheNearestCardIs() {
        final int[] answer = {Integer.MAX_VALUE};

        this.scenario.onActivity(activity -> {
            final View area = activity.findViewById(R.id.tags_scrollable_area);
            final ViewGroup row = activity.findViewById(R.id.tags_scroll_container);
            if (area == null || row == null || row.getChildCount() == 0) {
                return;
            }

            final int[] areaAt = new int[2];
            area.getLocationOnScreen(areaAt);
            final int rowMiddle = areaAt[0] + (area.getWidth() / 2);

            final int[] cardAt = new int[2];
            for (int i = 0; i < row.getChildCount(); i++) {
                final View card = row.getChildAt(i);
                card.getLocationOnScreen(cardAt);

                final int cardMiddle = cardAt[0] + (card.getWidth() / 2);
                answer[0] = Math.min(answer[0], Math.abs(rowMiddle - cardMiddle));
            }
        });

        return answer[0];
    }

    // ------------------------------------------------------------------ putting it back

    /** Call from {@code @After}, always - this overwrote the device's own settings. */
    public void putItBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        if (this.appleDouble != null) {
            this.appleDouble.callAttr("uninstall");
        }

        MapProviderFactory.reset();
        AppDependencies.reset();

        this.forgetThem();

        if (this.guard != null) {
            this.guard.restore();
        }
    }

    private void forgetThem() {
        for (final String beaconId : this.beaconIds) {
            // Reports go with it: LocationReport cascades on the beacon being deleted.
            this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(beaconId).build());
            this.db.beaconNamingRecordDao().delete(
                    BeaconNamingRecord.builder().id(beaconId).build());
        }

        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
    }
}
