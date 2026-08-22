package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertFalse;
import static org.hamcrest.Matchers.not;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Being signed out by Apple rather than by choice.
 *
 * <p>A session can stop working for reasons the user did nothing to cause: Apple invalidating
 * it, a password change, or the machine identity moving because local Anisette fell back to a
 * server. Before this, all of them arrived as a toast asserting that the FindMy library had
 * been updated - true of one migration and wrong every other time - or, in the case that
 * actually matters, as nothing at all.
 *
 * <p><b>The reason this is worth a screen and not a log line.</b> Somebody dropped on a login
 * form with no explanation reasonably concludes the app has lost everything, and a reasonable
 * next step is to go and tidy up whatever unfamiliar entry is sitting in their Apple device
 * list - which is the single action that makes it worse, because that entry is this app.
 *
 * <p>The activity is driven directly with the extras rather than through a failing restore.
 * Producing the real thing needs Apple to reject a session, which cannot be arranged; what can
 * be asserted is that the screen honours what it is sent, and {@code MapsActivityTest} covers
 * nothing here, so the contract between them is stated in {@code MapsActivity} and in
 * {@code AppleLoginActivity}'s constants.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class SessionExpiredLoginTest {

    private static final String EMAIL = "someone@example.com";

    private ActivityScenario<AppleLoginActivity> scenario;
    private DeviceStateGuard deviceState;

    @Before
    public void startSignedOut() {
        this.deviceState = DeviceStateGuard.capture(getInstrumentation().getTargetContext());

        signEverybodyOut();
        forgetAnyChosenServer();

        // Ready, so the screen goes straight to the account form and the dialog is the only
        // thing in front of it. An unavailable source would add the welcome step and make
        // "is the email field filled in" a question about a different page.
        AppDependencies.replaceAuthService(FakeAppleAuthService.signsInImmediately());
        AppDependencies.replaceServerTester(FakeAnisetteServerTester.thatIsUp());
        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());
    }

    @After
    public void putTheRealOnesBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        AppDependencies.reset();

        getInstrumentation().waitForIdleSync();
        signEverybodyOut();
        this.deviceState.restore();
    }

    /**
     * The explanation appears, and says the thing that stops somebody making it worse.
     *
     * <p>Asserted on the words, not on "a dialog is up". What matters is that it says the tags
     * are still here - a dialog that only said "sign in again" would leave the same fear.
     */
    @Test
    public void somebodySignedOutByAppleIsToldWhatHappened() {
        launchAfterExpiry(EMAIL);

        final Context context = getInstrumentation().getTargetContext();
        Eventually.check(() -> onView(withText(context.getString(R.string.session_expired_title)))
                .check(matches(isDisplayed())));
        onView(withText(context.getString(R.string.session_expired_message)))
                .check(matches(isDisplayed()));
    }

    /**
     * And once they dismiss it, their address is already in the field.
     *
     * <p>Read from the account being discarded, which is why {@code MapsActivity} reads it
     * before clearing rather than after.
     *
     * <p>Dismissed first because a dialog owns the focused window, so Espresso resolves views
     * against the dialog's hierarchy and not the activity's - looking for the field while it is
     * up fails with {@code NoMatchingViewException} even though the field is there. It is also
     * the order a person meets it in.
     */
    @Test
    public void theirAddressIsAlreadyFilledIn() {
        launchAfterExpiry(EMAIL);
        dismissTheExplanation();

        Eventually.check(() -> onView(withId(R.id.email_or_phone_input_field))
                .perform(scrollTo()).check(matches(withText(EMAIL))));
    }

    /**
     * Dismissing it leaves them on the sign-in form, not somewhere else.
     *
     * <p>The dialog is an explanation, not a decision - there is nothing to accept or decline,
     * and no other screen to go to.
     */
    @Test
    public void dismissingItLeavesThemOnTheSignInForm() {
        launchAfterExpiry(EMAIL);
        dismissTheExplanation();

        onView(withId(R.id.email_or_phone_input_field))
                .perform(scrollTo()).check(matches(isDisplayed()));
        onView(withId(R.id.password_input_field)).perform(scrollTo()).check(matches(isDisplayed()));
    }

    /**
     * An ordinary first run says none of this.
     *
     * <p>The half that makes the other three mean something. A dialog shown unconditionally
     * would pass every test above and tell every new user their session had expired.
     */
    @Test
    public void anordinaryFirstRunIsNotToldAnything() {
        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);
        TestPace.afterAStep();

        Eventually.check(() -> onView(withId(R.id.email_or_phone_input_field))
                .perform(scrollTo()).check(matches(isDisplayed())));

        final Context context = getInstrumentation().getTargetContext();
        onView(withId(R.id.email_or_phone_input_field))
                .perform(scrollTo()).check(matches(not(withText(EMAIL))));
        assertFalse("a first run has no expired session to explain",
                isShowing(context.getString(R.string.session_expired_title)));
    }

    /**
     * An address alone prefills without announcing anything.
     *
     * <p>The two extras are independent on purpose: an address can be known when nothing has
     * expired, and something can expire when the address cannot be read.
     */
    @Test
    public void anaddressWithoutAnExpiryPrefillsQuietly() {
        final Intent intent = new Intent(
                getInstrumentation().getTargetContext(), AppleLoginActivity.class);
        intent.putExtra(AppleLoginActivity.EXTRA_PREFILL_EMAIL, EMAIL);
        this.scenario = ActivityScenario.launch(intent);
        TestPace.afterAStep();

        final Context context = getInstrumentation().getTargetContext();
        Eventually.check(() -> onView(withId(R.id.email_or_phone_input_field))
                .perform(scrollTo()).check(matches(withText(EMAIL))));
        assertFalse("nothing expired, so nothing to explain",
                isShowing(context.getString(R.string.session_expired_title)));
    }

    /**
     * An expiry with no address still explains itself.
     *
     * <p>{@code MapsActivity} reads the address defensively - it is recovering from an account
     * that has already failed to restore - so "expired, and we could not read who you are" is
     * a state that can genuinely arrive, and it must not silently show nothing.
     */
    @Test
    public void anexpiryWithoutAnAddressStillExplainsItself() {
        launchAfterExpiry(null);

        final Context context = getInstrumentation().getTargetContext();
        Eventually.check(() -> onView(withText(context.getString(R.string.session_expired_title)))
                .check(matches(isDisplayed())));
    }

    // ------------------------------------------------------------------------------------

    /**
     * Wait for the explanation, then dismiss it.
     *
     * <p>{@code perform} rather than a bare click: the dialog animates in, so a tap can land
     * before it is touchable and needs retrying - but a tap that works removes the very view
     * being clicked, which Espresso reports from the same call. "Has the title gone" separates
     * the two; the exception cannot.
     */
    private void dismissTheExplanation() {
        final Context context = getInstrumentation().getTargetContext();

        Eventually.check(() -> onView(withText(context.getString(R.string.session_expired_title)))
                .check(matches(isDisplayed())));
        Eventually.perform("the dismiss button",
                () -> !isShowing(context.getString(R.string.session_expired_title)),
                () -> onView(withText(context.getString(R.string.ok))).perform(click()));
        TestPace.afterAStep();
    }

    /**
     * <b>A prefilled address counts, so typing only the password is enough to sign in.</b>
     *
     * <p><b>It was not.</b> The button is enabled from two flags kept by text watchers, and the
     * watchers are attached when this page is shown - which happens <i>after</i> {@code onCreate}
     * prefills the address. So nothing ever told the screen the email was valid, and no amount of
     * typing in the password could turn Sign in on: a filled-in address next to a dead button,
     * with nothing to explain it and no way forward except deleting an address that was already
     * correct.
     *
     * <p>Worth guarding twice over, because this is the exact screen a session failure sends
     * people to - so the recovery path ended at a button that could not be pressed.
     */
    @Test
    public void aprefilledAddressAndATypedPasswordIsEnoughToSignIn() {
        launchAfterExpiry(EMAIL);
        this.dismissTheExplanation();

        Eventually.check(() -> onView(withId(R.id.login_button_main))
                .perform(scrollTo())
                .check(matches(not(isEnabled()))));

        onView(withId(R.id.password_input_field)).perform(scrollTo(), replaceText("hunter2"));
        TestPace.afterAStep();

        Eventually.check(() -> onView(withId(R.id.login_button_main))
                .perform(scrollTo())
                .check(matches(isEnabled())));
    }

    /**
     * And clearing the prefilled address turns it off again.
     *
     * <p>Here so the fix cannot be "assume the email is fine whenever one was passed in", which
     * would pass the test above and enable Sign in over an address the user had just deleted.
     */
    @Test
    public void emptyingTheprefilledAddressDisablesItAgain() {
        launchAfterExpiry(EMAIL);
        this.dismissTheExplanation();

        onView(withId(R.id.password_input_field)).perform(scrollTo(), replaceText("hunter2"));
        Eventually.check(() -> onView(withId(R.id.login_button_main))
                .perform(scrollTo()).check(matches(isEnabled())));

        onView(withId(R.id.email_or_phone_input_field)).perform(scrollTo(), replaceText(""));

        Eventually.check(() -> onView(withId(R.id.login_button_main))
                .perform(scrollTo())
                .check(matches(not(isEnabled()))));
    }

    private void launchAfterExpiry(final String email) {
        final Intent intent = new Intent(
                getInstrumentation().getTargetContext(), AppleLoginActivity.class);
        intent.putExtra(AppleLoginActivity.EXTRA_SESSION_EXPIRED, true);
        if (email != null) {
            intent.putExtra(AppleLoginActivity.EXTRA_PREFILL_EMAIL, email);
        }

        this.scenario = ActivityScenario.launch(intent);
        TestPace.afterAStep();
    }

    /**
     * Whether some text is on screen, as a question rather than an assertion.
     *
     * <p>Needed because two of these assert an absence, and an absence cannot be an expected
     * exception: {@code NoMatchingViewException} is also what a mistyped matcher throws.
     */
    private static boolean isShowing(final String text) {
        try {
            onView(withText(text)).check(matches(isDisplayed()));
            return true;
        } catch (final NoMatchingViewException | AssertionError e) {
            return false;
        }
    }

    private static void forgetAnyChosenServer() {
        new UserSettingsRepository(
                UserSettingsDataStore.getInstance(getInstrumentation().getTargetContext()))
                .storeUserSettings(UserSettings.builder()
                        .anisetteMode(UserSettings.ANISETTE_LOCAL)
                        .build())
                .blockingAwait();
    }

    /**
     * A stored session would send this screen straight to the map in {@code onCreate}, so every
     * test here depends on there being none.
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
