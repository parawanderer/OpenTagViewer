package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.icloud.FakeICloudService;
import dev.wander.android.opentagviewer.python.icloud.ICloudException;
import dev.wander.android.opentagviewer.python.icloud.ICloudFailure;

/**
 * What the user is told when reading the account does not work.
 *
 * <p>Separate from the happy path because these are the cases that decide whether somebody comes
 * back tomorrow or gives up - and none of them can be produced on a real account on demand.
 *
 * <p><b>The distinction that carries the most weight is between the two empty answers.</b> An
 * account with nothing to recover from is final and the import path is the answer; a service that
 * reported nothing usable at all is very likely a bad afternoon at Apple. Showing the first when
 * it is the second tells somebody with a perfectly good account that they permanently own no
 * tags, and sends them off to find a friend with a Mac.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class FetchFromICloudErrorsTest {

    private FakeICloudService icloud;
    private ActivityScenario<FetchFromICloudActivity> scenario;

    @After
    public void putTheRealOneBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        AppDependencies.reset();
    }

    @org.junit.Before
    public void forgetAnyStoredMembership() {
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
    }

    private boolean isShown(final int id) {
        final boolean[] shown = {false};
        this.scenario.onActivity(activity ->
                shown[0] = activity.findViewById(id).getVisibility() == android.view.View.VISIBLE);
        return shown[0];
    }

    private void unlockWithTheFirstDevice() {
        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
        Eventually.perform("a device", () -> isShown(R.id.icloud_passcode_container),
                () -> onView(withText(containsString(FakeICloudService.AN_IPHONE.getSerial())))
                        .perform(click()));

        final long before = this.icloud.timesCalled("unlock");
        onView(withId(R.id.icloud_passcode_input)).perform(replaceText("123456"));
        Eventually.perform("unlock", () -> this.icloud.timesCalled("unlock") > before,
                () -> onView(withId(R.id.icloud_primary_button)).perform(click()));
    }

    /** No device on the account can unlock the keychain: final, and import is the answer. */
    @Test
    public void nothingToRecoverFromOffersTheImportPathAndNoRetry() {
        this.open(FakeICloudService.withNothingToRecoverFrom());

        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_container))
                .check(matches(isDisplayed())));
        // There is one button now, so what distinguishes the two screens is what it says - and
        // on this one it must offer the import path rather than a retry that can never work.
        Eventually.check(() -> onView(withId(R.id.icloud_primary_button))
                .check(matches(withText(R.string.icloud_import_from_file))));
        Eventually.check(() -> onView(withId(R.id.icloud_retry_container))
                .check(matches(not(isDisplayed()))));

        TestPace.afterAStep();
    }

    /** And it explains the case that actually brought most of these people here. */
    @Test
    public void ittellsThemWhySharingInFindMyIsNotEnough() {
        this.open(FakeICloudService.withNothingToRecoverFrom());

        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_shared_note))
                .check(matches(isDisplayed())));
    }

    /** A service having a bad day offers a retry, and never the "you own no tags" screen. */
    @Test
    public void aserviceHavingABadDayOffersARetry() {
        this.open(FakeICloudService.whereTheServiceIsUnsure());

        Eventually.check(() -> onView(withId(R.id.icloud_retry_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_container))
                .check(matches(not(isDisplayed()))));

        TestPace.afterAStep();
    }

    /** The two are different screens, which is the whole point. */
    @Test
    public void thetwoEmptyAnswersAreNotTheSameScreen() {
        assertNotEquals(ICloudFailure.NOTHING_TO_RECOVER_FROM, ICloudFailure.SERVICE_UNSURE);

        this.open(FakeICloudService.whereTheServiceIsUnsure());
        Eventually.check(() -> assertTrue(isShown(R.id.icloud_retry_container)));
        this.scenario.close();
        AppDependencies.reset();

        this.open(FakeICloudService.withNothingToRecoverFrom());
        Eventually.check(() -> assertTrue(isShown(R.id.icloud_no_tags_container)));
    }

    /**
     * A failure nothing anticipated lands on "try again later", with what it said.
     *
     * <p>Deliberately the safe half: "try again" about a cause nobody established is a great deal
     * better than telling somebody their account is empty when it is not.
     */
    @Test
    public void anunrecognisedFailureSaysTryAgainRatherThanGuessing() {
        this.open(FakeICloudService.withTags().whereFetchingFails(
                new ICloudException(ICloudFailure.UNKNOWN, "CloudKit said something odd")));

        this.unlockWithTheFirstDevice();

        Eventually.check(() -> onView(withId(R.id.icloud_retry_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.icloud_retry_body))
                .check(matches(withText(containsString("CloudKit said something odd")))));
    }

    /** An unexpected failure must never be reported as "this account owns no tags". */
    @Test
    public void anunrecognisedFailureIsNeverTheEmptyAccountScreen() {
        this.open(FakeICloudService.withTags().whereFetchingFails(
                new ICloudException(ICloudFailure.UNKNOWN, "something odd")));

        this.unlockWithTheFirstDevice();

        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_container))
                .check(matches(not(isDisplayed()))));
    }

    /** An account that unlocks and holds no tags: same advice, a step later. */
    @Test
    public void anaccountWithNoTagsLandsOnTheSameAdvice() {
        this.open(FakeICloudService.withNoTagsOnTheAccount());

        this.unlockWithTheFirstDevice();

        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.icloud_primary_button))
                .check(matches(isDisplayed())));

        TestPace.afterAStep();
    }

    /**
     * A rejected passcode stays on the passcode step, with the library's own words.
     *
     * <p>Not "incorrect passcode": FindMy.py's first advice is to try the same one again, because
     * the exchange has been seen to fail intermittently and then succeed.
     */
    @Test
    public void arejectedPasscodeStaysPutAndDoesNotCallItWrong() {
        this.open(FakeICloudService.withTags().refusingThePasscode(1));

        this.unlockWithTheFirstDevice();

        Eventually.check(() -> onView(withId(R.id.icloud_passcode_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.icloud_passcode_error_container))
                .check(matches(isDisplayed())));
    }

    /** A rejection is not a reason to throw the user out of the flow. */
    @Test
    public void arejectedPasscodeIsNotAFailureScreen() {
        this.open(FakeICloudService.withTags().refusingThePasscode(1));

        this.unlockWithTheFirstDevice();

        Eventually.check(() -> onView(withId(R.id.icloud_retry_container))
                .check(matches(not(isDisplayed()))));
        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_container))
                .check(matches(not(isDisplayed()))));
    }
}
