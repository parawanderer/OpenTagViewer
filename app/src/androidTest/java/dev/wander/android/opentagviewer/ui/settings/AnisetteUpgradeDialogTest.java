package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Dialog;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.ui.TestHostActivity;

/**
 * The one-time offer to move an existing login off a public Anisette server.
 *
 * <p>Everything about this prompt is one-shot and irreversible in practice, which is why it is
 * worth driving for real rather than reasoning about. Showing it twice teaches people to
 * dismiss it unread; never showing it wastes the feature on everyone who upgraded; showing it
 * to somebody who already chose remote overrides a decision they made deliberately; and
 * accepting it signs them out, so a mistake there costs a two-factor round trip.
 *
 * <p>Driven through Espresso on a bare host activity - see {@link TestHostActivity}. The app's
 * own activities restore an Apple session before they finish starting, so hosting this on one
 * of them would put a network in the loop of a test about a dialog.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class AnisetteUpgradeDialogTest {

    private ActivityScenario<TestHostActivity> scenario;

    /** What the dialog decided, in order. Empty until it answers, and never more than one. */
    private final List<Boolean> decisions = new ArrayList<>();

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
     * The upgrader: signed in, never chose, never asked. The only person this is for.
     */
    @Test
    public void somebodyWhoUpgradedIsAsked() {
        final UserSettings settings = upgradedFromAnEarlierVersion();

        assertNotNull("an upgraded user should be offered this", offer(settings));

        inTheDialog(R.string.anisette_upgrade_title).check(matches(isDisplayed()));
        inTheDialog(R.string.anisette_upgrade_switch).check(matches(isDisplayed()));
        inTheDialog(R.string.anisette_upgrade_later).check(matches(isDisplayed()));
    }

    /**
     * Accepting switches the mode and asks for a sign-in.
     *
     * <p>The mode has to be set before the decision is delivered: the caller's next act is to
     * persist these settings and sign the user out, so a mode written afterwards would be
     * written to an object nobody saves again.
     */
    @Test
    public void acceptingSwitchesToLocalBeforeAnybodyIsSignedOut() {
        final UserSettings settings = upgradedFromAnEarlierVersion();
        offer(settings);

        inTheDialog(R.string.anisette_upgrade_switch).perform(click());

        assertEquals(List.of(true), this.decisions);
        assertEquals("the switch has to be in the settings the caller is about to save",
                UserSettings.ANISETTE_LOCAL, settings.getAnisetteMode());
    }

    /** Declining changes nothing except that they were asked. */
    @Test
    public void decliningLeavesThemWhereTheyWere() {
        final UserSettings settings = upgradedFromAnEarlierVersion();
        offer(settings);

        inTheDialog(R.string.anisette_upgrade_later).perform(click());

        assertEquals(List.of(false), this.decisions);
        assertNull("declining is not choosing remote - it is not choosing",
                settings.getAnisetteMode());
        assertEquals("a session must not be moved by a prompt somebody declined",
                UserSettings.ANISETTE_REMOTE, settings.resolveAnisetteMode(true));
    }

    /**
     * Answering delivers exactly one decision.
     *
     * <p>Dismissal follows a button press as well as a cancel, so the obvious wiring fires
     * twice - saving the settings twice, and in the accepting case racing that save against
     * the sign-out it triggers.
     */
    @Test
    public void answeringDeliversOneDecisionAndNotTwo() {
        offer(upgradedFromAnEarlierVersion());

        inTheDialog(R.string.anisette_upgrade_switch).perform(click());

        assertEquals("one answer, one decision", 1, this.decisions.size());
    }

    /**
     * Walking away still counts as having been asked.
     *
     * <p>Otherwise the flag is never written and the prompt returns on every launch - the exact
     * behaviour that teaches people to dismiss it without reading.
     */
    @Test
    public void dismissingWithoutAnsweringStillCountsAsAsked() {
        final UserSettings settings = upgradedFromAnEarlierVersion();
        final Dialog dialog = offer(settings);

        // Cancelled directly rather than with Espresso's back press: back is delivered to the
        // resumed activity, which tears the whole host down and fails with "Pressed back and
        // killed the app" before the dialog ever sees it. This is the same path a back press
        // or a tap outside takes.
        getInstrumentation().runOnMainSync(dialog::cancel);
        // Dialog delivers its dismiss listener through a posted message rather than inline, so
        // without waiting the assertion runs before the callback does.
        getInstrumentation().waitForIdleSync();

        assertEquals(List.of(false), this.decisions);
        assertFalse("being asked once is the whole point",
                settings.shouldOfferLocalAnisette(true));
    }

    /** And having been asked, they are not asked again. */
    @Test
    public void nobodyIsAskedTwice() {
        final UserSettings settings = upgradedFromAnEarlierVersion();

        offer(settings);
        inTheDialog(R.string.anisette_upgrade_later).perform(click());

        assertNull("a prompt that comes back is a prompt nobody reads", offer(settings));
    }

    /**
     * Somebody who chose a server for themselves already answered this question.
     */
    @Test
    public void somebodyWhoChoseRemoteIsNotSecondGuessed() {
        final UserSettings settings = upgradedFromAnEarlierVersion();
        settings.setAnisetteMode(UserSettings.ANISETTE_REMOTE);

        assertNull("they chose this deliberately", offer(settings));
    }

    /** And somebody already on local has nothing to be offered. */
    @Test
    public void somebodyAlreadyOnLocalIsNotOffered() {
        final UserSettings settings = upgradedFromAnEarlierVersion();
        settings.setAnisetteMode(UserSettings.ANISETTE_LOCAL);

        assertNull(offer(settings));
    }

    /**
     * The flag is set when the dialog appears, not when it is answered.
     *
     * <p>The activity can be torn down mid-dialog - a rotation, a background kill - and an
     * offer only recorded on the way out would come back every time that happened.
     */
    @Test
    public void theOfferIsRecordedAsSoonAsItIsMade() {
        final UserSettings settings = upgradedFromAnEarlierVersion();

        offer(settings);

        assertTrue("recorded before any answer", settings.getAnisetteUpgradeOffered());
        assertTrue("and no decision has been delivered yet", this.decisions.isEmpty());
    }

    /** Signed in, on a server, never asked - the state an update leaves people in. */
    private static UserSettings upgradedFromAnEarlierVersion() {
        return UserSettings.builder()
                .anisetteServerUrl("https://ani.sidestore.io")
                .build();
    }

    /**
     * A view in the dialog's window, not the activity's.
     *
     * <p>A dialog is a separate window, and Espresso picks which window to search by itself.
     * Left to choose, it picks the activity underneath - where none of these views are - and
     * reports the buttons as missing from a hierarchy they were never in.
     */
    private static androidx.test.espresso.ViewInteraction inTheDialog(final int stringRes) {
        return onView(withText(stringRes)).inRoot(isDialog());
    }

    private Dialog offer(final UserSettings settings) {
        final Dialog[] shown = new Dialog[1];
        this.scenario.onActivity(activity -> shown[0] = AnisetteUpgradeDialog.offerIfDue(
                activity, settings, this.decisions::add));
        return shown[0];
    }
}
