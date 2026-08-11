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
import static org.hamcrest.Matchers.not;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
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
import dev.wander.android.opentagviewer.python.FakeAppleAuthService;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.service.web.FakeAnisetteServerTester;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Signing in on a device where Anisette cannot be produced locally.
 *
 * <p>This is the path that decides whether somebody can get in at all when the normal route
 * fails, and it is the one nobody exercises: local Anisette works on virtually every device,
 * so the fall-back only runs for the people already having a bad day. It is also the path with
 * no way back - Settings is behind the sign-in, so a sign-in screen that hides the server
 * field strands exactly the people who need it.
 *
 * <p>Everything is real except the three things that reach the network: Apple, Anisette, and
 * the server-reachability check. That last one matters especially here - without it, a test of
 * the fall-back would depend on a stranger's Anisette server being up, which is the situation
 * the fall-back exists to survive.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class AppleLoginFallbackFlowTest {

    private static final String EMAIL = "someone@example.com";
    private static final String PASSWORD = "hunter2";

    private FakeAppleAuthService apple;
    private FakeAnisetteServerTester server;
    private ActivityScenario<AppleLoginActivity> scenario;

    private DeviceStateGuard deviceState;

    @Before
    public void breakLocalAnisette() {
        // Captured first: everything below overwrites what is on the device, which matters on
        // one somebody actually uses.
        this.deviceState = DeviceStateGuard.capture(getInstrumentation().getTargetContext());

        signEverybodyOut();
        forgetAnyChosenServer();

        this.apple = FakeAppleAuthService.signsInImmediately();
        this.server = FakeAnisetteServerTester.thatIsUp();

        AppDependencies.replaceAuthService(this.apple);
        AppDependencies.replaceServerTester(this.server);
        AppDependencies.replaceAnisette(settings ->
                FakeAnisetteSource.unavailable("Unable to resolve host apps.mzstatic.com"));

        Intents.init();
        intending(hasComponent(MapsActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_OK, null));
    }

    @After
    public void putTheRealOnesBack() {
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
     * The field comes back, and says why.
     *
     * <p>It is hidden by default now, which is the whole point of producing Anisette here. A
     * device that cannot has to be given it back unasked - there is no Settings to go to.
     */
    @Test
    public void aDeviceThatCannotManageOnItsOwnGetsTheServerFieldBack() {
        launch();

        eventually(() -> onView(withId(R.id.anisetteRemoteSection))
                .perform(scrollTo()).check(matches(isDisplayed())));
        onView(withId(R.id.anisetteLoginFallbackReason)).perform(scrollTo()).check(matches(isDisplayed()));
    }

    /** And having got it back, they can still sign in through it. */
    @Test
    public void theyCanStillSignInThroughAServer() {
        launch();

        eventually(() -> onView(withId(R.id.go_to_maininfo))
                .perform(scrollTo()).check(matches(isDisplayed())));
        onView(withId(R.id.go_to_maininfo)).perform(scrollTo(), click());
        TestPace.afterAStep();

        signIn();

        eventually(() -> intended(hasComponent(MapsActivity.class.getName())));
    }

    /**
     * The server URL reaches Python, because now it is the only thing that will work.
     *
     * <p>On this path it is not a fallback that goes unused - it is what the sign-in runs on.
     */
    @Test
    public void theConfiguredServerIsWhatTheSignInActuallyUses() {
        launch();

        eventually(() -> onView(withId(R.id.go_to_maininfo))
                .perform(scrollTo()).check(matches(isDisplayed())));
        onView(withId(R.id.go_to_maininfo)).perform(scrollTo(), click());
        signIn();

        eventually(() -> assertNotNull(apple.serverUrlUsed()));
        assertTrue("a real URL, not a placeholder", apple.serverUrlUsed().startsWith("http"));
        assertTrue("the server should have been checked before letting anybody past",
                server.calls() > 0);
    }

    /**
     * The other half of the same decision: a device that manages on its own is never taken
     * through any of this.
     *
     * <p>Worth asserting from the same place, because the two are one condition. Making the
     * failing case work by simply always showing the field would pass every test above and
     * quietly undo the feature.
     */
    @Test
    public void aWorkingDeviceIsNeverTakenThroughTheServerStep() {
        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());

        launch();

        // Straight to the account form - no welcome step, no server to choose.
        eventually(() -> onView(withId(R.id.email_or_phone_input_field))
                .perform(scrollTo()).check(matches(isDisplayed())));

        assertEquals("nothing should have asked a server anything", 0, server.calls());

        // Asserted as "there but not shown", not as "absent": the view is in the layout
        // either way, so looking for its absence would pass for the wrong reason - or, as it
        // did here, fail for one.
        onView(withId(R.id.anisetteRemoteSection)).check(matches(not(isDisplayed())));
    }

    /**
     * A sign-in that went through a server is recorded as one.
     *
     * <p>The mirror of the local case, and written for the same reason: the stored mode decides
     * what Settings shows and whether the upgrade prompt applies. Recording this as local would
     * tell somebody their sign-in never leaves the device, when it just did.
     */
    @Test
    public void aSignInThroughAServerIsRecordedAsRemote() {
        launch();
        eventually(() -> onView(withId(R.id.go_to_maininfo)).perform(click()));
        signIn();

        eventually(() -> assertEquals(UserSettings.ANISETTE_REMOTE,
                storedSettings().getAnisetteMode()));
    }

    // ------------------------------------------------------------------------------------

    private static UserSettings storedSettings() {
        return new UserSettingsRepository(UserSettingsDataStore.getInstance(
                getInstrumentation().getTargetContext())).getUserSettings();
    }

    private void launch() {
        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);
        TestPace.afterAStep();
    }

    private void signIn() {
        eventually(() -> onView(withId(R.id.email_or_phone_input_field))
                .perform(scrollTo()).check(matches(isDisplayed())));

        onView(withId(R.id.email_or_phone_input_field)).perform(scrollTo(), replaceText(EMAIL));
        TestPace.afterAStep();

        onView(withId(R.id.password_input_field))
                .perform(scrollTo(), replaceText(PASSWORD), closeSoftKeyboard());
        TestPace.afterAStep();

        // Retried: closeSoftKeyboard() asks the IME to go away and does not wait for it, so a
        // click issued immediately can land while the keyboard is still over the button.
        eventually(() -> onView(withId(R.id.login_button_main)).perform(scrollTo(), click()));
        TestPace.afterAStep();
    }

    /** See {@code AppleLoginFlowTest.eventually} - the same reason applies here. */
    private static void eventually(final Runnable assertion) {
        Throwable last = null;

        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                assertion.run();
                return;
            } catch (final AssertionError | RuntimeException error) {
                last = error;
                getInstrumentation().waitForIdleSync();
                SystemClock.sleep(100);
            }
        }

        if (last instanceof RuntimeException) {
            throw (RuntimeException) last;
        }
        throw (AssertionError) last;
    }


    /**
     * Start each test as a fresh install, with no Anisette server chosen.
     *
     * <p>Not housekeeping - it decides which screen appears. With a server already stored and
     * reachable, the sign-in screen correctly skips the whole welcome step and goes straight
     * to the account form, so a test that saved one (they all do, on the way past) would leave
     * the next one looking for a page the app was right not to show.
     */
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
