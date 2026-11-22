package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.espresso.intent.VerificationModes.times;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Context;
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
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.onboarding.OnboardingRecord;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.FakeAppleAuthService;
import dev.wander.android.opentagviewer.update.UpdateSettings;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * A first sign-in on a fresh install, all the way through: the sign-in screen, then Setup,
 * then the map. The two screens are the real ones; Apple is {@link FakeAppleAuthService} and the
 * map is stubbed, because the managed device has no Play Services.
 *
 * <p>{@link OnboardingStepsTest} drives Setup on its own. This is the hand-off it cannot see:
 * that a sign-in actually lands on Setup, and that a second sign-in on the same install does not.
 *
 * <p>To watch it, run one test in slow motion on an emulator with a window (AGENTS.md, "Showing
 * a UI test to a person"); every step calls {@link TestPace#afterAStep()}.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class SignInThenSetupJourneyTest {

    private final Context context = getInstrumentation().getTargetContext();
    private FakeAppleAuthService apple;
    private ActivityScenario<AppleLoginActivity> scenario;
    private DeviceStateGuard deviceState;

    @Before
    public void aFreshInstall() {
        this.deviceState = DeviceStateGuard.capture(this.context);
        this.signEverybodyOut();
        new OnboardingRecord(this.context).forget();
        this.context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().clear().commit();
        this.memberships().forget().blockingAwait();

        this.apple = FakeAppleAuthService.signsInImmediately();
        AppDependencies.replaceAuthService(this.apple);
        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());

        Intents.init();
        intending(hasComponent(MapsActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_OK, null));
        intending(hasComponent(FetchFromICloudActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_CANCELED, null));
    }

    @After
    public void putItBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();
        getInstrumentation().waitForIdleSync();
        this.signEverybodyOut();
        // The test runner marks it done for every other class; leave it that way.
        new OnboardingRecord(this.context).markShown();
        this.context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().clear().commit();
        this.deviceState.restore();
    }

    @Test
    public void aFirstSignInGoesThroughSetupToTheMap() {
        this.signIn();

        // Setup, step 1: update checks. The runner granted every permission, so the nearby
        // step has nothing to ask and is left out: two steps.
        this.seeTitle(R.string.onboarding_updates_title);
        intended(hasComponent(OnboardingActivity.class.getName()));
        this.tap(R.id.onboarding_primary_button);

        // Step 2, last: get your tags. "Connect" hands the map the job of starting iCloud.
        this.seeTitle(R.string.onboarding_tags_title);
        this.tap(R.id.onboarding_primary_button);

        Eventually.check(() -> intended(allOf(hasComponent(MapsActivity.class.getName()),
                hasExtra(MapsActivity.EXTRA_CONNECT_ICLOUD, true))));
        assertEquals(Boolean.TRUE, new UpdateSettings(this.context).enabled());
        assertFalse(OnboardingActivity.isDue(this.context));
    }

    @Test
    public void signingInAgainOnTheSameInstallGoesStraightToTheMap() {
        new OnboardingRecord(this.context).markShown();

        this.signIn();

        Eventually.check(() -> intended(hasComponent(MapsActivity.class.getName())));
        intended(hasComponent(OnboardingActivity.class.getName()), times(0));
    }

    private void signIn() {
        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);
        TestPace.afterAStep();
        onView(withId(R.id.email_or_phone_input_field)).perform(replaceText("someone@example.com"));
        onView(withId(R.id.password_input_field))
                .perform(replaceText("hunter2"), closeSoftKeyboard());
        TestPace.afterAStep();
        // See Eventually.perform: the tap may finish the sign-in and tear this screen down.
        Eventually.perform("the sign in button", () -> this.apple.timesCalled("login") > 0,
                () -> onView(withId(R.id.login_button_main)).perform(click()));
        TestPace.afterAStep();
    }

    private void seeTitle(final int title) {
        Eventually.check(() -> onView(withId(R.id.onboarding_title))
                .check(matches(withText(title))));
        TestPace.afterAStep();
    }

    private void tap(final int buttonId) {
        Eventually.check(() -> onView(withId(buttonId)).check(matches(isDisplayed())));
        onView(withId(buttonId)).perform(click());
        TestPace.afterAStep();
    }

    private KeychainMembershipRepository memberships() {
        return new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(this.context), new AppCryptographyUtil());
    }

    /** As AppleLoginFlowTest does it: a cleared session can reappear for a moment. */
    private void signEverybodyOut() {
        final UserAuthRepository auth = new UserAuthRepository(
                UserAuthDataStore.getInstance(this.context), new AppCryptographyUtil());
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
    }
}
