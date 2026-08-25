package dev.wander.android.opentagviewer.ui.error;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasData;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasType;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.hamcrest.MatcherAssert.assertThat;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.app.Instrumentation.ActivityResult;
import android.content.Intent;

import androidx.lifecycle.Lifecycle.State;
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
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.LogRedactor;

/**
 * The page somebody lands on when the app cannot say what went wrong.
 *
 * <p><b>Its whole job is to make a report possible, so what it hands over is the thing to test.</b>
 * Two questions decide whether it works: does the link carry the template that puts the questions
 * in front of the reporter, and can an <i>unredacted</i> log ever leave the app. The second is the
 * one with a permanent cost - an Apple ID posted to a public issue cannot be un-posted - and it
 * only happens on the path where redaction fails, which is exactly the path a real device will not
 * take on demand.
 *
 * <p>Hence a fake redactor. {@code AppDependencies.replaceLogRedactor} produces both the working
 * case and the broken one; the broken one is not reachable otherwise, because it means Chaquopy
 * failing to start.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class TheErrorPageIsReportableTest {

    private static final String A_CAUSE = "KeychainSessionError: No keychain keys are held";

    /** Something a real logcat would carry and a report must not. */
    private static final String SOMETHING_PERSONAL = "someone@example.com";

    /** Only the redactor's output carries this, so finding it proves which text went.
     * Asserting the absence of the personal string alone would pass on an empty payload. */
    private static final String REDACTED_MARKER = " [cleaned by the redactor]";

    private ActivityScenario<ErrorReportActivity> scenario;

    @Before
    public void catchTheIntents() {
        Intents.init();

        // **Only what leaves the app, named explicitly.**
        //
        // "anything that is not ACTION_MAIN" reads as the same thing and is not: the intent that
        // launches the activity under test matches it too, so ActivityScenario.launch was
        // answered by the stub, the activity never started, and the scenario waited for a RESUMED
        // state that could not arrive. A hang rather than a failure, at 0 of 8 tests, with
        // nothing in the output naming the cause.
        // **Every intent the page can fire, or the real thing launches.** `intending` stubs
        // only what it is named, and this class went on stubbing ACTION_CHOOSER after the share
        // sheet became a document picker - so a real picker opened over the app and stayed there
        // for whatever ran next.
        intending(anyOf(
                hasAction(Intent.ACTION_VIEW),
                hasAction(Intent.ACTION_CREATE_DOCUMENT)))
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

    private void open() {
        this.scenario = ActivityScenario.launch(ErrorReportActivity.intentFor(
                getInstrumentation().getTargetContext(), A_CAUSE));
    }

    /**
     * <b>The report button opens the form, with the template that asks the questions.</b>
     *
     * <p>Not {@code /issues/new}. GitHub applies a template's labels and questions from its front
     * matter; a bare form gives the reporter a blank box and the maintainer an unlabelled issue.
     * And GitHub does not error on a wrong template name - it silently serves the blank one - so
     * nothing but an assertion notices.
     */
    @Test
    public void thereportButtonOpensTheTemplatedForm() {
        AppDependencies.replaceLogRedactor(log -> new LogRedactor.Redacted(log, "nothing"));
        this.open();

        Eventually.check(() -> onView(withId(R.id.error_report_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.error_report_button)).perform(click());

        intended(allOf(
                hasAction(Intent.ACTION_VIEW),
                hasData(hasToString(IssueReport.NEW_APP_BUG))));
    }

    /**
     * <b>And the cause is on screen verbatim, in the words the failure arrived in.</b>
     *
     * <p>It is evidence, not prose: a maintainer searches for it, and a reporter pastes it. A
     * translated or prettified cause is one nobody can match against a stack trace.
     */
    @Test
    public void thefailureIsShownAsItArrived() {
        AppDependencies.replaceLogRedactor(log -> new LogRedactor.Redacted(log, "nothing"));
        this.open();

        Eventually.check(() -> onView(withId(R.id.error_report_cause))
                .check(matches(withText(A_CAUSE))));
    }

    /**
     * <b>Copying puts the redacted text on the clipboard, and not the raw log.</b>
     *
     * <p>The fake stands in for {@code exporter.redact}, whose own rules are the exporter's to
     * test. What is asserted here is the wiring - and it is asserted on the <i>payload</i>,
     * which is the whole point. This test previously checked that a share sheet had opened and
     * called itself "the shared log is the redacted one"; it would have stayed green while
     * handing over an unredacted log, which is the one outcome that cannot be taken back.
     */
    @Test
    public void thecopiedLogIsTheRedactedOne() {
        // **A bounded stand-in rather than the real log with a marker on the end.** Copy is only
        // offered for a log that fits on the clipboard, and the real capture is however chatty
        // the emulator happened to be - so passing it through would make whether this test can
        // reach the Copy item depend on the device's logcat volume. The two assertions below are
        // about which text travels, not how much of it.
        AppDependencies.replaceLogRedactor(log ->
                new LogRedactor.Redacted("<email> was here" + REDACTED_MARKER, "1 email address"));
        this.open();

        this.chooseFromTheLogMenu(R.string.error_report_log_copy);

        final CharSequence copied = theClipboard();
        assertNotNull("nothing was copied", copied);
        assertThat(copied.toString(), containsString(REDACTED_MARKER));
        assertThat("the raw log reached the clipboard",
                copied.toString(), not(containsString(SOMETHING_PERSONAL)));
    }

    /**
     * <b>Saving asks the system for somewhere to put it, rather than choosing for the user.</b>
     *
     * <p>A file the user picked the location of is one the browser's file picker can find again
     * when they go to attach it, which is the entire reason this option exists. A cache file
     * handed to a share sheet is not, and Drive and Files do not even appear as targets for one.
     */
    @Test
    public void thesaveOptionOpensTheDocumentPicker() {
        AppDependencies.replaceLogRedactor(log -> new LogRedactor.Redacted(log, "nothing"));
        this.open();

        this.chooseFromTheLogMenu(R.string.error_report_log_save);

        intended(allOf(
                hasAction(Intent.ACTION_CREATE_DOCUMENT),
                hasType("text/plain"),
                hasExtra(Intent.EXTRA_TITLE, "opentagviewer-log.txt")));
    }

    /**
     * <b>And there is a way off this page that is on the page.</b>
     *
     * <p>The action bar is hidden here, so before Close existed the system back gesture was the
     * only exit - no arrow, no X. Fine for anybody who knows that and a dead end for anybody who
     * does not, on a screen somebody reaches at the moment they are already stuck.
     */
    @Test
    public void thereisAWayOutThatIsNotTheBackGesture() {
        AppDependencies.replaceLogRedactor(log -> new LogRedactor.Redacted(log, "nothing"));
        this.open();

        Eventually.check(() -> onView(withId(R.id.error_report_close))
                .check(matches(isDisplayed())));
        onView(withId(R.id.error_report_close)).perform(scrollTo(), click());

        Eventually.check(() -> assertEquals(
                State.DESTROYED, this.scenario.getState()));
    }

    /**
     * Opens the two-way choice and picks one of them.
     *
     * <p><b>The wait between the two is not padding.</b> A dialog animates in, and
     * `animationsDisabled` does not stop it - AGP zeroes the window and transition scales and
     * leaves `animator_duration_scale` alone, so clicking the instant the builder returns hits a
     * row that is still scaling up and less than 90% visible. Espresso calls that a
     * PerformException, which reads like the view being wrong rather than early. It passed on a
     * fast device and failed on the managed one, which is the usual way round.
     */
    private void chooseFromTheLogMenu(final int option) {
        Eventually.check(() -> onView(withId(R.id.error_report_share_log))
                .check(matches(isDisplayed())));
        onView(withId(R.id.error_report_share_log)).perform(scrollTo(), click());

        final String label = getInstrumentation().getTargetContext().getString(option);
        Eventually.check(() -> onView(withText(label)).inRoot(isDialog())
                .check(matches(isDisplayed())));
        onView(withText(label)).inRoot(isDialog()).perform(click());
    }

    /** What is on the clipboard, read on the main thread as the framework requires. */
    private static CharSequence theClipboard() {
        final CharSequence[] held = new CharSequence[1];
        getInstrumentation().runOnMainSync(() -> {
            final ClipboardManager clipboard = getInstrumentation().getTargetContext()
                    .getSystemService(ClipboardManager.class);
            final ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            held[0] = clip == null || clip.getItemCount() == 0
                    ? null
                    : clip.getItemAt(0).getText();
        });
        return held[0];
    }

    /**
     * And it says what came out, rather than asking to be trusted.
     */
    @Test
    public void itsaysWhatTheRedactorRemoved() {
        AppDependencies.replaceLogRedactor(log ->
                new LogRedactor.Redacted(log, "1 email address, 2 serial numbers"));
        this.open();

        Eventually.check(() -> onView(withId(R.id.error_report_log_note))
                .check(matches(withText(containsString("1 email address, 2 serial numbers")))));
    }

    /**
     * <b>A redactor that cannot run means no log at all - never the raw one.</b>
     *
     * <p>The case this class exists for. This page is reached <i>because</i> something broke, and
     * "Chaquopy did not start" is a candidate - so the redactor failing is not hypothetical, it is
     * correlated with being here. Falling back to the unredacted log would put somebody's Apple ID
     * on a public issue at the exact moment they are least inclined to read it first, and it
     * cannot be taken back.
     *
     * <p>So the button is absent, and the page says why rather than leaving a dead control.
     */
    @Test
    public void arefusedRedactionOffersNoLogAtAll() {
        AppDependencies.replaceLogRedactor(log -> null);
        this.open();

        // **Waiting on the text, not on the view being displayed.** The note is in the layout
        // with no android:text, so it measures a line high and is "displayed" from the first
        // frame - the wait returned instantly and the assertion below it read an empty view.
        // It passed only because reading the log was fast enough to win the race, and stopped
        // passing the moment the app started capturing ten times as much of it.
        Eventually.check(() -> onView(withId(R.id.error_report_log_note)).check(matches(
                withText(getInstrumentation().getTargetContext()
                        .getString(R.string.error_report_log_unavailable)))));

        onView(withId(R.id.error_report_share_log)).check(matches(not(isDisplayed())));
    }

    /**
     * <b>A log too large for the clipboard is not offered to it.</b>
     *
     * <p>{@code setPrimaryClip} is a Binder call, Binder caps a transaction at about a megabyte
     * across the process, and a string parcels as UTF-16. Raising the captured log to 5000 lines
     * made that parcel 1,055,848 bytes, and Copy killed this activity with
     * {@code TransactionTooLargeException} - the app crashing while somebody reported a crash.
     *
     * <p>Trimming to fit was the other option and is worse: it hands over something that looks
     * complete, and what goes missing is the oldest part, which is the import and start-up lines.
     * So the offer changes instead, and this asserts the reason is on screen rather than the
     * option merely vanishing.
     *
     * <p><b>The fake's output is unrelated to its input on purpose.</b> Redaction replaces a
     * captured group with a {@code <name-N>} placeholder that is often longer than what it
     * replaced, so a redacted log can be bigger than the raw one - and the decision has to be
     * made on what actually goes to the clipboard. A fake that returned its input would not tell
     * the two apart.
     */
    @Test
    public void alogTooBigToCopyOffersSavingAndSaysWhy() {
        AppDependencies.replaceLogRedactor(
                log -> new LogRedactor.Redacted(aLogOf(600_000), "1 email address"));
        this.open();

        Eventually.check(() -> onView(withId(R.id.error_report_share_log))
                .check(matches(isDisplayed())));
        onView(withId(R.id.error_report_share_log)).perform(scrollTo(), click());

        final var context = getInstrumentation().getTargetContext();

        Eventually.check(() -> onView(withText(context.getString(
                R.string.error_report_log_too_big_to_copy))).inRoot(isDialog())
                .check(matches(isDisplayed())));

        onView(withText(context.getString(R.string.error_report_log_save))).inRoot(isDialog())
                .check(matches(isDisplayed()));
        onView(withText(context.getString(R.string.error_report_log_copy)))
                .check(doesNotExist());
    }

    /**
     * And saving it still works, which is the whole point of still offering something.
     *
     * <p>An option that is present and does nothing would pass the test above.
     */
    @Test
    public void alogTooBigToCopyCanStillBeSaved() {
        AppDependencies.replaceLogRedactor(
                log -> new LogRedactor.Redacted(aLogOf(600_000), "1 email address"));
        this.open();

        this.chooseFromTheLogMenu(R.string.error_report_log_save);

        intended(allOf(
                hasAction(Intent.ACTION_CREATE_DOCUMENT),
                hasType("text/plain"),
                hasExtra(Intent.EXTRA_TITLE, "opentagviewer-log.txt")));
    }

    /** A redacted log of a given size, in lines, so it looks like what it stands in for. */
    private static String aLogOf(final int chars) {
        final StringBuilder sb = new StringBuilder(chars + 64);
        while (sb.length() < chars) {
            sb.append("08-25 17:37:55.257 21244 26234 I python.stdout: a line of it\n");
        }
        return sb.toString();
    }

    /**
     * And reporting still works without one, because a report with no log still helps.
     */
    @Test
    public void reportingIsStillOfferedWithoutALog() {
        AppDependencies.replaceLogRedactor(log -> null);
        this.open();

        Eventually.check(() -> onView(withId(R.id.error_report_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.error_report_button)).perform(click());

        intended(hasAction(Intent.ACTION_VIEW));
    }

    private static org.hamcrest.Matcher<android.net.Uri> hasToString(final String expected) {
        return new org.hamcrest.TypeSafeMatcher<>() {
            @Override
            protected boolean matchesSafely(final android.net.Uri uri) {
                return expected.equals(uri.toString());
            }

            @Override
            public void describeTo(final org.hamcrest.Description description) {
                description.appendText("a Uri of " + expected);
            }
        };
    }
}
