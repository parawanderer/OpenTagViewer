package dev.wander.android.opentagviewer.ui.importing;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.ui.TestHostActivity;

/**
 * The prompt that asks for a locked bundle's code, driven for real.
 *
 * <p>Worth driving rather than reasoning about, because the interesting behaviour is all in the
 * input handling and none of it is visible from the importer's side. The code arrives by paste
 * far more often than by typing - it is emailed alongside the bundle - and an earlier draft of
 * this capped each box at four characters, which would have taken a pasted twelve-character
 * code, kept four, and then reported that the code did not open the bundle. Nothing about that
 * failure would have pointed at the input field.
 *
 * <p>Hosted on {@link TestHostActivity} rather than on {@code MapsActivity}. See the gap noted
 * on {@link #theDialogAsksForTheCode()}: the wiring from a locked bundle to this dialog is not
 * covered here, and cannot be on the current test device.
 *
 * <p><b>{@code replaceText} throughout, never {@code typeText}</b> - the rule the 2FA code
 * entry's test helper already follows, and for the same reason: these boxes move focus as they
 * fill, and Espresso's per-character typing fails once the field it started on stops being
 * focused. It is also the case people actually hit, a code being pasted far more often than
 * typed.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class BundlePasscodeDialogTest {

    /** As the exporter displays it. */
    private static final String TYPED = "H4K2-9WMR-7TQX";

    private static final String EXPECTED = "H4K29WMR7TQX";

    private ActivityScenario<TestHostActivity> scenario;

    /** What the dialog handed back, in order. Empty unless the user confirmed. */
    private final List<String> codes = new ArrayList<>();

    @Before
    public void launchAHost() {
        this.scenario = ActivityScenario.launch(TestHostActivity.class);
    }

    @After
    public void closeTheHost() {
        if (this.scenario != null) {
            this.scenario.close();
        }
    }

    /**
     * It appears, and says what it wants.
     *
     * <p><b>Known gap:</b> this shows the dialog directly. Nothing here proves that picking a
     * locked bundle in {@code MapsActivity} reaches it - that path needs the activity, and the
     * managed device runs an {@code aosp-atd} image with no Play Services, so a test touching
     * Maps cannot run on it at all. Covered by issue #63; until then the wiring from
     * {@code Reason.LOCKED} to this call is read, not tested.
     */
    @Test
    public void theDialogAsksForTheCode() {
        showDialog(false);

        Eventually.check(() -> onView(withText(R.string.bundle_locked_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withId(R.id.bundle_passcode_group_1)).inRoot(isDialog()).check(matches(isDisplayed()));
        onView(withId(R.id.bundle_passcode_group_2)).inRoot(isDialog()).check(matches(isDisplayed()));
        onView(withId(R.id.bundle_passcode_group_3)).inRoot(isDialog()).check(matches(isDisplayed()));
    }

    /** A partial code cannot open anything, and a button that always fails teaches nothing. */
    @Test
    public void unlockStaysDisabledUntilTheWholeCodeIsThere() {
        showDialog(false);

        Eventually.check(() -> onView(withId(android.R.id.button1))
                .inRoot(isDialog()).check(matches(not(isEnabled()))));

        onView(withId(R.id.bundle_passcode_group_1)).inRoot(isDialog()).perform(replaceText("H4K2"));
        onView(withId(R.id.bundle_passcode_group_2)).inRoot(isDialog()).perform(replaceText("9WMR"));

        onView(withId(android.R.id.button1)).inRoot(isDialog()).check(matches(not(isEnabled())));

        onView(withId(R.id.bundle_passcode_group_3)).inRoot(isDialog()).perform(replaceText("7TQX"));

        Eventually.check(() -> onView(withId(android.R.id.button1))
                .inRoot(isDialog()).check(matches(isEnabled())));
    }

    @Test
    public void typingItGroupByGroupProducesTheWholeCode() {
        showDialog(false);

        onView(withId(R.id.bundle_passcode_group_1)).inRoot(isDialog()).perform(replaceText("H4K2"));
        onView(withId(R.id.bundle_passcode_group_2)).inRoot(isDialog()).perform(replaceText("9WMR"));
        onView(withId(R.id.bundle_passcode_group_3)).inRoot(isDialog()).perform(replaceText("7TQX"));

        Eventually.perform("unlock", () -> !this.codes.isEmpty(),
                () -> onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click()));

        assertEquals(List.of(EXPECTED), this.codes);
    }

    /**
     * The whole code, hyphens and all, dropped into the first box.
     *
     * <p>The normal way a code gets here, and the case an earlier draft silently truncated.
     */
    @Test
    public void pastingTheGroupedCodeFillsAllThreeGroups() {
        showDialog(false);

        onView(withId(R.id.bundle_passcode_group_1)).inRoot(isDialog()).perform(replaceText(TYPED));

        Eventually.check(() -> onView(withId(R.id.bundle_passcode_group_1))
                .inRoot(isDialog()).check(matches(withText("H4K2"))));
        onView(withId(R.id.bundle_passcode_group_2)).inRoot(isDialog()).check(matches(withText("9WMR")));
        onView(withId(R.id.bundle_passcode_group_3)).inRoot(isDialog()).check(matches(withText("7TQX")));

        Eventually.perform("unlock", () -> !this.codes.isEmpty(),
                () -> onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click()));

        assertEquals(List.of(EXPECTED), this.codes);
    }

    /** Pasted lower case, which is what a copy out of an email often is. */
    @Test
    public void pastingItInLowerCaseWorksToo() {
        showDialog(false);

        onView(withId(R.id.bundle_passcode_group_1)).inRoot(isDialog())
                .perform(replaceText(TYPED.toLowerCase()));

        Eventually.check(() -> onView(withId(R.id.bundle_passcode_group_3))
                .inRoot(isDialog()).check(matches(withText("7TQX"))));
    }

    /**
     * The fold happens in the box, where the user can see it.
     *
     * <p>{@code O} and {@code I} are excluded from the alphabet <em>because</em> people write
     * them for {@code 0} and {@code 1}. Folding silently on submit would leave somebody looking
     * at a field that does not contain what they think it does.
     */
    @Test
    public void confusableLettersFoldWhereTheUserCanSeeThem() {
        showDialog(false);

        onView(withId(R.id.bundle_passcode_group_1)).inRoot(isDialog()).perform(replaceText("OI23"));

        Eventually.check(() -> onView(withId(R.id.bundle_passcode_group_1))
                .inRoot(isDialog()).check(matches(withText("0123"))));
    }

    /**
     * Characters no code can hold never appear at all.
     *
     * <p>Including {@code U}, which is excluded from the alphabet but is <b>not</b> a
     * confusable - it has no digit to fold onto, so it has to be dropped rather than replaced.
     */
    @Test
    public void charactersNoCodeCanContainAreRefused() {
        showDialog(false);

        onView(withId(R.id.bundle_passcode_group_1)).inRoot(isDialog()).perform(replaceText("H!4U@K"));

        // The permitted characters, in order, and nothing else. Four of them, so the group fills
        // and the rest would have carried on into the next box.
        Eventually.check(() -> onView(withId(R.id.bundle_passcode_group_1))
                .inRoot(isDialog()).check(matches(withText("H4K"))));
    }

    /** A retry says why, rather than silently asking the same question again. */
    @Test
    public void aRetryAfterAWrongCodeSaysSo() {
        showDialog(true);

        Eventually.check(() -> onView(withText(R.string.import_failed_wrong_passcode))
                .inRoot(isDialog()).check(matches(isDisplayed())));
    }

    /** And the complaint goes away once they start fixing it. */
    @Test
    public void theComplaintClearsWhenTheyStartTypingAgain() {
        showDialog(true);

        Eventually.check(() -> onView(withText(R.string.import_failed_wrong_passcode))
                .inRoot(isDialog()).check(matches(isDisplayed())));

        onView(withId(R.id.bundle_passcode_group_1)).inRoot(isDialog()).perform(replaceText("H"));

        Eventually.check(() -> onView(withId(R.id.bundle_passcode_error))
                .inRoot(isDialog()).check(matches(not(isDisplayed()))));
    }

    /** Cancelling is declining, and must not import anything. */
    @Test
    public void cancellingHandsBackNothing() {
        showDialog(false);

        Eventually.check(() -> onView(withId(android.R.id.button2))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());

        assertTrue("cancelling must not produce a code", this.codes.isEmpty());
    }

    private void showDialog(final boolean wrongPreviousAttempt) {
        this.scenario.onActivity(activity ->
                BundlePasscodeDialog.show(activity, wrongPreviousAttempt, this.codes::add));
    }
}
