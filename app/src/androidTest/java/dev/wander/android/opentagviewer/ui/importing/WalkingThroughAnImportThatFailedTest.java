package dev.wander.android.opentagviewer.ui.importing;

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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.appcompat.app.AlertDialog;
import androidx.test.espresso.Espresso;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.Shot;
import dev.wander.android.opentagviewer.TestPace;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.LogRedactor;
import dev.wander.android.opentagviewer.ui.TestHostActivity;
import dev.wander.android.opentagviewer.ui.error.ErrorReportActivity;

/**
 * The two halves of a failed import, side by side, at a pace a person can follow.
 *
 * <p><b>They used to be one screen saying one thing, and that was the bug.</b> Picking a zip
 * starts two operations - reading the file and storing the tags, then asking Apple where those
 * tags are - and a single error handler covered both. So an Anisette server being down produced
 * "Error occurred while importing new devices. Try to restart the app and retry", for an import
 * that had succeeded and whose tags were visible in the device list. Issues
 * <a href="https://github.com/parawanderer/OpenTagViewer/issues/19">#19</a> and
 * <a href="https://github.com/parawanderer/OpenTagViewer/issues/26">#26</a> are 34 comments of
 * people discovering that for themselves.
 *
 * <p>Watching the two in sequence is the point: one says <i>this is a bug, report it</i>, the
 * other says <i>your tags are fine, this is probably your Anisette server</i>. If they ever read
 * alike again, the fix has been undone.
 *
 * <p>Run it slowly on a device with a window - see {@code AGENTS.md}, "Showing a UI test to a
 * person". It asserts as it goes, because a demo that can pass while showing the wrong screen is
 * decoration.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class WalkingThroughAnImportThatFailedTest {

    /** What a zip failing below the importer actually looks like. */
    private static final String A_ZIP_CAUSE =
            "EOFException: Unexpected end of ZLIB input stream";

    /** And what a first fetch failing looks like - nothing to do with a file. */
    private static final String A_FETCH_CAUSE =
            "PythonAppleFindMyException: Anisette server at https://ani.example.com returned 502";

    private ActivityScenario<?> scenario;

    @Before
    public void catchWhatLeavesTheApp() {
        Intents.init();
        // Only what leaves the app, named explicitly - a matcher broad enough to catch the
        // launch intent stubs ActivityScenario itself and hangs the run. See the note in
        // TheErrorPageIsReportableTest.
        intending(anyOf(hasAction(Intent.ACTION_VIEW), hasAction(Intent.ACTION_CHOOSER)))
                .respondWith(new ActivityResult(Activity.RESULT_OK, null));
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
     * <b>First half: the zip really could not be read, and nothing here can say why.</b>
     *
     * <p>This is the only failure that now claims to be about the file, and the only one that
     * offers to open a bug report. Everything the reporter would otherwise be asked for is
     * already on the screen.
     */
    @Test
    public void azipNobodyCanReadIsTheOneThingWorthReporting() {
        AppDependencies.replaceLogRedactor(log -> new LogRedactor.Redacted(
                log.replace("someone@example.com", "<email>"), "1 email address"));

        final Context context = getInstrumentation().getTargetContext();
        this.scenario = ActivityScenario.launch(ErrorReportActivity.intentFor(
                context, A_ZIP_CAUSE, R.string.error_report_body_import));

        // 1. It says this is a bug rather than something to retry.
        Eventually.check(() -> onView(withId(R.id.error_report_title))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();

        // 2. And it describes the right phase. The protocol wording - "something came back that
        //    this app does not know how to read" - is false here: nothing came back from
        //    anywhere, somebody chose a file.
        onView(withId(R.id.error_report_body)).check(matches(withText(
                context.getString(R.string.error_report_body_import))));
        onView(withId(R.id.error_report_body)).check(matches(not(withText(
                context.getString(R.string.error_report_body)))));
        TestPace.afterAStep();

        // 3. The failure verbatim, which is what a maintainer searches for.
        onView(withId(R.id.error_report_cause)).check(matches(withText(A_ZIP_CAUSE)));
        TestPace.afterAStep();

        // 4. The log, cleaned, and said to have been cleaned.
        Eventually.check(() -> onView(withId(R.id.error_report_share_log))
                .check(matches(isDisplayed())));
        onView(withId(R.id.error_report_log_note))
                .check(matches(withText(containsString("1 email address"))));
        Shot.ofTheScreen("an_import_that_failed-the_zip_could_not_be_read");
        TestPace.afterAStep();

        // 5. And the form, with the questions already in it.
        onView(withId(R.id.error_report_button)).perform(scrollTo(), click());
        intended(hasAction(Intent.ACTION_VIEW));
        TestPace.afterAStep();
    }

    /**
     * <b>Second half: the tags arrived and their locations did not.</b>
     *
     * <p>Everything about this is the opposite. The import is not retracted, there is nothing to
     * retry, and the likely fix is a setting rather than a bug report - so it names the setting.
     * Three reporters across #19 and #26 reached that conclusion unaided, one of them 25
     * comments in.
     */
    @Test
    public void btagsThatArrivedWithoutTheirLocationsSayExactlyThat() {
        final AtomicInteger settingsOpened = new AtomicInteger();

        final ActivityScenario<TestHostActivity> host =
                ActivityScenario.launch(TestHostActivity.class);
        this.scenario = host;

        host.onActivity(activity -> ImportedButNotLocatedDialog.show(
                activity, A_FETCH_CAUSE, settingsOpened::incrementAndGet));

        final Context context = getInstrumentation().getTargetContext();

        // 1. The headline is the reassurance, because the first thing somebody does here is
        //    look to see whether their tags survived.
        onView(withText(context.getString(R.string.imported_but_not_located_title)))
                .inRoot(isDialog()).check(matches(isDisplayed()));
        TestPace.afterAStep();

        // 2. It names Anisette, and it names the failure. The app always knew which exception
        //    it was; it used to throw that away and say "error occurred while importing".
        onView(withText(containsString("Anisette"))).inRoot(isDialog())
                .check(matches(isDisplayed()));
        onView(withText(containsString(A_FETCH_CAUSE))).inRoot(isDialog())
                .check(matches(isDisplayed()));
        Shot.ofTheScreen("an_import_that_failed-the_tags_arrived_the_locations_did_not");
        TestPace.afterAStep();

        // 3. **No offer to report it.** This fails when somebody else's server is down or a
        //    phone is on a train, and it retries by itself every minute. Inviting a bug report
        //    for that fills the tracker with weather.
        assertTrue("the fetch failure must not offer a bug report",
                nothingOnScreenSays(context.getString(R.string.error_report_button)));
        TestPace.afterAStep();

        // 4. What it offers instead is the setting that actually fixes it.
        onView(withText(context.getString(R.string.imported_but_not_located_open_settings)))
                .inRoot(isDialog()).perform(click());
        assertEquals(1, settingsOpened.get());
        TestPace.afterAStep();
    }

    /**
     * <b>And both of the ways out of it work, without doing anything.</b>
     *
     * <p>OK and the back gesture are defaults - {@code MaterialAlertDialogBuilder} makes a
     * cancelable dialog and a null listener dismisses - which is exactly why they are pinned. A
     * later {@code setCancelable(false)}, added for some other reason, would take the back
     * gesture away silently, and nothing else here would notice.
     *
     * <p>Neither must open settings. Dismissing is not consent to be sent somewhere.
     */
    @Test
    public void cthedialogCanBeDismissedBothWays() {
        final Context context = getInstrumentation().getTargetContext();
        final AtomicInteger settingsOpened = new AtomicInteger();
        final AlertDialog[] dialog = new AlertDialog[1];

        final ActivityScenario<TestHostActivity> host =
                ActivityScenario.launch(TestHostActivity.class);
        this.scenario = host;

        // **Asked of the dialog, not of the view tree.** Written the obvious way - press back,
        // then assert the title is not on screen - this class took 15 minutes and still failed:
        // proving a view absent means inRoot(isDialog()) against a screen with no dialog, and
        // Espresso's root picker retries for seconds before conceding. A boolean is instant, and
        // it is also the thing actually being claimed.
        host.onActivity(activity -> dialog[0] = ImportedButNotLocatedDialog.show(
                activity, A_FETCH_CAUSE, settingsOpened::incrementAndGet));

        // **Focus, not isShowing().** `show()` makes isShowing() true immediately, before the
        // dialog's window has taken focus - and a back press in that gap goes to the activity,
        // finishes it, and throws NoActivityResumedException from the very call that would have
        // worked a moment later. Waiting on isShowing() looked like the right guard and is not;
        // it passed locally for hours and failed in CI, which is slower and loses the race more
        // often.
        Eventually.check(() -> assertTrue("the dialog never took focus", hasFocus(dialog[0])));

        Eventually.perform("back", () -> !dialog[0].isShowing(), Espresso::pressBack);

        host.onActivity(activity -> dialog[0] = ImportedButNotLocatedDialog.show(
                activity, A_FETCH_CAUSE, settingsOpened::incrementAndGet));
        Eventually.check(() -> assertTrue("the dialog never reappeared", hasFocus(dialog[0])));

        onView(withText(context.getString(R.string.ok))).inRoot(isDialog()).perform(click());
        Eventually.check(() -> assertFalse("OK did not dismiss the dialog",
                dialog[0].isShowing()));

        assertEquals("dismissing must not open settings", 0, settingsOpened.get());
    }

    /** Whether the dialog's own window is the one that would receive a key press. */
    private static boolean hasFocus(final AlertDialog dialog) {
        return dialog.getWindow() != null
                && dialog.getWindow().getDecorView().hasWindowFocus();
    }

    /**
     * Whether a piece of text is absent from the dialog.
     *
     * <p>Phrased as a caught {@link NoMatchingViewException} rather than
     * {@code matches(not(isDisplayed()))} because the assertion here is that the view does not
     * <i>exist</i> - a button never added, not one hidden. The usual warning about expecting
     * that exception applies to views that are present and GONE, which is the opposite case.
     */
    private static boolean nothingOnScreenSays(final String text) {
        try {
            onView(withText(text)).inRoot(isDialog()).check(matches(isDisplayed()));
            return false;
        } catch (final NoMatchingViewException expected) {
            return true;
        }
    }

}
