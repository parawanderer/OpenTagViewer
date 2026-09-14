package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.FakeAppleAuthService;
import dev.wander.android.opentagviewer.service.web.FakeAnisetteServerTester;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Escaping to a remote Anisette server when Apple refuses a sign-in this device could produce
 * Anisette for perfectly well.
 *
 * <p>This is the gap #206 exposed: the server field hides itself when local Anisette works, and
 * Settings is behind the sign-in - so a device that produces Anisette fine, and is then refused
 * by Apple with a 429, has nothing to change and nowhere to change it. The only lever left is the
 * machine identity, which a server would present differently, and this is the one path to it.
 *
 * <p>Everything is real except the three things that reach the network. The Anisette fake mirrors
 * the real source in the one way that matters here: it produces locally until remote mode is
 * chosen, then steps aside - so the sign-in is recorded as remote exactly as a real one would be.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ForceRemoteAnisetteAfterDeclineTest {

    private static final String EMAIL = "someone@example.com";
    private static final String PASSWORD = "hunter2";

    private FakeAppleAuthService apple;
    private FakeAnisetteServerTester server;
    private ActivityScenario<AppleLoginActivity> scenario;

    private DeviceStateGuard deviceState;

    @Before
    public void setUp() {
        this.deviceState = DeviceStateGuard.capture(getInstrumentation().getTargetContext());

        signEverybodyOut();
        forgetAnyChosenServer();

        // Refused once, then served: the transient 429 in the report.
        this.apple = FakeAppleAuthService.declinesOnceThenSignsIn();
        this.server = FakeAnisetteServerTester.thatIsUp();

        AppDependencies.replaceAuthService(this.apple);
        AppDependencies.replaceServerTester(this.server);
        // Mirrors the real LocalAnisette: local while local mode is in force, out of the way once
        // remote is chosen. Without this the fake would report every sign-in as local and the
        // stored-mode assertion would test the fake, not the app.
        AppDependencies.replaceAnisette(settings -> settings.usesLocalAnisette(false)
                ? FakeAnisetteSource.ready()
                : FakeAnisetteSource.unavailable("a remote Anisette server is selected in Settings"));

        Intents.init();
        intending(hasComponent(MapsActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_OK, null));
    }

    @After
    public void tearDown() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();

        getInstrumentation().waitForIdleSync();
        signEverybodyOut();
        this.deviceState.restore();
    }

    /**
     * The button is offered only for Apple's own refusal.
     *
     * <p>A working device goes straight to the account form (no welcome step), so a refusal there
     * is the 429, and the escape hatch appears on it.
     */
    @Test
    public void aRefusedSignInOffersTheRemoteServer() {
        launch();
        signInExpectingAnotherLoginCall();

        Eventually.check(() -> onView(withId(R.id.login_error_try_remote_anisette))
                .perform(scrollTo()).check(matches(isDisplayed())));
    }

    /** Taking it brings back the server field that READY had hidden. */
    @Test
    public void takingItRevealsTheServerField() {
        launch();
        signInExpectingAnotherLoginCall();

        Eventually.check(() -> onView(withId(R.id.login_error_try_remote_anisette))
                .perform(scrollTo()).check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.login_error_try_remote_anisette))
                .perform(scrollTo(), click()));
        TestPace.afterAStep();

        Eventually.check(() -> onView(withId(R.id.anisetteRemoteSection))
                .perform(scrollTo()).check(matches(isDisplayed())));
    }

    /**
     * And the retry through the server actually goes through the server, and is recorded as remote.
     *
     * <p>The whole point: the first attempt used local Anisette and was refused; the second must
     * present the server's identity instead, or nothing has changed. Asserted on what reached
     * Python and what was stored, not merely on landing on the map.
     */
    @Test
    public void theRetryGoesThroughTheServerAndIsRecordedAsRemote() {
        launch();
        signInExpectingAnotherLoginCall();

        Eventually.check(() -> onView(withId(R.id.login_error_try_remote_anisette))
                .perform(scrollTo(), click()));
        TestPace.afterAStep();

        Eventually.check(() -> onView(withId(R.id.go_to_maininfo))
                .perform(scrollTo()).check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.go_to_maininfo)).perform(scrollTo(), click()));
        TestPace.afterAStep();

        signInExpectingAnotherLoginCall();

        Eventually.check(() -> intended(hasComponent(MapsActivity.class.getName())));
        Eventually.check(() -> assertNotNull("the server URL must reach Python", apple.serverUrlUsed()));
        assertTrue("a real URL, not a placeholder", apple.serverUrlUsed().startsWith("http"));
        assertTrue("the server should have been checked before letting anybody past",
                server.calls() > 0);
        assertEquals("a sign-in through a server is stored as remote",
                UserSettings.ANISETTE_REMOTE, storedSettings().getAnisetteMode());
    }

    /**
     * A wrong password does not offer it: a different Anisette identity would not fix a bad
     * credential, and saying otherwise sends people to change a setting that cannot help.
     */
    @Test
    public void aWrongPasswordDoesNotOfferTheRemoteServer() {
        AppDependencies.replaceAuthService(
                this.apple = FakeAppleAuthService.rejectsTheSignIn("Bad password"));

        launch();
        signInExpectingAnotherLoginCall();

        Eventually.check(() -> onView(withId(R.id.login_error_message_text))
                .perform(scrollTo()).check(matches(isDisplayed())));
        onView(withId(R.id.login_error_try_remote_anisette)).check(matches(not(isDisplayed())));
    }

    // ------------------------------------------------------------------------------------

    private void launch() {
        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);
        TestPace.afterAStep();
    }

    /**
     * Fill the account form and sign in, waiting for the login count to actually rise.
     *
     * <p>The count, not the button's exception: a tap can miss the still-closing keyboard and
     * need retrying, while a tap that lands tears the screen down and throws from the same call.
     * Only "did another login happen" tells them apart - and it must be *another*, because this
     * runs a second time in the same test, when one login has already been made.
     */
    private void signInExpectingAnotherLoginCall() {
        final long before = apple.timesCalled("login");

        Eventually.check(() -> onView(withId(R.id.email_or_phone_input_field))
                .perform(scrollTo()).check(matches(isDisplayed())));

        onView(withId(R.id.email_or_phone_input_field)).perform(scrollTo(), replaceText(EMAIL));
        onView(withId(R.id.password_input_field))
                .perform(scrollTo(), replaceText(PASSWORD), closeSoftKeyboard());
        TestPace.afterAStep();

        Eventually.perform("the sign in button", () -> apple.timesCalled("login") > before,
                () -> onView(withId(R.id.login_button_main)).perform(scrollTo(), click()));
        TestPace.afterAStep();
    }

    private static UserSettings storedSettings() {
        return new UserSettingsRepository(UserSettingsDataStore.getInstance(
                getInstrumentation().getTargetContext())).getUserSettings();
    }

    private static void forgetAnyChosenServer() {
        new UserSettingsRepository(
                UserSettingsDataStore.getInstance(getInstrumentation().getTargetContext()))
                .storeUserSettings(UserSettings.builder().build())
                .blockingAwait();
    }

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
