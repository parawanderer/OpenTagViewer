package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.python.FakeAppleAuthService;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.PythonAuthService.AuthMethodPhone;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Signing in, driven end to end with Apple replaced.
 *
 * <p>This is the flow with the most ways to go wrong and, until now, no coverage at all: four
 * pages, page changes fired from three different places, state carried across them in a view
 * model, and a six-box code entry where each box rewrites its neighbours. None of it could be
 * exercised before, because every step ran Python against Apple with real credentials and sent
 * a real code to a real phone - so the only test was a person doing it by hand, and only for
 * the path they happened to take.
 *
 * <p>What is real here is everything that has ever broken: the activity, its layouts, its view
 * model, the page transitions, the code-entry behaviour, and the encrypted store the session
 * ends up in. Only Apple and Anisette are substituted, through
 * {@link AppDependencies}.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class AppleLoginFlowTest {

    private static final String EMAIL = "someone@example.com";
    private static final String PASSWORD = "hunter2";
    private static final String CODE = "123456";

    private FakeAppleAuthService apple;
    private ActivityScenario<AppleLoginActivity> scenario;

    private DeviceStateGuard deviceState;

    @Before
    public void replaceAppleAndSignEverybodyOut() {
        // Before signing anybody out, so their session can be put back afterwards.
        this.deviceState = DeviceStateGuard.capture(getInstrumentation().getTargetContext());

        signEverybodyOut();

        this.apple = FakeAppleAuthService.wantsTwoFactor();
        AppDependencies.replaceAuthService(this.apple);
        // Ready, so the screen never downloads Apple's ADI libraries or asks a server about
        // anything. The states where it is not ready have their own tests.
        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());

        Intents.init();
        // The map is the end of this flow and the point of reaching it, but it needs Play
        // Services, which the managed test device does not have. Stub it: arriving there is
        // what these tests assert, not what it does next.
        intending(hasComponent(MapsActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_OK, null));
    }

    @After
    public void putTheRealOnesBack() {
        // The screen goes down before the stubbing does. Released first, a sign-in still
        // finishing would have its intent escape the stub and really start MapsActivity -
        // which needs Play Services the test device has not got, and which then sits on top
        // of whatever the next test launches.
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();

        // Cleared on the way out as well as on the way in. Most of these tests finish a
        // sign-in, which stores a session, and the next test's screen would then redirect
        // straight to the map before it could be driven anywhere.
        getInstrumentation().waitForIdleSync();
        signEverybodyOut();
        this.deviceState.restore();
    }

    /**
     * Clear any stored session, and wait until it stays cleared.
     *
     * <p>Storing a session is the last step of a sign-in and runs on a background scheduler,
     * so a write started by the test just finished can still be in flight. Checking once
     * could therefore see an empty store that is about to be written to, so this insists on
     * seeing it empty a few times running before believing it.
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

    /**
     * The whole thing, in the order somebody actually does it.
     *
     * <p>Deliberately one long test rather than four: the bugs in a flow like this live in the
     * transitions, and a test that starts each page from a fresh activity never crosses one.
     */
    @Test
    public void signingInWithATextedCodeReachesTheMap() {
        launch();

        signIn();

        // Apple asked for a second factor, so the choice of how to receive it comes next.
        eventually(() -> onView(withText(R.string.two_factor_authentication))
                .check(matches(isDisplayed())));
        chooseTheTextTo(FakeAppleAuthService.PHONE_TWO);

        eventually(() -> onView(withId(R.id.twofa_sent_info_text)).check(matches(isDisplayed())));
        pasteTheCode(CODE);

        eventually(() -> assertEquals("the code has to reach Apple exactly as typed",
                CODE, apple.submittedCode()));
        eventually(() -> intended(hasComponent(MapsActivity.class.getName())));
    }

    /**
     * A code pasted into the first box fills all six and submits itself.
     *
     * <p>The six boxes are not six independent fields: each one rewrites its neighbours as it
     * changes, and the submit fires off a counter that the cascade advances. Pasting is how
     * people actually enter a code they were just sent, and it is the path that exercises all
     * of that at once.
     */
    @Test
    public void pastingTheCodeIntoTheFirstBoxFillsThemAllAndSubmits() {
        launch();
        signIn();
        chooseTheTextTo(FakeAppleAuthService.PHONE_ONE);

        pasteTheCode(CODE);

        eventually(() -> assertEquals(CODE, apple.submittedCode()));
        assertEquals("submitted once, not once per box", 1, apple.timesCalled("submitCode"));
    }

    /** The code must go to the method the user picked, not simply the first one offered. */
    @Test
    public void theCodeGoesToTheNumberThatWasChosen() {
        launch();
        signIn();
        chooseTheTextTo(FakeAppleAuthService.PHONE_TWO);

        pasteTheCode(CODE);

        eventually(() -> assertNotNull(apple.codeSubmittedAgainst()));
        assertEquals("the code belongs to the number Apple texted",
                FakeAppleAuthService.PHONE_TWO,
                ((AuthMethodPhone) apple.codeSubmittedAgainst()).getPhoneNumber());
    }

    /** Somebody already trusted skips the second factor entirely. */
    @Test
    public void anAccountThatNeedsNoSecondFactorGoesStraightToTheMap() {
        this.apple = FakeAppleAuthService.signsInImmediately();
        AppDependencies.replaceAuthService(this.apple);

        launch();
        signIn();

        eventually(() -> intended(hasComponent(MapsActivity.class.getName())));
        assertEquals("nothing should have asked for a code",
                0, apple.timesCalled("requestCode"));
    }

    /**
     * A rejected password says so and lets them try again.
     *
     * <p>The screen hides the whole form while signing in, so failing to put it back leaves
     * somebody looking at a spinner-less blank page with no way forward.
     */
    @Test
    public void awrongPasswordLeavesThemAbleToTryAgain() {
        this.apple = FakeAppleAuthService.rejectsTheSignIn("Bad password");
        AppDependencies.replaceAuthService(this.apple);

        launch();
        signIn();

        eventually(() -> onView(withId(R.id.login_error_container)).check(matches(isDisplayed())));
        onView(withId(R.id.login_maininfo_container)).check(matches(isDisplayed()));
        onView(withId(R.id.login_button_main)).check(matches(isDisplayed()));
    }

    /** A rejected code says so, and gives the boxes back rather than stranding them. */
    @Test
    public void aWrongCodeIsReportedAndTheBoxesComeBack() {
        this.apple = FakeAppleAuthService.wantsTwoFactor().thatRejectsTheCode("Wrong code");
        AppDependencies.replaceAuthService(this.apple);

        launch();
        signIn();
        chooseTheTextTo(FakeAppleAuthService.PHONE_ONE);
        pasteTheCode("000000");

        eventually(() -> onView(withId(R.id.verification_code_error_msg_container))
                .check(matches(isDisplayed())));
        onView(withId(R.id.login_2fa_container)).check(matches(isDisplayed()));
    }

    /**
     * An account with no phone numbers offers only the trusted-device route.
     *
     * <p>The phone buttons are built at runtime from what Apple returned, so an account with
     * none has to leave that list empty rather than showing a button that texts nobody.
     */
    @Test
    public void anAccountWithNoPhoneNumbersOffersOnlyTheTrustedDevice() {
        this.apple = FakeAppleAuthService.wantsTwoFactorFromATrustedDeviceOnly();
        AppDependencies.replaceAuthService(this.apple);

        launch();
        signIn();

        eventually(() -> onView(withId(R.id.twofactorauth_choice_trusted_device))
                .check(matches(isDisplayed())));
    }

    /**
     * Signing in uses Anisette from this device, and still passes a server to fall back to.
     *
     * <p>Both halves matter. Dropping the source silently returns everybody to relaying their
     * sign-in through somebody else's server; dropping the URL is a null reaching Python, on a
     * screen where the server step may never have been visited.
     */
    @Test
    public void theSignInUsesLocalAnisetteAndStillCarriesAFallbackServer() {
        final var fakeAnisette = FakeAnisetteSource.ready();
        AppDependencies.replaceAnisette(settings -> fakeAnisette);

        launch();
        signIn();

        eventually(() -> assertSame("local Anisette has to reach the sign-in",
                fakeAnisette, apple.anisetteUsed()));
        assertNotNull("Python needs a fallback URL even when it is not used",
                apple.serverUrlUsed());
        assertTrue(apple.serverUrlUsed().startsWith("http"));
    }

    // ------------------------------------------------------------------------------------
    // Steps, named for what somebody doing this would say they were doing.
    // ------------------------------------------------------------------------------------

    private void launch() {
        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);
        this.scenario.onActivity(activity -> assertFalse(
                "the sign-in screen is already finishing - somebody was still signed in",
                activity.isFinishing()));
        TestPace.afterAStep();
    }

    /**
     * Retry an assertion until it holds, or give up.
     *
     * <p>Espresso waits for the main thread to go idle, and for nothing else. Every step of
     * signing in runs on an RxJava scheduler and hops back, so an assertion made the instant
     * a click returns is asking about work that has not started yet. This is a real property
     * of the screen rather than a test defect - the alternative, an IdlingResource, would mean
     * threading test-only bookkeeping through production Rx chains.
     */
    private static void eventually(final Runnable assertion) {
        AssertionError last = null;

        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                assertion.run();
                return;
            } catch (final AssertionError error) {
                last = error;
                getInstrumentation().waitForIdleSync();
                SystemClock.sleep(100);
            }
        }

        throw last;
    }

    private void signIn() {
        onView(withId(R.id.email_or_phone_input_field)).perform(replaceText(EMAIL));
        TestPace.afterAStep();

        onView(withId(R.id.password_input_field))
                .perform(replaceText(PASSWORD), closeSoftKeyboard());
        TestPace.afterAStep();

        onView(withId(R.id.login_button_main)).perform(click());
        TestPace.afterAStep();
    }

    private void chooseTheTextTo(final String phoneNumber) {
        eventually(() -> onView(withText(
                getInstrumentation().getTargetContext()
                        .getString(R.string.auth_by_sms_to_x, phoneNumber)))
                .perform(click()));
        TestPace.afterAStep();
    }

    /**
     * Types the whole code into the first box, which is what pasting one does.
     *
     * <p>{@code replaceText} rather than {@code typeText}: the boxes move focus as they fill,
     * and Espresso's per-character typing fails when the field it started on stops being
     * focused. Setting the text in one go is also the case people actually hit.
     */
    private void pasteTheCode(final String code) {
        // One action, not two. Filling the last box submits the code, and a correct one signs
        // in and finishes this screen - so a second action in the same perform() (closing the
        // keyboard, say) runs when there is no activity left and fails with "No activities
        // found", pointing at the test rather than at the six lines that actually ran.
        onView(withId(R.id.twofactorauth_textinput_1)).perform(replaceText(code));
        TestPace.afterAStep();
    }
}
