package dev.wander.android.opentagviewer.ui.error;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.Shot;
import dev.wander.android.opentagviewer.TestPace;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.LogRedactor;

/**
 * The error page as a person meets it, at a pace a person can follow.
 *
 * <p><b>Its sibling {@code TheErrorPageIsReportableTest} asserts; this one is for watching.</b>
 * Six separate assertions each open and close the screen, so run in slow motion they are six
 * flickers rather than a journey. This walks the whole thing once, paced with {@link TestPace}, so
 * {@code slowMotion=2000} shows what somebody actually sees.
 *
 * <p>It still asserts as it goes - a demo that could pass while showing the wrong screen is
 * decoration - but the assertions are the ones a viewer is looking at anyway, and the two states
 * it covers are the ones that matter: a log that can be shared, and a log that cannot.
 *
 * <p>See {@code AGENTS.md} under "Showing a UI test to a person" for how to run it on a device
 * with a window.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class WalkingThroughTheErrorPageTest {

    private static final String A_CAUSE =
            "KeychainSessionError: No keychain keys are held, so nothing can be decrypted.";

    /** What a real logcat would carry and a public issue must not. */
    private static final String AN_EMAIL = "someone@example.com";

    private ActivityScenario<ErrorReportActivity> scenario;

    @Before
    public void catchWhatLeavesTheApp() {
        Intents.init();
        // **Only what leaves the app, named explicitly.**
        //
        // "anything that is not ACTION_MAIN" reads as the same thing and is not: the intent that
        // launches the activity under test matches it too, so ActivityScenario.launch was
        // answered by the stub, the activity never started, and the scenario waited for a RESUMED
        // state that could not arrive. A hang rather than a failure, at 0 of 8 tests, with
        // nothing in the output naming the cause.
        // CANCELED for the picker: RESULT_OK with no data would have the page treat a
        // cancelled save as a successful one, which is the opposite of what a stub should model.
        intending(hasAction(Intent.ACTION_VIEW))
                .respondWith(new ActivityResult(Activity.RESULT_OK, null));
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
                .respondWith(new ActivityResult(Activity.RESULT_CANCELED, null));
    }

    @After
    public void putTheRealOnesBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();
    }

    /**
     * <b>The whole page, in the order somebody reads it.</b>
     *
     * <p>Arrive on a failure nobody can act on, see what it was, see which build and which bundle
     * it happened to, learn that the log is cleaned and what came out of it, share it, then open
     * the report form.
     */
    @Test
    public void awholeReportFromAFailureNobodyCanAct0n() {
        // Stands in for exporter.redact: takes the address out, and says it did.
        AppDependencies.replaceLogRedactor(log -> new LogRedactor.Redacted(
                log.replace(AN_EMAIL, "<email>"), "1 email address, 2 device names"));

        this.scenario = ActivityScenario.launch(ErrorReportActivity.intentFor(
                getInstrumentation().getTargetContext(), A_CAUSE));

        // 1. It says plainly that this is a bug rather than something to retry.
        Eventually.check(() -> onView(withId(R.id.error_report_title))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();

        // 2. And what actually failed, verbatim - the thing a maintainer searches for.
        onView(withId(R.id.error_report_cause)).check(matches(withText(A_CAUSE)));
        TestPace.afterAStep();

        // 3. The two questions the report form opens with, already answered on screen. This is
        //    what stops somebody opening their export zip to read a version line out of it.
        onView(withId(R.id.error_report_build))
                .check(matches(withText(containsString("OpenTagViewer app"))));
        onView(withId(R.id.error_report_imported_from)).check(matches(isDisplayed()));
        TestPace.afterAStep();

        // 4. The log is offered only once it has been through the redactor, and the page says
        //    what came out rather than asking to be trusted.
        Eventually.check(() -> onView(withId(R.id.error_report_share_log))
                .check(matches(isDisplayed())));
        onView(withId(R.id.error_report_log_note))
                .check(matches(withText(containsString("1 email address, 2 device names"))));
        Shot.ofTheScreen("the_error_page-log_can_be_shared");
        TestPace.afterAStep();

        // 5. And it asks which of the two things is wanted, because they are not the same
        //    thing: pasting into the form's log box, or producing a file to attach. This was a
        //    share sheet, which served neither - with text and no stream, Drive and Files do not
        //    appear as targets at all.
        onView(withId(R.id.error_report_share_log)).perform(scrollTo(), click());
        onView(withText(getInstrumentation().getTargetContext()
                .getString(R.string.error_report_log_save)))
                .inRoot(isDialog()).check(matches(isDisplayed()));
        Shot.ofTheScreen("the_error_page-how_do_you_want_the_log");
        TestPace.afterAStep();

        // 6. Saving goes to the document picker, so the file lands where the user chose - which
        //    is the only place a browser's file picker can find it again.
        onView(withText(getInstrumentation().getTargetContext()
                .getString(R.string.error_report_log_save))).inRoot(isDialog()).perform(click());
        intended(hasAction(Intent.ACTION_CREATE_DOCUMENT));
        TestPace.afterAStep();

        // 7. And the report button lands on the form with the questions already in it.
        onView(withId(R.id.error_report_button)).perform(scrollTo(), click());
        intended(hasAction(Intent.ACTION_VIEW));
        TestPace.afterAStep();
    }

    /**
     * <b>And the same page when the log cannot be cleaned.</b>
     *
     * <p>Worth watching rather than only asserting, because the correct behaviour is an
     * <i>absence</i> - no share button - and an absence is the kind of thing that looks like a
     * layout bug until you know it is deliberate. The page says why, and reporting still works.
     */
    @Test
    public void andwhatItLooksLikeWhenTheLogCannotBeCleaned() {
        AppDependencies.replaceLogRedactor(log -> null);

        this.scenario = ActivityScenario.launch(ErrorReportActivity.intentFor(
                getInstrumentation().getTargetContext(), A_CAUSE));

        Eventually.check(() -> onView(withId(R.id.error_report_log_note))
                .check(matches(withText(getInstrumentation().getTargetContext()
                        .getString(R.string.error_report_log_unavailable)))));
        TestPace.afterAStep();

        // No button, rather than a button that hands over an unredacted log.
        onView(withId(R.id.error_report_share_log)).check(matches(not(isDisplayed())));
        Shot.ofTheScreen("the_error_page-log_cannot_be_cleaned");
        TestPace.afterAStep();

        // Reporting without a log still helps, so it is still offered.
        onView(withId(R.id.error_report_button)).perform(scrollTo(), click());
        intended(hasAction(Intent.ACTION_VIEW));
        TestPace.afterAStep();
    }
}
