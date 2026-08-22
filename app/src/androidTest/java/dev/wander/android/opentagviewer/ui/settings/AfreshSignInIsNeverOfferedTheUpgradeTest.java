package dev.wander.android.opentagviewer.ui.settings;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

import dev.wander.android.opentagviewer.AppleLoginActivity;
import dev.wander.android.opentagviewer.DeviceStateGuard;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.MapsActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.FakeAppleAuthService;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Somebody signing in today is never invited to migrate something they never had.
 *
 * <p><b>The Anisette upgrade offer is meant for upgraders and nobody else.</b> It fires on
 * "signed in, but never chose an Anisette mode", which is a good proxy for "signed in before
 * that setting existed" - better than a version check, because an install old enough to need
 * the offer has no stored version to compare against either.
 *
 * <p><b>The proxy only holds because signing in records a mode</b>, and that is the part with no
 * test. {@code AnisetteUpgradeDialogTest} covers the decision thoroughly - who is asked, who is
 * not, that nobody is asked twice - but every one of those cases writes the settings by hand and
 * then asks the function. None of them signs in. So the claim a new user actually depends on
 * rests entirely on {@code recordHowThisSessionWasEstablished} being reached, and nothing would
 * notice if it stopped being.
 *
 * <p><b>It is not a hypothetical failure.</b> A test fixture here produced exactly that state by
 * writing a session without a mode, and the dialog duly opened over the map. In the app, that is
 * a brand-new user being told their phone "can now do this itself" about a middleman they never
 * used - and being offered a sign-out to fix it.
 *
 * <p>There is also a live way to reach it: {@code recordHowThisSessionWasEstablished} returns
 * early when the local Anisette source is null, leaving the mode unset. Nothing can produce that
 * today, and this test is what says so out loud.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class AfreshSignInIsNeverOfferedTheUpgradeTest {

    private static final String EMAIL = "fresh-install@example.com";
    private static final String PASSWORD = "hunter2";
    private static final String CODE = "123456";

    private Context context;
    private FakeAppleAuthService apple;
    private DeviceStateGuard deviceState;
    private ActivityScenario<AppleLoginActivity> scenario;

    @Before
    public void startFromAnInstallNobodyHasEverSignedInOn() {
        this.context = getInstrumentation().getTargetContext();
        this.deviceState = DeviceStateGuard.capture(this.context);

        signEverybodyOut();
        this.forgetAnyAnisetteChoice();

        this.apple = FakeAppleAuthService.wantsTwoFactor();
        AppDependencies.replaceAuthService(this.apple);
        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());

        // The map is answered at the door: it needs Play Services the managed device has not
        // got, and what is under test finished before it would have started.
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

        signEverybodyOut();
        this.deviceState.restore();
    }

    /**
     * <b>Signing in writes down how the session was established.</b>
     *
     * <p>The link the whole thing hangs on. Asserted on the stored settings rather than on the
     * absence of a dialog, because "no dialog appeared" is also what a broken screen looks like.
     */
    @Test
    public void signingInRecordsWhichAnisetteEstablishedTheSession() {
        this.signIn();

        Eventually.check(() -> assertNotNull(
                "signing in did not record an Anisette mode, so the next time the map opens this"
                        + " brand-new user will be offered the upgrade meant for people coming"
                        + " from an older version",
                this.storedSettings().getAnisetteMode()));
    }

    /** <b>And so the offer is not due, which is the thing the user would actually see.</b> */
    @Test
    public void andisThereforeNotOfferedTheUpgrade() {
        this.signIn();

        Eventually.check(() -> assertNotNull(this.storedSettings().getAnisetteMode()));

        assertFalse("a fresh sign-in is due the upgrade offer, which is only for sessions made"
                        + " before the Anisette setting existed",
                this.storedSettings().shouldOfferLocalAnisette(true));
    }

    /**
     * <b>And the case it <i>is</i> for still qualifies.</b>
     *
     * <p>The mirror, and it is what stops the test above being satisfiable by never offering the
     * upgrade to anyone. A session with no recorded mode is exactly what an install from before
     * the setting looks like, and that one is still asked.
     */
    @Test
    public void butasessionFromBeforeTheSettingStillIs() {
        this.signIn();
        Eventually.check(() -> assertNotNull(this.storedSettings().getAnisetteMode()));

        // Wound back to what an older version left behind: a working session, no mode.
        final UserSettings asAnUpgraderHasIt = this.storedSettings();
        asAnUpgraderHasIt.setAnisetteMode(null);
        asAnUpgraderHasIt.setAnisetteUpgradeOffered(null);

        assertTrue("somebody who signed in under an older version should still be offered the"
                        + " move to local Anisette",
                asAnUpgraderHasIt.shouldOfferLocalAnisette(true));
    }

    // ------------------------------------------------------------------ the sign-in

    private void signIn() {
        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);

        onView(withId(R.id.email_or_phone_input_field)).perform(replaceText(EMAIL));
        onView(withId(R.id.password_input_field))
                .perform(replaceText(PASSWORD), closeSoftKeyboard());

        Eventually.perform("the sign in button", () -> this.apple.timesCalled("login") > 0,
                () -> onView(withId(R.id.login_button_main)).perform(click()));

        Eventually.check(() -> onView(withText(this.context.getString(
                R.string.auth_by_sms_to_x, FakeAppleAuthService.PHONE_ONE))).perform(click()));

        Eventually.check(() -> onView(withId(R.id.twofa_sent_info_text))
                .check(matches(isDisplayed())));
        onView(withId(R.id.twofactorauth_textinput_1)).perform(replaceText(CODE));

        // The sign-in is finished when it hands over to the map, and not before - the settings
        // are written on the way there.
        Eventually.check(() -> intended(hasComponent(MapsActivity.class.getName())));
    }

    private UserSettings storedSettings() {
        return new UserSettingsRepository(UserSettingsDataStore.getInstance(this.context))
                .getUserSettings();
    }

    private void forgetAnyAnisetteChoice() {
        final UserSettingsRepository settings = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.context));

        final UserSettings blank = settings.getUserSettings();
        blank.setAnisetteMode(null);
        blank.setAnisetteUpgradeOffered(null);
        settings.storeUserSettings(blank).blockingAwait();
    }

    /**
     * Clear any stored session, and wait until it stays cleared.
     *
     * <p>Storing a session is the last step of a sign-in and runs on a background scheduler, so
     * a single look can see an empty store that is about to be written to.
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
