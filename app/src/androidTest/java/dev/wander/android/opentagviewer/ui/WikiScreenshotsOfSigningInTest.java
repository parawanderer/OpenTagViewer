package dev.wander.android.opentagviewer.ui;

import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
// The row reads "SMS (+44 ******1234)", so an exact match on the number finds nothing. Matching
// the number inside the label keeps this working whether or not the method is named around it.
import static org.hamcrest.Matchers.containsString;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.AppleLoginActivity;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.MapsActivity;
import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.Shot;
import dev.wander.android.opentagviewer.TestPace;
import dev.wander.android.opentagviewer.db.AccountBeaconsForTests;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.python.FakeAppleAuthService;

/**
 * Signing in, and getting tags in from the device list — for the wiki.
 *
 * <p>Replaces images taken on a different phone, in another year, with an older navigation bar.
 * Mixing those with freshly captured ones on the same page is the thing this is for.
 *
 * <p><b>Nothing here talks to Apple.</b> {@code FakeAppleAuthService.wantsTwoFactor()} produces
 * the second-factor screens on demand, which no real account will do to order, and
 * {@code FakeAnisetteSource.ready()} keeps the screen from downloading Apple's ADI libraries.
 * The address, the password and the phone numbers are invented.
 *
 * <p>The one screen not here is the system file picker in the import flow: it belongs to another
 * app in another process, so Espresso can neither drive it nor photograph it. That stays a manual
 * shot.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class WikiScreenshotsOfSigningInTest {

    private static final String EMAIL = "someone@example.com";
    private static final String PASSWORD = "hunter2";

    private FakeAppleAuthService apple;
    private ActivityScenario<?> scenario;

    @Before
    public void replaceApple() {
        OnlyWhenCapturing.wasAskedFor();

        AccountBeaconsForTests.forgetThemAll();
        signOutFirst();

        this.apple = FakeAppleAuthService.wantsTwoFactor();
        AppDependencies.replaceAuthService(this.apple);
        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());

        Intents.init();
        // The map is where a finished sign-in lands, and it needs Play Services. Stubbed for the
        // same reason AppleLoginFlowTest stubs it - reaching it is the claim, not what it draws.
        intending(hasComponent(MapsActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_OK, null));
    }

    @After
    public void putItBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();
        getInstrumentation().waitForIdleSync();
        AccountBeaconsForTests.forgetThemAll();
    }

    /** 2a: the first screen the app ever shows. */
    @Test
    public void athewelcomeScreen() {
        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);

        Eventually.check(() -> onView(withId(R.id.login_button_main))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("login-1-welcome");
    }

    /** 2b: choosing where Apple should send the code. */
    @Test
    public void bthesecondFactorChoice() {
        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);
        this.signIn();

        Eventually.check(() -> onView(withText(containsString(FakeAppleAuthService.PHONE_ONE)))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("login-2-2fa-methods");
    }

    /**
     * 3c: the six boxes.
     *
     * <p>The wiki's current version of this has Android's SMS-autofill chip above it, offering the
     * code. That comes from a real message arriving, which a fake auth service cannot produce -
     * see the note in CONTRIBUTING about sending one with {@code adb emu sms send} while this
     * screen is up, which is how to get that shot rather than pasting the chip onto this one.
     */
    @Test
    public void cthecodeEntry() {
        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);
        this.signIn();

        Eventually.check(() -> onView(withText(containsString(FakeAppleAuthService.PHONE_ONE)))
                .check(matches(isDisplayed())));
        onView(withText(containsString(FakeAppleAuthService.PHONE_ONE))).perform(click());

        Eventually.check(() -> onView(withId(R.id.twofa_sent_info_text))
                .check(matches(isDisplayed())));

        // Long enough for a message sent from the host to arrive and be offered, and harmless
        // otherwise: TestPace does nothing at all unless slowMotion was asked for.
        TestPace.afterAStep();
        Shot.ofTheScreen("login-3-2fa-code");
    }

    /**
     * The other way in: My Devices, for somebody who has no tags yet.
     *
     * <p>The wiki documents importing through the map's overflow menu. This is the same two
     * actions from the list, which is where an empty install actually starts.
     */
    @Test
    public void dtheemptyDeviceList() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);

        Eventually.check(() -> onView(withText(R.string.icloud_import_from_file))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("import-1-my-devices-empty");
    }

    /**
     * Make sure nobody is signed in, rather than hoping.
     *
     * <p><b>Every test here fails with {@code NoActivityResumedException} if a session exists</b>,
     * and the reason is nowhere near the failure: AppleLoginActivity finishes itself and starts
     * the map when it finds one, the map is stubbed out here, and Espresso then reports that no
     * activity is resumed - which reads as a launch that did not happen rather than a screen that
     * declined to appear.
     *
     * <p>It happened because a sibling capture class signs somebody in and puts it back in its
     * {@code @After}, and three of its tests failed in a way that skipped the restore. Fixed on
     * this side as well as that one: a class whose whole subject is the signed-out screens should
     * establish signed-out itself, not inherit it from whatever ran before.
     */
    private static void signOutFirst() {
        new UserAuthRepository(
                UserAuthDataStore.getInstance(
                        getInstrumentation().getTargetContext().getApplicationContext()),
                new AppCryptographyUtil())
                .clearUser().blockingAwait();
    }

    private void signIn() {
        onView(withId(R.id.email_or_phone_input_field)).perform(replaceText(EMAIL));
        onView(withId(R.id.password_input_field))
                .perform(replaceText(PASSWORD), closeSoftKeyboard());

        // Retried until Apple was actually asked, not until nothing throws - the keyboard may
        // still be over the button. See AGENTS.md on Eventually.perform.
        Eventually.perform("the sign in button", () -> this.apple.timesCalled("login") > 0,
                () -> onView(withId(R.id.login_button_main)).perform(click()));
    }
}
