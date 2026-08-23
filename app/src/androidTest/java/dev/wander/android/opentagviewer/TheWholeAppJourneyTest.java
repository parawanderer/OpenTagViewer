package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.rule.GrantPermissionRule;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.db.AccountBeaconsForTests;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.FakeAppleAuthService;
import dev.wander.android.opentagviewer.python.icloud.FakeICloudService;
import dev.wander.android.opentagviewer.ui.maps.FakeGeocoder;
import dev.wander.android.opentagviewer.ui.maps.FakeMapProvider;
import dev.wander.android.opentagviewer.ui.maps.MapProviderFactory;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.util.rx.RefreshPolicy;

/**
 * The app, from a signed-out install to a tag's history, without stopping.
 *
 * <p><b>Every screen here is covered, and the joins between them were not.</b> Signing in has a
 * test, reading the account has a test, the map has a test, and each of them starts from a state
 * the previous one is trusted to have produced - written by hand, in a {@code @Before}. This one
 * produces them. Nothing is seeded: the tags on the map are the tags the account handed over
 * minutes earlier, through the real importer, the real repository and the real bridge.
 *
 * <p><b>Two joins already broke here and nowhere else.</b> {@code SignInThenGetTagsJourneyTest}
 * stubs {@code MapsActivity} at the door, because the {@code aosp-atd} image carries no Play
 * Services, so nothing had ever gone from a completed sign-in to a drawn map. And
 * {@code HistoryViewActivity} reads {@code PythonAppleService.getInstance()} - a process-wide
 * singleton that only the map sets up - so it could not be launched by any test that did not
 * start the map first. It had none, and no coverage at all.
 *
 * <p><b>What is faked is the outside world, and only that.</b> Four edges:
 *
 * <ul>
 *   <li>{@link FakeAppleAuthService} and {@link FakeAnisetteSource} - signing in reaches Apple
 *       and downloads Apple's ADI libraries, neither arrangeable on a build machine</li>
 *   <li>{@link FakeICloudService} - the keychain escrow read, which needs an Apple account with
 *       a device on it</li>
 *   <li>{@code apple_test_double}, on the Python side of the bridge - session restore and the
 *       fetches. Placed there rather than in Java deliberately: two shipped bugs lived in the
 *       bridge itself, so a Java-side fake would have skipped exactly the seam that broke</li>
 *   <li>{@link FakeMapProvider} - which is what the request excludes. It records what the app
 *       asked to be drawn instead of drawing it, which is the more useful half: "there is a pin
 *       for this tag, at these coordinates" is a claim about the app's decision, not about what
 *       Google rendered</li>
 * </ul>
 *
 * <p>Everything between those edges - the importer, Room, the repositories, the Rx plumbing,
 * every activity and every hand-off between them - is the shipping code.
 *
 * <p><b>One test, on purpose.</b> The point is the continuity: a per-screen split is what the
 * suite already has, and it is what let two joins go unexamined. It is paced with
 * {@link TestPace}, so this is the one to run with {@code slowMotion} when somebody wants to
 * watch the app work rather than read that it does.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class TheWholeAppJourneyTest {

    private static final String EMAIL = "someone@example.com";
    private static final String PASSWORD = "hunter2";
    private static final String CODE = "123456";
    private static final String DEVICE_PASSCODE = "123456";

    /** How many reports the account has for today. Asserted on the history screen, exactly. */
    private static final int REPORTS_TODAY = 3;

    /** Where the walk starts. Somewhere no other test reports from, so a stale row is obvious. */
    private static final double LATITUDE = 48.858370;
    private static final double LONGITUDE = 2.294481;

    /** How far each later report of the day moves on from the one before it. */
    private static final double STEP_LATITUDE = 0.003;
    private static final double STEP_LONGITUDE = 0.004;

    /**
     * Where the newest report of the day is - which is what the map pin shows.
     *
     * <p>Derived rather than written out, because it has to follow {@link #REPORTS_TODAY} and the
     * step. Writing it as a second literal is how this test broke when the reports were first
     * spread out: the pin moved to the newest report, correctly, and the assertion still named
     * the oldest.
     */
    private static final double LAST_LATITUDE = LATITUDE + ((REPORTS_TODAY - 1) * STEP_LATITUDE);
    private static final double LAST_LONGITUDE =
            LONGITUDE + ((REPORTS_TODAY - 1) * STEP_LONGITUDE);

    /** What the fake geocoder calls that spot. Invented, and worded so as not to look real. */
    private static final String WHERE_THAT_IS = "A made-up street, in a made-up town";

    /**
     * <b>Granted up front, or the journey stops on the map and never restarts.</b>
     *
     * <p>{@code MapsActivity} asks for location the moment it opens. The permission dialog
     * belongs to the system's permission controller, not to this app, so it takes focus, pauses
     * the map, and leaves Espresso reporting "no activity currently resumed" - which reads as
     * the app having died and is the system waiting politely for an answer nothing will give.
     * Costly to diagnose too: every retry pays the root picker's own thirty-second wait.
     *
     * <p>The screen-level map tests never met it because they ask the fake provider what was
     * drawn rather than asking Espresso for a view, and a dialog in front of the activity does
     * not change either answer.
     */
    @Rule
    public GrantPermissionRule locationGranted = GrantPermissionRule.grant(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION);

    private FakeAppleAuthService apple;
    private FakeICloudService icloud;
    private FakeMapProvider map;
    private FakeGeocoder geocoder;
    private KeychainMembershipRepository memberships;
    private DeviceStateGuard deviceState;
    private PyObject appleDouble;
    private ActivityScenario<?> scenario;

    @Before
    public void signOutAndReplaceTheOutsideWorld() {
        final Context context = getInstrumentation().getTargetContext();

        this.deviceState = DeviceStateGuard.capture(context);
        signEverybodyOut();

        this.memberships = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(context), new AppCryptographyUtil());
        this.memberships.forget().blockingAwait();
        AccountBeaconsForTests.forgetThemAll();

        this.apple = FakeAppleAuthService.wantsTwoFactor();
        this.icloud = FakeICloudService.withTags();
        AppDependencies.replaceAuthService(this.apple);
        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());
        AppDependencies.replaceICloud(() -> this.icloud);

        this.map = new FakeMapProvider();
        MapProviderFactory.replaceWith(() -> this.map);

        // **So the screens show a place rather than a pair of numbers.** The managed device has
        // no geocoding backend, and the app's fallback for "no address found" is to print the
        // coordinates - a correct fallback that makes a missing geocoder indistinguishable from
        // an honest answer, on screen and in every screenshot.
        // Registered at the *newest* report, because that is the one the card describes. The
        // fake matches within about a hundred metres, and the walk moves further than that
        // between reports - so naming the starting point here would leave the card correctly
        // showing the fallback for an unregistered spot.
        this.geocoder = new FakeGeocoder().saying(LAST_LATITUDE, LAST_LONGITUDE, WHERE_THAT_IS);
        AppDependencies.replaceGeocoder((ctx, locale) -> this.geocoder);

        this.givenTheAccountHasReportedToday();

        // **Otherwise the startup fetch is skipped and no tag is ever located.** RefreshPolicy
        // is process-wide and survives an activity being rebuilt - deliberately, so a theme
        // change does not cost a walk of every tag's key history - which also means one test's
        // fetch suppresses the next test's.
        RefreshPolicy.resetShared();
    }

    /**
     * Put this journey's reports on today, at times the history screen will ask for.
     *
     * <p><b>Absolute times, not offsets.</b> The history screen asks for one <i>local</i> day at
     * a time, so "two hours ago" falls on yesterday for anybody running the suite shortly after
     * midnight - a test that is green all day and red at 00:30 for reasons that have nothing to
     * do with the app. The day boundary is computed here the same way the screen computes it.
     */
    private void givenTheAccountHasReportedToday() {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(getInstrumentation().getTargetContext()));
        }

        this.appleDouble = Python.getInstance().getModule("apple_test_double");
        this.appleDouble.callAttr("install");
        this.appleDouble.callAttr("clearReports");

        final long startOfToday = startOfTodayLocal();
        for (int i = 0; i < REPORTS_TODAY; i++) {
            // A minute apart, and only minutes into the day, so every one of them is in the
            // past however early the suite runs.
            //
            // **And each one somewhere slightly different.** All three used to be at the same
            // spot, which is a fair description of a tag sitting on a shelf and a poor test: a
            // route through three identical points draws as a dot, so the history screen's line
            // could have been drawn through any of them, or one of them, and looked the same.
            // A few hundred metres apart makes the drawing say what it did.
            this.appleDouble.callAttr("reportAt",
                    startOfToday + ((i + 1) * 60_000L),
                    LATITUDE + (i * STEP_LATITUDE), LONGITUDE + (i * STEP_LONGITUDE));
        }
    }

    /** Midnight this morning, by the same clock {@code HistoryViewActivity} uses. */
    private static long startOfTodayLocal() {
        return Instant.ofEpochMilli(System.currentTimeMillis())
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    @After
    public void putEverythingBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        if (this.appleDouble != null) {
            this.appleDouble.callAttr("uninstall");
        }

        MapProviderFactory.reset();
        AppDependencies.reset();

        getInstrumentation().waitForIdleSync();
        AccountBeaconsForTests.forgetThemAll();
        this.memberships.forget().blockingAwait();
        signEverybodyOut();
        this.deviceState.restore();
    }

    // ------------------------------------------------------------------ the journey

    @Test
    public void fromSignedOutToATagsHistoryWithoutSeedingAnything() {
        this.signIn();
        this.themapOpensOnAnAccountWithNothingOnItYet();
        this.readTheAccountFromTheDeviceList();
        this.thetagsAreDrawnWhereTheAccountSaysTheyAre();
        this.openTheBikesHistory();
    }

    // ------------------------------------------------------------------ step by step

    /** Email, password, a code by text - and the map is what a sign-in ends at. */
    private void signIn() {
        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);
        TestPace.afterAStep();

        onView(withId(R.id.email_or_phone_input_field)).perform(replaceText(EMAIL));
        onView(withId(R.id.password_input_field))
                .perform(replaceText(PASSWORD), closeSoftKeyboard());
        TestPace.afterAStep();

        Eventually.perform("the sign in button", () -> this.apple.timesCalled("login") > 0,
                () -> onView(withId(R.id.login_button_main)).perform(click()));
        TestPace.afterAStep();

        Eventually.check(() -> onView(withText(
                getInstrumentation().getTargetContext().getString(
                        R.string.auth_by_sms_to_x, FakeAppleAuthService.PHONE_ONE)))
                .perform(click()));
        TestPace.afterAStep();

        Eventually.check(() -> onView(withId(R.id.twofa_sent_info_text))
                .check(matches(isDisplayed())));
        // Pasted rather than typed: the boxes move focus as they fill, so per-character typing
        // fails the moment the field it started on stops being focused - and pasting is what
        // people do with a code they were just sent.
        onView(withId(R.id.twofactorauth_textinput_1)).perform(replaceText(CODE));
        TestPace.afterAStep();
    }

    /**
     * <b>The map, for real, on the far side of a sign-in.</b>
     *
     * <p>The join nothing crossed before. The sign-in screen finishes itself and starts this,
     * which restores the session through Python and draws whatever is stored - and with a fresh
     * install nothing is, so the assertion is that it got as far as having a map at all.
     */
    private void themapOpensOnAnAccountWithNothingOnItYet() {
        Eventually.check(() -> assertTrue("signing in never reached a working map",
                this.map.isReady()));

        assertTrue("a fresh install drew a pin for something", this.map.markers().isEmpty());
        TestPace.afterAStep();

        this.theofferToConnectTheAccountIsWaitingThere();
    }

    /**
     * <b>The first thing a new user actually meets on the map.</b>
     *
     * <p>Somebody who has just signed in has no account connected and has never been asked, which
     * is exactly who the offer is for - so it is part of this journey rather than something to
     * suppress. Asserted rather than merely dismissed: it is the only time the app ever mentions
     * the feature that removes the Mac from the story, and a journey test that quietly clicked
     * past it would not notice it disappearing.
     *
     * <p>Declined, because the rest of this journey goes the long way round - through My Devices
     * and the account screen - which is the route somebody who says "not now" then takes.
     */
    private void theofferToConnectTheAccountIsWaitingThere() {
        Eventually.check(() -> onView(withText(R.string.icloud_offer_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        TestPace.afterAStep();

        onView(withText(R.string.icloud_offer_not_now)).inRoot(isDialog()).perform(click());
        TestPace.afterAStep();
    }

    /** Map to My Devices to the account, and back with two tags. */
    private void readTheAccountFromTheDeviceList() {
        Eventually.check(() -> onView(withId(R.id.button_more_settings))
                .check(matches(isDisplayed())));
        onView(withId(R.id.button_more_settings)).perform(click());
        TestPace.afterAStep();

        // Not wrapped in Eventually: a PopupMenu is shown on the main thread inside the click
        // handler, so it is already up by the time the perform returns - and asking for a
        // platform-popup root that is not there yet is the expensive question, not the cheap
        // one. Espresso's root picker retries internally for seconds before admitting it.
        onView(withText(R.string.my_devices)).inRoot(isPlatformPopup()).perform(click());
        TestPace.afterAStep();

        // Nothing has ever been imported, so this is the empty state, and its own button is the
        // way in - the same button a first-run user meets.
        Eventually.check(() -> onView(withId(R.id.my_devices_empty_fetch_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.my_devices_empty_fetch_button)).perform(click());
        TestPace.afterAStep();

        this.unlockWithADevicePasscode();

        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();
        onView(withId(R.id.icloud_primary_button)).perform(click());

        // **The tags are not the last step.** What this app registered as on the Apple account
        // comes after them, so the results button says Next and this one says Done.
        Eventually.check(() -> onView(withId(R.id.icloud_registered_container))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();
        onView(withId(R.id.icloud_primary_button)).perform(click());

        // Back on the device list, which rebuilt itself when the fetch reported tags.
        Eventually.check(() -> onView(withId(R.id.my_devices_list))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.my_devices_empty_state))
                .check(matches(not(isDisplayed()))));
        TestPace.afterAStep();

        // **Rows, not a screenful.** The list could show what the fetch is still holding in
        // memory; what has to be true is that it was written down, with the accessory state
        // that makes a tag locatable rather than merely present.
        final OpenTagViewerDatabase db = OpenTagViewerDatabase.getInstance(
                getInstrumentation().getTargetContext());
        final List<OwnedBeacon> stored = db.ownedBeaconDao().getAll();

        assertEquals("the account's two tags should have been written", 2, stored.size());
        for (final OwnedBeacon beacon : stored) {
            assertTrue(beacon.id + " imported with no accessory state, so it looks imported and"
                    + " can never be located", beacon.accessoryJson != null);
        }
    }

    /**
     * <b>Back on the map, and the tags are on it.</b>
     *
     * <p>Which is a fetch, not a redraw: these tags have never been located, so the pin can only
     * be there if the request crossed the bridge, Python answered, and the repository stored it.
     * Asked of Python first, because "no marker" and "a marker in the wrong place" have very
     * different causes and the screen shows neither.
     */
    private void thetagsAreDrawnWhereTheAccountSaysTheyAre() {
        pressBack();
        TestPace.afterAStep();

        Eventually.check(() -> assertTrue("python was never asked to locate the new tags",
                this.appleDouble.callAttr("howManyFetches").toInt() > 0));

        Eventually.check(() -> assertEquals("the imported tags were never drawn",
                2, this.map.markers().size()));

        final FakeMapProvider.PlacedMarker bike = this.markerFor(
                FakeICloudService.A_BIKE.getBeaconId());

        // **The newest report of the day, not the first.** The tag moved between reports, and
        // the pin shows where it is now - which is the whole point of the screen.
        assertEquals("the pin is not on the tag's most recent report",
                LAST_LATITUDE, bike.marker.getLatitude(), 0.000001);
        assertEquals(LAST_LONGITUDE, bike.marker.getLongitude(), 0.000001);

        // **And the card says where that is, in words.** Its fallback when no address can be
        // found is the raw coordinates, which is right - and means that until there was a
        // geocoder to answer, this assertion could not tell a working lookup from no lookup at
        // all. It also covers the rounding: the app geocodes a rounded pair, so what is asked
        // for is never exactly what was reported.
        Eventually.check(() -> onView(theBikesCard(R.id.device_location))
                .perform(scrollTo())
                .check(matches(withText(WHERE_THAT_IS))));
        TestPace.afterAStep();
    }

    /**
     * <b>And its history, which no test could reach before this one.</b>
     *
     * <p>{@code HistoryViewActivity} takes its Apple session from
     * {@code PythonAppleService.getInstance()}, which only the map sets up, so it is only
     * launchable after a map has run. It also fetches by a different route - {@code getReports}
     * generates the day's keys itself rather than asking for the latest location - which is a
     * second bridge path, and the one the user meets when they go looking for a day the app did
     * not fetch on its own.
     */
    private void openTheBikesHistory() {
        // **Scrolled to first, and it is not a formality.** The tag cards live in a
        // HorizontalScrollView, and with two tags on the account the one this wants is off the
        // side of the screen - so `click()` fails its own ninety-percent-visible constraint with
        // a message about constraints rather than about position, and the screenshot shows a
        // perfectly healthy map with the *other* tag's card on it.
        Eventually.check(() -> onView(theBikesCard(R.id.device_history_button_container))
                .perform(scrollTo()));
        onView(theBikesCard(R.id.device_history_button_container)).perform(click());
        TestPace.afterAStep();

        // **Not asserted as displayed, because it correctly is not.** The list lives in a bottom
        // sheet that opens at its peek height, so the rows sit below the fold until somebody
        // drags it up - `isDisplayed` is false and Espresso is right to say so. An earlier
        // version checked exactly that and passed on timing luck for several runs before the
        // extra work of the merged history fetch shifted it enough to fail honestly.
        //
        // What is wanted here is that the rows exist, which the caption below says without
        // needing anything to be on screen. Dragging the sheet open is covered by
        // TheHistoryScreenDrawsTheDayTest, where it is the point rather than a step.

        // **Asked out loud, because the screen renders both the same way.** A day that was
        // searched and came back empty and a day that was never searched at all both arrive as
        // an empty list, and the second is the bug.
        Eventually.check(() -> assertTrue("the day was never actually searched",
                this.appleDouble.callAttr("howManyRangedFetches").toInt() > 0));

        // Exactly, not at least. The day's reports are fetched remotely and merged with what
        // the map's own fetch already stored, so a broken de-duplication shows up here as
        // double - which is what the user would see.
        Eventually.check(() -> onView(withId(R.id.history_datapoints_text))
                .check(matches(withText(getInstrumentation().getTargetContext()
                        .getString(R.string.x_data_points, REPORTS_TODAY)))));
        TestPace.afterAStep();
    }

    // ------------------------------------------------------------------ helpers

    /** A control inside the Bike's card specifically - there are two cards on the screen. */
    private static org.hamcrest.Matcher<android.view.View> theBikesCard(final int controlId) {
        return allOf(withId(controlId), isDescendantOfA(allOf(
                withId(R.id.tag_item_container),
                hasDescendant(withText(containsString(FakeICloudService.A_BIKE.getName()))))));
    }

    private FakeMapProvider.PlacedMarker markerFor(final String beaconId) {
        final List<FakeMapProvider.PlacedMarker> found = this.map.markers().stream()
                .filter(placed -> beaconId.equals(placed.marker.getId()))
                .collect(Collectors.toList());

        assertEquals("expected exactly one pin for " + beaconId, 1, found.size());
        return found.get(0);
    }

    private void unlockWithADevicePasscode() {
        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();

        Eventually.check(() -> onView(withText(containsString(
                FakeICloudService.AN_IPHONE.getSerial()))).perform(click()));
        TestPace.afterAStep();

        Eventually.check(() -> onView(withId(R.id.icloud_passcode_input))
                .check(matches(isDisplayed())));
        onView(withId(R.id.icloud_passcode_input)).perform(replaceText(DEVICE_PASSCODE));
        TestPace.afterAStep();

        Eventually.perform("unlock", () -> this.icloud.timesCalled("unlock") > 0,
                () -> onView(withId(R.id.icloud_primary_button)).perform(click()));
        TestPace.afterAStep();
    }

    /**
     * Clear any stored session, and wait until it stays cleared.
     *
     * <p>The same insistence as {@code AppleLoginFlowTest}: storing a session is the last step of
     * a sign-in and runs on a background scheduler, so a single look can see an empty store that
     * is about to be written to.
     */
    private static void signEverybodyOut() {
        final UserAuthRepository auth = new UserAuthRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil());

        int consecutivelyEmpty = 0;

        for (int attempt = 0; attempt < 40 && consecutivelyEmpty < 3; attempt++) {
            if (auth.getUserAuth().blockingFirst().isEmpty()) {
                consecutivelyEmpty++;
            } else {
                consecutivelyEmpty = 0;
                auth.clearUser().blockingAwait();
            }
            SystemClock.sleep(50);
        }

        if (consecutivelyEmpty < 3) {
            throw new IllegalStateException("a stored session kept coming back");
        }
    }
}
