package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;

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

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.FetchFromICloudActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.SettingsActivity;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.python.icloud.KeychainMembership;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Reading the Apple account from Settings.
 *
 * <p><b>The third door to one screen, and the one people go looking for.</b> The empty state's
 * button disappears the moment anything is imported, and the device list's overflow menu is only
 * obvious to somebody who already knows it is there - so "how do I get my tags again" ends up
 * being asked of Settings, which is where somebody looks when a thing is not where they expected.
 *
 * <p>What is checked is that the row exists, says what it does, and reaches the screen. Where it
 * goes next is {@code FetchFromICloudFlowTest}'s business; the fetch screen itself is answered at
 * the door here, because it wants an Apple session and a Python interpreter and none of that is
 * what this is about.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class FetchFromAccountSettingTest {

    private ActivityScenario<SettingsActivity> scenario;
    private KeychainMembershipRepository memberships;

    @Before
    public void answerTheFetchScreenAtTheDoor() {
        this.memberships = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil());
        this.memberships.forget().blockingAwait();

        Intents.init();
        intending(hasComponent(FetchFromICloudActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_CANCELED, null));
    }

    @After
    public void closeIt() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        this.memberships.forget().blockingAwait();
    }

    private void openSettings() {
        this.scenario = ActivityScenario.launch(SettingsActivity.class);
    }

    /** As if the app had already joined the account's keychain. */
    private void givenTheAccountIsAlreadyLinked() {
        this.memberships.store(new KeychainMembership(
                "{\"peer_id\":\"peer-ours\"}", "ZW50cm9weQ==", "a-generated-passcode",
                "a-label", 2)).blockingAwait();
    }

    @Test
    public void settingsOffersToReadTheAppleAccount() {
        this.openSettings();

        Eventually.check(() -> onView(withId(R.id.settings_fetch_from_account))
                .check(matches(isDisplayed())));
    }

    /**
     * <b>And it says so once the account is linked.</b>
     *
     * <p>The row read the same either way, which is wrong twice: somebody who has linked cannot
     * tell that they have, and somebody who has not is told their tags will "update" when nothing
     * has ever been read. Being a member is what makes a later read cost one tap and no device
     * passcode, so it is worth saying out loud.
     */
    @Test
    public void alinkedAccountIsDescribedAsLinked() {
        this.givenTheAccountIsAlreadyLinked();

        this.openSettings();

        Eventually.check(() -> onView(allOf(
                withText(R.string.icloud_fetch_from_settings_linked),
                isDescendantOfA(withId(R.id.settings_fetch_from_account))))
                .check(matches(isDisplayed())));
    }

    /**
     * And it says what it does, not just what it is called.
     *
     * <p>"Fetch my tags from my Apple account" alone leaves somebody who already has tags
     * wondering whether this would duplicate them. The subtitle is the answer.
     */
    @Test
    public void therowSaysWhatItWillDoToTagsAlreadyHere() {
        this.openSettings();

        Eventually.check(() -> onView(allOf(
                withText(R.string.icloud_fetch_from_settings_subtitle),
                isDescendantOfA(withId(R.id.settings_fetch_from_account))))
                .check(matches(isDisplayed())));
    }

    /** <b>And tapping it gets there.</b> */
    @Test
    public void tappingItReachesTheAccountScreen() {
        this.openSettings();

        Eventually.check(() -> onView(withId(R.id.settings_fetch_from_account))
                .check(matches(isDisplayed())));

        onView(withId(R.id.settings_fetch_from_account)).perform(click());

        Eventually.check(() -> intended(hasComponent(FetchFromICloudActivity.class.getName())));
    }
}
