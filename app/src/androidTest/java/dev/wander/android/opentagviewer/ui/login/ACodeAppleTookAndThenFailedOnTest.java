package dev.wander.android.opentagviewer.ui.login;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;

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
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.db.AccountBeaconsForTests;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.FakeAppleAuthService;

/**
 * The 503 that arrives <i>after</i> Apple has accepted the code.
 *
 * <p><b>The submit is two calls, and only the second failed.</b> FindMy.py checks the code - which
 * passes - and then runs a Grand Slam re-authentication, which can 503 on its own. By then Apple
 * has consumed the code.
 *
 * <p><b>So the screen's old behaviour was the one thing guaranteed to fail.</b> It cleared the box
 * and asked for the code again; the code is spent, so the next attempt returns
 * {@code InvalidCredentialsError} - which reads as a typo - and the failure counter climbs until
 * the screen advises changing the Anisette server. Anisette had no part in it, and changing it
 * forces a re-login against a different machine identity (AGENTS.md rule 4): somebody is sent to
 * fix something that was never broken.
 *
 * <p>Reported as
 * <a href="https://github.com/parawanderer/OpenTagViewer/issues/168">#168</a>. The desktop
 * exporter fixed the same bug through the same library in #169, and this is deliberately reasoned
 * the same way rather than invented differently.
 *
 * <p>The arithmetic of the waits lives in {@code ACodeAppleAlreadyTookTest} on the JVM, including
 * the test that goes red if either is shortened. This covers what the screen does.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ACodeAppleTookAndThenFailedOnTest {

    private static final String EMAIL = "someone@example.com";
    private static final String PASSWORD = "hunter2";
    private static final String A_CODE = "222222";

    private FakeAppleAuthService apple;
    private ActivityScenario<AppleLoginActivity> scenario;

    @Before
    public void replaceApple() {
        AccountBeaconsForTests.forgetThemAll();

        this.apple = FakeAppleAuthService.wantsTwoFactor().whereAppleTakesTheCodeThenFails();
        AppDependencies.replaceAuthService(this.apple);
        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());

        Intents.init();
        Intents.intending(androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent(
                        MapsActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_OK, null));

        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);
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

    /**
     * <b>It says Apple took the code, rather than blaming the code.</b>
     *
     * <p>The distinction the whole change turns on: this is not a wrong code, and a screen that
     * says "Two-Factor Authentication failed" over an empty box invites the one action that
     * cannot work.
     */
    @Test
    public void itSaysAppleTookTheCodeRatherThanBlamingTheUser() {
        this.getToTheCodeBoxAndSubmit();

        Eventually.check(() -> onView(withId(R.id.verification_code_error_message))
                .check(matches(isDisplayed())));

        // **Asserted against the old wording, not merely the absence of "Anisette".** The old
        // message does not mention Anisette on a first attempt either, so a test that only
        // checked for that passed with the fix removed - it proved nothing about the case it was
        // written for. What must be gone is the sentence that blames the code.
        final String blamesTheCode = getInstrumentation().getTargetContext()
                .getString(R.string.twofactor_failed_x, "").trim();

        Eventually.check(() -> onView(withId(R.id.verification_code_error_message))
                .check(matches(not(withText(containsString(blamesTheCode))))));
        onView(withId(R.id.verification_code_error_message))
                .check(matches(not(withText(containsString("Anisette")))));
    }

    /**
     * <b>And it does not count as a failed attempt.</b>
     *
     * <p>This is the assertion with teeth. The counter is what eventually produces the
     * change-your-Anisette-server advice, so a fault on Apple's side reaching it turns one bad
     * afternoon into a re-login against a different machine identity.
     */
    @Test
    public void itDoesNotCountTowardsTheAnisetteAdvice() {
        this.getToTheCodeBoxAndSubmit();
        for (int attempt = 0; attempt < 3; attempt++) {
            this.submitTheCode();
        }

        // Four goes is past HINT_DIFFERENT_ANISETTE_SERVER_AFTER_FAILED_2FACODES, so if this were
        // being counted as a rejected code the hint would be on screen by now.
        onView(withId(R.id.verification_code_error_message))
                .check(matches(not(withText(containsString("Anisette")))));
    }

    /**
     * <b>Nobody is asked to sign in again.</b>
     *
     * <p>The account is still in its second-factor state, so a new code needs only a request on
     * the chosen method. Sending them back to the email and password screen would be a second
     * thing that looks broken.
     */
    @Test
    public void theAppleIdAndPasswordAreNotAskedForAgain() {
        this.getToTheCodeBoxAndSubmit();

        Eventually.check(() -> onView(withId(R.id.login_2fa_container))
                .check(matches(isDisplayed())));
        onView(withId(R.id.login_maininfo_container)).check(matches(not(isDisplayed())));

        assertEquals("signing in again was not needed and must not happen",
                1, this.apple.timesCalled("login"));
    }

    private void getToTheCodeBoxAndSubmit() {
        onView(withId(R.id.email_or_phone_input_field)).perform(replaceText(EMAIL));
        onView(withId(R.id.password_input_field))
                .perform(replaceText(PASSWORD), closeSoftKeyboard());

        Eventually.perform("the sign in button", () -> this.apple.timesCalled("login") > 0,
                () -> onView(withId(R.id.login_button_main)).perform(click()));

        Eventually.check(() -> onView(withText(containsString(FakeAppleAuthService.PHONE_ONE)))
                .check(matches(isDisplayed())));
        onView(withText(containsString(FakeAppleAuthService.PHONE_ONE))).perform(click());

        this.submitTheCode();
    }

    private void submitTheCode() {
        final long before = this.apple.timesCalled("submitCode");

        Eventually.check(() -> onView(withId(R.id.twofactorauth_textinput_1))
                .check(matches(isDisplayed())));

        // replaceText rather than typeText: the boxes move focus as they fill, and a paste is
        // what people actually do with a code. See AGENTS.md on Espresso.
        Eventually.perform("the code", () -> this.apple.timesCalled("submitCode") > before,
                () -> onView(withId(R.id.twofactorauth_textinput_1)).perform(replaceText(A_CODE)));
    }
}
