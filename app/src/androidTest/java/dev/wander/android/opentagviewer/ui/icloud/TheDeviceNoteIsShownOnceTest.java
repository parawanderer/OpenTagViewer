package dev.wander.android.opentagviewer.ui.icloud;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.FetchFromICloudActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.AccountBeaconsForTests;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.icloud.FakeICloudService;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * The page explaining what this app put on somebody's Apple account, and when it is worth showing.
 *
 * <p><b>It describes something that happened, so it belongs to the run where it happened.</b>
 * Connecting an account registers a device: a row appears in the user's Apple device list, titled
 * after a Mac they do not own, next to a button offering to remove things they do not recognise -
 * and removing it breaks the session. That is worth a screen.
 *
 * <p>Re-reading an already-linked account registers nothing. Showing the same page again explains
 * an event that did not occur, to somebody who read it the first time - and a page that turns up
 * when nothing has happened is one people learn to tap past, including on the run where it
 * matters.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class TheDeviceNoteIsShownOnceTest {

    private static final String PASSCODE = "123456";

    private FakeICloudService icloud;
    private ActivityScenario<FetchFromICloudActivity> scenario;
    private KeychainMembershipRepository memberships;

    @Before
    public void startWithNothingStored() {
        this.memberships = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil());

        // The membership lives in the real encrypted datastore and outlives a test class, so a
        // run that crashed leaves this screen resuming as a member. Cleared before as well as
        // after for that reason.
        this.memberships.forget().blockingAwait();
        AccountBeaconsForTests.forgetThemAll();
    }

    @After
    public void putItBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        AppDependencies.reset();
        this.memberships.forget().blockingAwait();
        AccountBeaconsForTests.forgetThemAll();
    }

    private void open() {
        this.icloud = FakeICloudService.withTags();
        AppDependencies.replaceICloud(() -> this.icloud);
        this.scenario = ActivityScenario.launch(FetchFromICloudActivity.class);
    }

    /** Pick the first device and unlock with a passcode, which is what a first run does. */
    private void connectForTheFirstTime() {
        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
        onView(withText(containsString(FakeICloudService.AN_IPHONE.getSerial()))).perform(click());

        Eventually.check(() -> onView(withId(R.id.icloud_passcode_input))
                .check(matches(isDisplayed())));
        onView(withId(R.id.icloud_passcode_input)).perform(replaceText(PASSCODE));

        final long before = this.icloud.timesCalled("unlock");
        Eventually.perform("unlock", () -> this.icloud.timesCalled("unlock") > before,
                () -> onView(withId(R.id.icloud_primary_button)).perform(click()));
    }

    private void waitForTheResults() {
        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));
    }

    /**
     * <b>A first connection registers a device, so it explains one.</b>
     *
     * <p>The button on the results step says Next rather than Done precisely because there is a
     * step after it.
     */
    @Test
    public void aafirstConnectionExplainsTheDeviceItJustRegistered() {
        this.open();
        this.connectForTheFirstTime();
        this.waitForTheResults();

        onView(withId(R.id.icloud_primary_button))
                .check(matches(withText(R.string.icloud_results_next)));
        onView(withId(R.id.icloud_primary_button)).perform(click());

        Eventually.check(() -> onView(withId(R.id.icloud_registered_container))
                .check(matches(isDisplayed())));
    }

    /**
     * <b>And re-reading a linked account does not, because nothing was registered.</b>
     *
     * <p>The one this exists for. The second run resumes as the member it already is - no device
     * list, no passcode, no join - and the page describing a new entry in the Apple device list
     * would be describing an entry that has been there since the first run.
     */
    @Test
    public void bareadOfALinkedAccountDoesNotShowItAgain() {
        this.open();
        this.connectForTheFirstTime();
        Eventually.check(() -> assertTrue("the first run did not store a membership",
                this.memberships.get().blockingFirst().isPresent()));
        this.scenario.close();
        AppDependencies.reset();

        this.open();
        this.waitForTheResults();

        // Done, not Next: there is no step after this one now.
        onView(withId(R.id.icloud_primary_button))
                .check(matches(withText(R.string.icloud_results_done)));

        // A GONE view still matches withId, so "not on screen" is asserted rather than expecting
        // a NoMatchingViewException - the container is inflated on every run either way.
        onView(withId(R.id.icloud_registered_container)).check(matches(not(isDisplayed())));
    }

    /**
     * And pressing it leaves, rather than landing on the note by another route.
     *
     * <p>Separate from the assertion above because the label and the action are set together and
     * could disagree: a button reading Done that still walks to the next step is worse than one
     * that reads Next.
     */
    @Test
    public void cpressingDoneOnAReadJustLeaves() {
        this.open();
        this.connectForTheFirstTime();
        Eventually.check(() -> assertTrue(this.memberships.get().blockingFirst().isPresent()));
        this.scenario.close();
        AppDependencies.reset();

        this.open();
        this.waitForTheResults();
        onView(withId(R.id.icloud_primary_button)).perform(click());

        Eventually.check(() -> assertTrue("the screen should have closed",
                this.scenario.getState() == androidx.lifecycle.Lifecycle.State.DESTROYED));
    }
}
