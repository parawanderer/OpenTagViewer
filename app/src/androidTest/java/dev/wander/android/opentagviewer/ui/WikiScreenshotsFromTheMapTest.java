package dev.wander.android.opentagviewer.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;

import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.util.Set;

import java.nio.charset.StandardCharsets;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.MapsActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.SettingsActivity;
import dev.wander.android.opentagviewer.Shot;
import dev.wander.android.opentagviewer.ui.maps.MapProviderFactory;
import dev.wander.android.opentagviewer.ui.maps.AMapWithTagsOnIt;

/**
 * The screens that only exist once somebody is signed in, for the wiki.
 *
 * <p><b>Split from {@code WikiScreenshotsTest} because reaching a drawn map is eight steps.</b>
 * A stored session, the Python double, a substituted map provider, a geocoder, beacons with
 * usable accessory JSON, somewhere to draw them, and a refresh policy that does not skip the
 * first fetch - {@code AMapWithTagsOnIt} arranges all of it, and the screens in the other class
 * need none of it.
 *
 * <p><b>The map is the real Google one.</b> The fixture substitutes a fake because the managed
 * device has no Play Services; these are captured on a windowed emulator that has them and with
 * a real Maps key, so the drawing is handed back and the page looks like the app does. Only the
 * map is real - the session, the tags, the locations and the address are all fabricated.
 *
 * <p><b>So this class cannot run on the managed device</b>, and does not pretend to: without
 * Play Services the map never initialises and the captures would be of a blank screen.
 *
 * <p>Everything on screen is fabricated. See the sibling class for the rest of the caveats.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class WikiScreenshotsFromTheMapTest {

    /** How long to let Google fetch tiles before photographing the map. */
    private static final long TILES_TAKE_A_MOMENT = 6000L;

    /**
     * The captures with a map actually in shot - everything else here photographs Settings.
     *
     * <p>Named rather than annotated because the decision is made in {@code @Before}, before any
     * test body runs, and a name is the only thing available there. The cost of getting it wrong
     * is visible either way: forget to add a map capture and it is photographed over a blank grey
     * rectangle.
     */
    private static final Set<String> NEEDS_A_DRAWN_MAP =
            Set.of("atheoverflowMenuOnTheMap", "gtheexportLogsButton");

    @Rule
    public final TestName testName = new TestName();

    private final AMapWithTagsOnIt theMap = new AMapWithTagsOnIt();

    @Before
    public void openTheMap() {
        OnlyWhenCapturing.wasAskedFor();

        Intents.init();

        // **Everything except the two screens this actually walks into.** The test this was
        // copied from stubs everything-but-Maps because it only asserts intents and never
        // navigates - so Settings was answered at the door and never launched, and three
        // captures failed on a page that could not appear. Stubbing MapsActivity itself is the
        // other half of the same trap: the map is then never allowed to exist, which presents as
        // a six-minute hang rather than a failure. See EveryButtonOnTheMapGoesSomewhereTest.
        intending(allOf(
                not(hasComponent(MapsActivity.class.getName())),
                not(hasComponent(SettingsActivity.class.getName()))))
                .respondWith(new ActivityResult(Activity.RESULT_OK, null));

        this.theMap.seed("Bike", "Keys");

        // **The real Google map, but only for the two captures that photograph one.** The fixture
        // substitutes FakeMapProvider because the managed device has no Play Services; these are
        // captured on a windowed emulator that has them, and the wiki page these replace shows a
        // real map. Everything else the fixture arranges is still needed.
        //
        // The pin sits at 48.85837, 2.29448 - the Eiffel Tower - which is recognisable, obviously
        // nobody's home, and paired with a geocoder that answers "A made-up street, in a made-up
        // town" so no real address reaches a public page.
        //
        // **It is handed back per test because it costs about fifty seconds.** Measured: the same
        // fixture with the fake provider runs a test in 1.3-2.8s, and with the real Google map
        // every test in this class landed between 52.4s and 55.0s - including the two that only
        // open a dialog. Bringing the Maps SDK up and tearing it down again dominates everything
        // else, and five of these seven screens are Settings, a full-screen activity with no map
        // behind it at all. Paying for it there bought a blank sixth of a screenshot nobody sees.
        final boolean weArePhotographingTheMap =
                NEEDS_A_DRAWN_MAP.contains(this.testName.getMethodName());
        if (weArePhotographingTheMap) {
            MapProviderFactory.reset();
        }
        givenSomebodyIsSignedIn();

        this.theMap.open();

        Eventually.check(() -> onView(withId(R.id.button_more_settings))
                .check(matches(isDisplayed())));

        // **Waiting for the chrome is not waiting for the map.** The overflow button is drawn
        // immediately; Google's tiles arrive over the network seconds later, and a screenshot
        // taken in between is of a blank grey rectangle that looks like a broken map rather than
        // a loading one. There is no callback to hang this on from out here, so it is a wait -
        // the one place in this repo where sleeping is the honest answer, because what is being
        // waited for is a third party's tile download and not anything the app decides.
        if (weArePhotographingTheMap) {
            android.os.SystemClock.sleep(TILES_TAKE_A_MOMENT);
        }
    }

    @After
    public void putEverythingBack() {
        this.theMap.putItBack();
        Intents.release();
    }

    /**
     * A session that carries a name and an address, so Settings has something to show.
     *
     * <p><b>The fixture stores a deliberately unrestorable blob</b> - the tests that use it care
     * that a session <i>exists</i>, not what is in it, and one that cannot be restored is the
     * cheapest way to have one. Settings then finds no account details and leaves "Logged In As"
     * blank above a lone Logout button, which is correct behaviour and a poor advertisement.
     *
     * <p>Written afterwards rather than by changing the fixture: every other test using it wants
     * the unrestorable one, and a session that suddenly looks real could send one of them down a
     * path it was never meant to take.
     *
     * <p>Invented, obviously. The name and the address belong to nobody.
     */
    private static void givenSomebodyIsSignedIn() {
        new UserAuthRepository(
                UserAuthDataStore.getInstance(
                        getInstrumentation().getTargetContext().getApplicationContext()),
                new AppCryptographyUtil())
                .storeUserAuth(("{\"account\":{\"info\":{"
                        + "\"account_name\":\"jamie.lang@example.com\","
                        + "\"first_name\":\"Jamie\","
                        + "\"last_name\":\"Lang\"}}}").getBytes(StandardCharsets.UTF_8))
                .blockingAwait();
    }

    private void openTheOverflow() {
        Eventually.check(() -> onView(withId(R.id.button_more_settings))
                .check(matches(isDisplayed())));
        onView(withId(R.id.button_more_settings)).perform(click());
    }

    /**
     * The menu the wiki's "change your Anisette server" page starts from.
     *
     * <p>Its current image predates this UI: a different device, an older navigation bar and a
     * clock from another year. Step one of three.
     */
    @Test
    public void atheoverflowMenuOnTheMap() {
        this.openTheOverflow();

        Eventually.check(() -> onView(withText(R.string.settings)).inRoot(isPlatformPopup())
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("map-1-overflow-menu");
    }

    /** Step two: the settings page itself. */
    @Test
    public void bthesettingsPage() {
        this.openTheOverflow();
        onView(withText(R.string.settings)).inRoot(isPlatformPopup()).perform(click());

        Eventually.check(() -> onView(withId(R.id.setting_app_anisette_server))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("settings-1-page");
    }

    /** Step three: the Anisette server dialog, warning that changing it costs a sign-in. */
    @Test
    public void ctheanisetteServerDialog() {
        this.openSettingsAndTap(R.id.setting_app_anisette_server);

        // **Waited for by id, not by its label.** This matched the text "Anisette Server URL" and
        // stopped matching when the dialog grew a local/remote mode dropdown: that string is now
        // the field's helper text rather than a TextView of its own. The id survived the redesign
        // and the wording will not.
        Eventually.check(() -> onView(withId(R.id.anisetteServerUrl)).inRoot(isDialog())
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("settings-2-anisette-server");
    }

    /** Choosing between Google Maps and AMap. */
    @Test
    public void dthemapProviderChoice() {
        this.openSettingsAndTap(R.id.setting_app_map_provider);

        Eventually.check(() -> onView(withText(R.string.map_provider_amap)).inRoot(isDialog())
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("settings-3-map-provider");
    }

    /**
     * And the key AMap needs, which this app deliberately does not ship.
     *
     * <p>AMap issues keys per developer account, bound to a package name and a signing
     * fingerprint, and expects the holder to be the app's operator - so users supply their own.
     * Picking AMap without one must not save, which is why this screen exists at all. See
     * AGENTS.md rule 5.
     */
    @Test
    public void etheamapKeyPrompt() {
        this.openSettingsAndTap(R.id.setting_app_map_provider);

        Eventually.check(() -> onView(withText(R.string.map_provider_amap)).inRoot(isDialog())
                .check(matches(isDisplayed())));

        // **Select, then Accept.** Tapping the row only moves the radio; the key prompt comes
        // after the choice is confirmed. Without the second tap this waited for a dialog that
        // was never going to open, which is what killed the run.
        onView(withText(R.string.map_provider_amap)).inRoot(isDialog()).perform(click());
        onView(withText(R.string.accept)).inRoot(isDialog()).perform(click());

        Eventually.check(() -> onView(withText(R.string.amap_api_key)).inRoot(isDialog())
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("settings-4-amap-key");
    }

    /**
     * Turning debug data on, which is where getting a log out begins.
     *
     * <p><b>Two screens because it is two places.</b> The switch is in Settings and the button it
     * reveals is on the map, so anybody following the wiki looks for the button first, does not
     * find it, and concludes the feature is missing. It is deliberately hidden - most people never
     * need a log, and an Export Logs button in everyone's menu is an invitation to hand out a file
     * full of their own coordinates. See the sibling capture for the second half.
     */
    @Test
    public void ftheenableDebugDataSwitch() {
        this.openTheOverflow();
        onView(withText(R.string.settings)).inRoot(isPlatformPopup()).perform(click());

        // **Scroll first, then assert it is on screen** - not the other way round. The switch is
        // the last row of the Settings ScrollView, so isDisplayed() is false until something
        // brings it into view: Espresso reported it VISIBLE with a 832x126 size and an empty
        // global visible rect, which reads as a contradiction until you notice it means
        // "laid out, off screen". scrollTo() only needs the view to exist.
        Eventually.check(() -> onView(withId(R.id.settings_app_debug_data_enabled))
                .perform(scrollTo()));
        onView(withId(R.id.settings_app_debug_data_enabled)).perform(click());

        // Photographed switched on, since that is the state the next step needs. The fixture's
        // DeviceStateGuard puts the setting back afterwards.
        Eventually.check(() -> onView(withId(R.id.settings_app_debug_data_enabled))
                .check(matches(isChecked())));
        Shot.ofTheScreen("logs-1-enable-debug-data");
    }

    /**
     * And the Export Logs item, which only exists once that switch is on.
     *
     * <p>Walks the whole way rather than writing the setting directly: the menu is rebuilt from
     * the stored value on every press, so going through Settings and back is also the assertion
     * that the switch reaches it. Writing the preference behind the app's back would capture the
     * same picture while proving nothing about the path a reader is being told to follow.
     *
     * <p>The system file picker it opens belongs to another app and cannot be photographed from
     * here - same limitation as the import flow.
     */
    @Test
    public void gtheexportLogsButton() {
        this.openTheOverflow();
        onView(withText(R.string.settings)).inRoot(isPlatformPopup()).perform(click());

        // **Scroll first, then assert it is on screen** - not the other way round. The switch is
        // the last row of the Settings ScrollView, so isDisplayed() is false until something
        // brings it into view: Espresso reported it VISIBLE with a 832x126 size and an empty
        // global visible rect, which reads as a contradiction until you notice it means
        // "laid out, off screen". scrollTo() only needs the view to exist.
        Eventually.check(() -> onView(withId(R.id.settings_app_debug_data_enabled))
                .perform(scrollTo()));
        onView(withId(R.id.settings_app_debug_data_enabled)).perform(click());
        Eventually.check(() -> onView(withId(R.id.settings_app_debug_data_enabled))
                .check(matches(isChecked())));

        pressBack();

        this.openTheOverflow();
        Eventually.check(() -> onView(withText(R.string.export_logs)).inRoot(isPlatformPopup())
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("logs-2-export-logs");
    }

    private void openSettingsAndTap(final int rowId) {
        this.openTheOverflow();
        onView(withText(R.string.settings)).inRoot(isPlatformPopup()).perform(click());

        Eventually.check(() -> onView(withId(rowId)).check(matches(isDisplayed())));
        onView(withId(rowId)).perform(scrollTo(), click());
    }
}
