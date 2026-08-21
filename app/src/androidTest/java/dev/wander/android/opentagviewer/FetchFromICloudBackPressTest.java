package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.Espresso.pressBackUnconditionally;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.db.AccountBeaconsForTests;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.icloud.FakeICloudService;

/**
 * What back does on each step of reading the account.
 *
 * <p><b>Back is where a multi-step screen quietly goes wrong.</b> The mistakes available are all
 * silent: abandoning the whole errand from a step that had somewhere to go, returning to a step
 * that no longer means anything, or leaving mid-call and stranding a keychain unlock that is
 * already talking to Apple. None of them throw, and none of them show up in a screenshot.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class FetchFromICloudBackPressTest {

    private FakeICloudService icloud;
    private ActivityScenario<FetchFromICloudActivity> scenario;

    @After
    public void putTheRealOneBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        AppDependencies.reset();
        AccountBeaconsForTests.forgetThemAll();
    }

    @org.junit.Before
    public void forgetAnyStoredMembership() {
        // Finishing this flow writes real rows into the real database. Cleared here too,
        // because a test that crashed left its tags behind for whatever runs next.
        AccountBeaconsForTests.forgetThemAll();

        // **The membership is in the real encrypted datastore, and it outlives a test class.**
        // FetchFromICloudMembershipTest stores one; without this, every test here resumes as a
        // member and never sees the device list. Cleared before rather than only after, because
        // a test that crashed leaves it behind.
        new dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository(
                dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore.getInstance(
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                                .getTargetContext()),
                new dev.wander.android.opentagviewer.util.android.AppCryptographyUtil())
                .forget().blockingAwait();
    }

    private void open(final FakeICloudService fake) {
        this.icloud = fake;
        AppDependencies.replaceICloud(() -> fake);
        this.scenario = ActivityScenario.launch(FetchFromICloudActivity.class);
        TestPace.afterAStep();
    }

    private boolean isShown(final int id) {
        final boolean[] shown = {false};
        this.scenario.onActivity(activity ->
                shown[0] = activity.findViewById(id).getVisibility() == android.view.View.VISIBLE);
        return shown[0];
    }

    /**
     * Whether the screen has gone.
     *
     * <p>Read from the scenario rather than by asking the activity: once it is destroyed,
     * `onActivity` throws "Cannot run onActivity since Activity has been destroyed already" -
     * which is the very outcome these tests want, so asking that way turns a pass into a
     * confusing failure.
     */
    private boolean hasLeft() {
        return this.scenario.getState() == Lifecycle.State.DESTROYED;
    }

    private void reachThePasscodeStep(final String serial) {
        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();
        Eventually.perform("a device", () -> isShown(R.id.icloud_passcode_container),
                () -> onView(withText(containsString(serial))).perform(click()));
        TestPace.afterAStep();
    }

    /**
     * From the passcode step, back goes to the device list rather than out.
     *
     * <p>Choosing the wrong device out of two is otherwise an expensive mistake: leaving costs
     * the whole errand, and the user has to find the button again.
     */
    @Test
    public void backFromThePasscodeStepReturnsToTheDeviceList() {
        this.open(FakeICloudService.withTags());
        this.reachThePasscodeStep(FakeICloudService.AN_IPHONE.getSerial());

        TestPace.afterAStep();
        pressBack();
        TestPace.afterAStep();

        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.icloud_passcode_container))
                .check(matches(not(isDisplayed()))));
        assertTrue("the screen should still be open", !this.hasLeft());
    }

    /** And the other device can then be picked, which is the point of going back. */
    @Test
    public void theotherDeviceCanBeChosenAfterGoingBack() {
        this.open(FakeICloudService.withTags());
        this.reachThePasscodeStep(FakeICloudService.AN_IPHONE.getSerial());

        TestPace.afterAStep();
        pressBack();
        TestPace.afterAStep();

        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
        Eventually.perform("the other device", () -> isShown(R.id.icloud_passcode_container),
                () -> onView(withText(containsString(FakeICloudService.A_MAC.getSerial())))
                        .perform(click()));

        onView(withId(R.id.icloud_passcode_input)).perform(replaceText("123456"));
        Eventually.perform("unlock", () -> this.icloud.timesCalled("unlock") > 0,
                () -> onView(withId(R.id.icloud_primary_button)).perform(click()));

        assertEquals("the passcode went to the device chosen after going back",
                FakeICloudService.A_MAC.getSerial(), this.icloud.unlockedWith().get(0));
    }

    /**
     * With one device there is nothing to go back to, so back leaves.
     *
     * <p>Returning to a list of one button is a dead end that reads as the button not working.
     */
    @Test
    public void backLeavesWhenThereWasNoChoiceOfDevice() {
        this.open(FakeICloudService.withTags().withOneDevice());
        this.reachThePasscodeStep(FakeICloudService.AN_IPHONE.getSerial());

        TestPace.afterAStep();
        pressBackUnconditionally();

        Eventually.check(() -> assertTrue("back should leave when there was one device",
                this.hasLeft()));
    }

    /** From the device list itself, back leaves - there is no earlier step. */
    @Test
    public void backFromTheDeviceListLeaves() {
        this.open(FakeICloudService.withTags());
        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));

        TestPace.afterAStep();
        pressBackUnconditionally();

        Eventually.check(() -> assertTrue(this.hasLeft()));
    }

    /** From a failure screen, back leaves rather than sitting there. */
    @Test
    public void backFromAFailureScreenLeaves() {
        this.open(FakeICloudService.withNothingToRecoverFrom());
        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_container))
                .check(matches(isDisplayed())));

        TestPace.afterAStep();
        pressBackUnconditionally();

        Eventually.check(() -> assertTrue(this.hasLeft()));
    }

    /** And from the overview at the end. */
    @Test
    public void backFromTheOverviewLeaves() {
        this.open(FakeICloudService.withTags());
        this.reachThePasscodeStep(FakeICloudService.AN_IPHONE.getSerial());

        onView(withId(R.id.icloud_passcode_input)).perform(replaceText("123456"));
        Eventually.perform("unlock", () -> this.icloud.timesCalled("fetch") > 0,
                () -> onView(withId(R.id.icloud_primary_button)).perform(click()));

        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));

        TestPace.afterAStep();
        pressBackUnconditionally();

        Eventually.check(() -> assertTrue(this.hasLeft()));
    }

    /**
     * Leaving always closes the session.
     *
     * <p>Two of these calls hold sockets. An abandoned session leaks them for the life of the
     * process, and "the user pressed back" is by far the most common way this screen ends.
     */
    @Test
    public void leavingClosesTheSession() {
        this.open(FakeICloudService.withTags());
        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));

        TestPace.afterAStep();
        pressBackUnconditionally();
        this.scenario.close();
        this.scenario = null;

        Eventually.check(() -> assertTrue("the session was left open", this.icloud.wasClosed()));
    }
}
