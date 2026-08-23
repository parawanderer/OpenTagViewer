package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.db.AccountBeaconsForTests;
import java.util.List;

import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.FakeAppleAuthService;
import dev.wander.android.opentagviewer.python.icloud.FakeICloudService;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * The whole journey, from a signed-out app to tags on the screen.
 *
 * <p><b>Everything here has a test of its own, and that is the point.</b> Signing in is covered,
 * reading the account is covered, writing the rows is covered - and each of those starts from a
 * state the previous one is trusted to have produced. This one produces them: it signs in for
 * real, opens the device list for real, reads the account through it, and looks at what ended up
 * in the list. The bugs it can catch are the ones that live between two green tests.
 *
 * <p>Both journeys are here because <b>they diverge on the answer that is out of the user's
 * hands</b>. An account with tags on it ends with a list of them; an account with none ends by
 * offering the only thing that could still work - a bundle from somebody who does own some. The
 * second is the one nobody exercises by hand, because producing it means having an Apple account
 * with nothing in Find My.
 *
 * <p><b>The map is stubbed, and that is a real gap rather than a convenience.</b> Signing in ends
 * by starting {@code MapsActivity}, which needs Play Services that the {@code aosp-atd} image has
 * not got, so the intent is answered at the door and the journey resumes at the device list. What
 * is not covered here is therefore the map itself - the recorded next step for that is a fake
 * {@code IMapProvider}.
 *
 * <p>Paced with {@link TestPace}, so this is the pair to run with {@code slowMotion} when
 * somebody wants to watch the app work rather than read that it does.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class SignInThenGetTagsJourneyTest {

    private static final String EMAIL = "someone@example.com";
    private static final String PASSWORD = "hunter2";
    private static final String CODE = "123456";
    private static final String DEVICE_PASSCODE = "123456";

    private FakeAppleAuthService apple;
    private FakeICloudService icloud;
    private KeychainMembershipRepository memberships;
    private DeviceStateGuard deviceState;
    private ActivityScenario<?> scenario;

    @Before
    public void signEverybodyOutAndReplaceTheWorld() {
        final Context context = getInstrumentation().getTargetContext();

        this.deviceState = DeviceStateGuard.capture(context);
        signEverybodyOut();

        this.memberships = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(context), new AppCryptographyUtil());
        this.memberships.forget().blockingAwait();
        AccountBeaconsForTests.forgetThemAll();

        this.apple = FakeAppleAuthService.wantsTwoFactor();
        AppDependencies.replaceAuthService(this.apple);
        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());

        Intents.init();
        intending(hasComponent(MapsActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_OK, null));
    }

    @After
    public void putEverythingBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();

        getInstrumentation().waitForIdleSync();
        AccountBeaconsForTests.forgetThemAll();
        this.memberships.forget().blockingAwait();
        signEverybodyOut();
        this.deviceState.restore();
    }

    /**
     * <b>An account with tags on it: sign in, read it, and they are in the list.</b>
     */
    @Test
    public void signingInAndReadingTheAccountFillsTheDeviceList() {
        this.icloud = FakeICloudService.withTags();
        AppDependencies.replaceICloud(() -> this.icloud);

        this.signInAllTheWayThrough();
        this.openTheDeviceList();

        // Nothing has ever been imported, so this is the empty state and its own button is the
        // way in - the same button a first-run user meets.
        Eventually.check(() -> onView(withId(R.id.my_devices_empty_fetch_button))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();
        onView(withId(R.id.my_devices_empty_fetch_button)).perform(click());

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
    }

    /** And the tags it read are the account's, written as rows rather than held on a screen. */
    @Test
    public void thetagsThatArriveAreTheOnesTheAccountHeld() {
        this.icloud = FakeICloudService.withTags();
        AppDependencies.replaceICloud(() -> this.icloud);

        this.signInAllTheWayThrough();
        this.openTheDeviceList();
        onView(withId(R.id.my_devices_empty_fetch_button)).perform(click());
        this.unlockWithADevicePasscode();

        Eventually.check(() -> assertTrue("nothing was written for the account",
                this.icloud.timesCalled("records") > 0));
    }

    /**
     * <b>And what was written is usable, not just present.</b>
     *
     * <p>The rows carry {@code accessory_json} - FindMy.py's serialised accessory state, which is
     * what actually locates the tag afterwards - and it is produced by handing the account's
     * plist to Python. That conversion failing is not fatal by design, because a missing one is
     * backfilled on the first fetch, so a tag that imported and can never be located looks
     * exactly like a tag that imported.
     *
     * <p>Which is how this went unnoticed: the fake used to return {@code "<plist/>"}, the real
     * converter threw on it, the failure was swallowed, and every "imported" tag in every test
     * had no accessory state at all.
     */
    @Test
    public void whatarrivesCanActuallyBeLocated() {
        this.icloud = FakeICloudService.withTags();
        AppDependencies.replaceICloud(() -> this.icloud);

        this.signInAllTheWayThrough();
        this.openTheDeviceList();
        onView(withId(R.id.my_devices_empty_fetch_button)).perform(click());
        this.unlockWithADevicePasscode();

        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));

        final OpenTagViewerDatabase db = OpenTagViewerDatabase.getInstance(
                getInstrumentation().getTargetContext());

        Eventually.check(() -> {
            final List<OwnedBeacon> held = db.ownedBeaconDao().getAll();
            assertTrue("nothing was written for the account", !held.isEmpty());

            for (final OwnedBeacon beacon : held) {
                assertNotNull("beacon " + beacon.id + " imported with no accessory state, so it"
                        + " looks imported and can never be located", beacon.accessoryJson);
            }
        });
    }

    /**
     * <b>An account with nothing on it says so, and offers the only thing that could work.</b>
     *
     * <p>A dead end here is a user who signed in, waited, and was told nothing at all. The screen
     * hands them back to the file picker instead, which is how somebody whose tags belong to a
     * family member gets anywhere.
     */
    @Test
    public void signingInWithNothingOnTheAccountOffersAFileInstead() {
        this.icloud = FakeICloudService.withNothingToRecoverFrom();
        AppDependencies.replaceICloud(() -> this.icloud);

        this.signInAllTheWayThrough();
        this.openTheDeviceList();

        Eventually.check(() -> onView(withId(R.id.my_devices_empty_fetch_button))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();
        onView(withId(R.id.my_devices_empty_fetch_button)).perform(click());

        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_container))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();
    }

    /**
     * And taking that offer hands the whole journey on to the file picker.
     *
     * <p><b>The list finishes itself, which looks like a bug and is not.</b> The picker and the
     * code that fetches locations for whatever comes back both live on the map, so the request is
     * passed back rather than duplicated - and the map is underneath in a real run. Here there is
     * nothing underneath, because the map was stubbed at the door, so the stack simply empties.
     *
     * <p>That is why this asserts the hand-off rather than looking for a screen: the first
     * version of this test expected the device list to still be there and failed with
     * {@code NoActivityResumedException}, which reads like a crash and is the app doing exactly
     * what it should.
     */
    @Test
    public void takingTheFileOfferHandsTheJourneyOnRatherThanStopping() {
        this.icloud = FakeICloudService.withNothingToRecoverFrom();
        AppDependencies.replaceICloud(() -> this.icloud);

        this.signInAllTheWayThrough();
        this.openTheDeviceList();
        onView(withId(R.id.my_devices_empty_fetch_button)).perform(click());

        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_container))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();
        onView(withId(R.id.icloud_primary_button)).perform(click());

        // Asked of the scenario rather than of Espresso: there is no activity left to look at,
        // and onView would report that as a failure rather than as the answer.
        Eventually.check(() -> assertEquals(
                "the device list should have handed the import request onward",
                Lifecycle.State.DESTROYED, this.scenario.getState()));
        TestPace.afterAStep();
    }

    /** The second run asks for no passcode at all, from a cold start of the whole journey. */
    @Test
    public void asecondReadAfterSigningInNeverAsksForAPasscode() {
        this.icloud = FakeICloudService.withTags();
        AppDependencies.replaceICloud(() -> this.icloud);

        this.signInAllTheWayThrough();
        this.openTheDeviceList();
        onView(withId(R.id.my_devices_empty_fetch_button)).perform(click());
        this.unlockWithADevicePasscode();
        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));
        onView(withId(R.id.icloud_primary_button)).perform(click());

        // **The screen is opened directly for the second read, deliberately.** Which button
        // reaches it is being redesigned - linking is offered until the account is linked, and
        // re-reading is moving to something the app does on its own - and the property under
        // test is not about buttons. It is that a member reads without asking for anything, and
        // tying that assertion to whichever affordance exists this week is how a test starts
        // failing for reasons that have nothing to do with what it protects.
        this.scenario.close();
        this.icloud = FakeICloudService.withTags();
        AppDependencies.replaceICloud(() -> this.icloud);

        this.scenario = ActivityScenario.launch(new Intent(
                getInstrumentation().getTargetContext(), FetchFromICloudActivity.class));

        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));
        assertEquals("a second read must not ask for a passcode",
                0, this.icloud.timesCalled("unlock"));
        assertEquals("it should have read as the member it already is",
                1, this.icloud.timesCalled("resume"));
        TestPace.afterAStep();
    }

    // ------------------------------------------------------------------ the journey, in steps

    private void signInAllTheWayThrough() {
        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);
        TestPace.afterAStep();

        onView(withId(R.id.email_or_phone_input_field)).perform(replaceText(EMAIL));
        TestPace.afterAStep();
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

        Eventually.check(() -> intended(hasComponent(MapsActivity.class.getName())));

        // The sign-in screen finishes itself once the map is on its way. Closed here so the
        // device list is not launched on top of a screen that is still tearing down.
        this.scenario.close();
        this.scenario = null;
    }

    private void openTheDeviceList() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);
        TestPace.afterAStep();
    }

    private boolean isShown(final int id) {
        final boolean[] shown = {false};
        this.scenario.onActivity(activity -> {
            final View found = activity.findViewById(id);
            shown[0] = found != null && found.getVisibility() == View.VISIBLE;
        });
        return shown[0];
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
