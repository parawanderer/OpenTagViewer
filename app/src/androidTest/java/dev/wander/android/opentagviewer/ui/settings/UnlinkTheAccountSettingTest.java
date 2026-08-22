package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.SettingsActivity;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.python.icloud.KeychainMembership;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Letting go of the Apple account again.
 *
 * <p>Linking was a one-way door until now: the Settings row went on offering to read the account
 * whether or not it had already been linked, and there was no way to stop the background read
 * short of clearing the app's data - which also destroys every imported tag.
 *
 * <p><b>What "unlink" means here is narrower than it sounds, and the tests hold the wording to
 * it.</b> Nothing in this app can leave the account's trust circle, so the peer it joined as
 * goes on existing and stays visible in the user's Apple device list. All this does is forget
 * the keys for it. Somebody who unlinks expecting the device list to tidy itself up will go
 * looking for a bug, so the dialog has to say so before anything happens - and it is the kind of
 * caveat that gets trimmed later by somebody shortening a wordy dialog.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class UnlinkTheAccountSettingTest {

    private ActivityScenario<SettingsActivity> scenario;
    private KeychainMembershipRepository memberships;

    @Before
    public void startUnlinked() {
        // Or the map/login/settings screen loads Apple's real ADI library: a download,
        // a dlopen and a native initialise, none of which this test is about - and two
        // screens reaching it at once segfaults the process and aborts the whole run.
        // See issue #135.
        AppDependencies.replaceAnisette(whateverTheSettingsSay -> FakeAnisetteSource.ready());
        this.memberships = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil());
        this.memberships.forget().blockingAwait();
    }

    @After
    public void closeIt() {
        // Put the real Anisette source back, or the next class inherits the fake.
        AppDependencies.reset();
        if (this.scenario != null) {
            this.scenario.close();
        }
        this.memberships.forget().blockingAwait();
    }

    private void openSettings() {
        this.scenario = ActivityScenario.launch(SettingsActivity.class);
    }

    private void givenTheAccountIsAlreadyLinked() {
        this.memberships.store(new KeychainMembership(
                "{\"peer_id\":\"peer-ours\"}", "ZW50cm9weQ==", "a-generated-passcode",
                "a-label", 2)).blockingAwait();
    }

    private boolean stillLinked() {
        return this.memberships.get().blockingFirst().isPresent();
    }

    /**
     * <b>Hidden until there is something to unlink.</b>
     *
     * <p>Not disabled: a permanently greyed row in Settings reads as a feature that is broken
     * rather than one that does not apply yet, and this row does not apply at all before linking.
     *
     * <p>Asserted as not displayed rather than as a missing view. The row is in the layout the
     * whole time - {@code withId} matches a GONE view perfectly well - so expecting
     * {@code NoMatchingViewException} would pass for the wrong reason.
     */
    @Test
    public void thereIsNothingToUnlinkBeforeLinking() {
        this.openSettings();

        Eventually.check(() -> onView(withId(R.id.settings_fetch_from_account))
                .check(matches(isDisplayed())));

        onView(withId(R.id.settings_unlink_account)).check(matches(not(isDisplayed())));
    }

    /** Once linked, the row appears. */
    @Test
    public void alinkedAccountCanBeUnlinked() {
        this.givenTheAccountIsAlreadyLinked();

        this.openSettings();

        Eventually.check(() -> onView(allOf(
                withText(R.string.icloud_unlink_title),
                isDescendantOfA(withId(R.id.settings_unlink_account))))
                .check(matches(isDisplayed())));
    }

    /**
     * <b>Tapping it asks first, and says what will survive.</b>
     *
     * <p>Linking again is not free - it needs the Apple device passcode, which is not something
     * people have to hand - so this is not a thing to do by accident.
     */
    @Test
    public void tappingItAsksBeforeDoingAnything() {
        this.givenTheAccountIsAlreadyLinked();
        this.openSettings();

        this.openTheUnlinkDialog();

        Eventually.check(() -> onView(withText(R.string.icloud_unlink_confirm_title))
                .inRoot(isDialog())
                .check(matches(isDisplayed())));

        assertTrue("asking must not have unlinked anything yet", this.stillLinked());
    }

    /**
     * <b>The dialog says the Apple device list is not tidied up by this.</b>
     *
     * <p>The one sentence in it that stops a support question. Held by a test because it is
     * exactly the sort of caveat that gets trimmed by somebody shortening a long dialog.
     */
    @Test
    public void thedialogSaysTheAppleDeviceEntryStays() {
        this.givenTheAccountIsAlreadyLinked();
        this.openSettings();

        this.openTheUnlinkDialog();

        final String message = getInstrumentation().getTargetContext()
                .getString(R.string.icloud_unlink_confirm_message);

        assertTrue("the dialog no longer explains that this does not remove the app from the"
                        + " Apple account: " + message,
                message.contains("Find My"));
    }

    /** Backing out of the dialog changes nothing. */
    @Test
    public void cancellingLeavesTheAccountLinked() {
        this.givenTheAccountIsAlreadyLinked();
        this.openSettings();

        this.openTheUnlinkDialog();

        onView(withText(R.string.cancel)).inRoot(isDialog()).perform(click());

        assertTrue("cancelling unlinked the account anyway", this.stillLinked());
        onView(withId(R.id.settings_unlink_account)).check(matches(isDisplayed()));
    }

    /**
     * <b>Confirming forgets the membership, and the screen catches up.</b>
     *
     * <p>Both halves matter. Forgetting without updating the rows leaves Settings saying the
     * account is linked when it is not, and the user tapping Unlink again on something already
     * gone.
     */
    @Test
    public void confirmingUnlinksAndTheRowsFollow() {
        this.givenTheAccountIsAlreadyLinked();
        this.openSettings();

        this.openTheUnlinkDialog();

        onView(withText(R.string.icloud_unlink_confirm_button))
                .inRoot(isDialog()).perform(click());
        Eventually.check(() -> assertFalse(this.stillLinked()));

        assertFalse("the membership was not forgotten", this.stillLinked());

        Eventually.check(() -> onView(withId(R.id.settings_unlink_account))
                .check(matches(not(isDisplayed()))));

        Eventually.check(() -> onView(allOf(
                withText(R.string.icloud_fetch_from_settings_subtitle),
                isDescendantOfA(withId(R.id.settings_fetch_from_account))))
                .check(matches(isDisplayed())));
    }

    /**
     * And it stays unlinked across a restart.
     *
     * <p>The bug this whole row exists next to: the linked state is read from an encrypted
     * DataStore on every start, so a change that only updated the screen would look right until
     * the app was reopened.
     */
    @Test
    public void itstaysUnlinkedWhenSettingsIsOpenedAgain() {
        this.givenTheAccountIsAlreadyLinked();
        this.openSettings();

        this.openTheUnlinkDialog();
        onView(withText(R.string.icloud_unlink_confirm_button))
                .inRoot(isDialog()).perform(click());
        Eventually.check(() -> assertFalse(this.stillLinked()));

        this.scenario.close();
        this.openSettings();

        Eventually.check(() -> onView(withId(R.id.settings_fetch_from_account))
                .check(matches(isDisplayed())));
        onView(withId(R.id.settings_unlink_account)).check(matches(not(isDisplayed())));
    }

    /**
     * Tap the row and wait for the dialog.
     *
     * <p><b>A plain click, not {@link Eventually#perform}.</b> `perform` is for an action that
     * might finish the flow it is driving, and it asks its predicate <i>before</i> each attempt -
     * so a predicate phrased as "is the dialog up yet" runs {@code inRoot(isDialog())} against a
     * screen with no dialog on it, and Espresso's root picker retries internally until it times
     * out before reporting that. Seven tests took six and a half minutes that way. Opening a
     * dialog cannot tear the activity down, so waiting for the row and clicking it is both
     * correct and roughly instant.
     */
    private void openTheUnlinkDialog() {
        Eventually.check(() -> onView(withId(R.id.settings_unlink_account))
                .check(matches(isDisplayed())));

        onView(withId(R.id.settings_unlink_account)).perform(click());

        Eventually.check(() -> onView(withText(R.string.icloud_unlink_confirm_title))
                .inRoot(isDialog())
                .check(matches(isDisplayed())));
    }
}
