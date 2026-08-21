package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.db.AccountBeaconsForTests;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.icloud.EscrowPasscode;
import dev.wander.android.opentagviewer.python.icloud.FakeICloudService;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Asking for the device passcode once, and never again.
 *
 * <p><b>This is the property the whole join exists for.</b> Without it, refreshing the tag list
 * means finding an Apple device and typing its screen-lock passcode every single time, which is
 * the difference between a feature somebody uses and one they try once.
 *
 * <p>Joining is also what stops the app going quietly stale: a non-member reads with view keys it
 * holds a share of, and when those roll - expected whenever the circle's membership changes -
 * only a current member is given shares of the new ones. A non-member keeps its old keys, keeps
 * looking fine, and decrypts nothing new.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class FetchFromICloudMembershipTest {

    private static final String PASSCODE = "123456";

    private FakeICloudService icloud;
    private ActivityScenario<FetchFromICloudActivity> scenario;
    private KeychainMembershipRepository memberships;

    @Before
    public void forgetAnyMembership() {
        this.memberships = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil());
        this.memberships.forget().blockingAwait();
        // Finishing this flow writes real rows into the real database. Cleared here too,
        // because a test that crashed left its tags behind for whatever runs next.
        AccountBeaconsForTests.forgetThemAll();
    }

    @After
    public void putTheRealOneBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        AppDependencies.reset();
        this.memberships.forget().blockingAwait();
        AccountBeaconsForTests.forgetThemAll();
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

        onView(withId(R.id.icloud_passcode_input)).perform(replaceText(PASSCODE));
        Eventually.perform("unlock", () -> this.icloud.timesCalled("unlock") > 0,
                () -> onView(withId(R.id.icloud_primary_button)).perform(click()));
    }

    /** The first run asks, joins, and stores what came back. */
    @Test
    public void thefirstRunJoinsAndStoresTheMembership() {
        this.open(FakeICloudService.withTags());

        this.unlockWithTheFirstDevice();

        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> assertEquals(1, this.icloud.timesCalled("join")));
        Eventually.check(() -> assertTrue("the membership was not stored",
                this.memberships.get().blockingFirst().isPresent()));
    }

    /**
     * The passcode it enrols its own record under is a real generated secret.
     *
     * <p>Not the user's, not a constant, and not empty - enrolment refuses an empty one on the
     * grounds that a record enrolled under it "could be recovered by anyone".
     */
    @Test
    public void itenrolsUnderAGeneratedSecretRatherThanTheUsersPasscode() {
        this.open(FakeICloudService.withTags());

        this.unlockWithTheFirstDevice();
        Eventually.check(() -> assertNotNull(this.icloud.joinedWithPasscode()));

        final String used = this.icloud.joinedWithPasscode();

        assertTrue("the escrow passcode must be a generated secret",
                EscrowPasscode.isWellFormed(used));
        assertTrue("the user's passcode must never become the record's",
                !PASSCODE.equals(used));
    }

    /**
     * <b>The point of all of it.</b>
     *
     * <p>A later run resumes as the member and never reaches the device list, so nobody is asked
     * for a passcode a second time.
     */
    @Test
    public void alaterRunNeverAsksForAPasscodeAgain() {
        this.open(FakeICloudService.withTags());
        this.unlockWithTheFirstDevice();
        Eventually.check(() -> assertTrue(this.memberships.get().blockingFirst().isPresent()));
        this.scenario.close();
        AppDependencies.reset();

        this.open(FakeICloudService.withTags());

        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));
        assertEquals("a second run must not unlock", 0, this.icloud.timesCalled("unlock"));
        assertEquals("a second run must not join again", 0, this.icloud.timesCalled("join"));
        assertEquals("it should have read as the member it already is",
                1, this.icloud.timesCalled("resume"));
    }

    /** And the device list is never shown on that later run - there is nothing to choose. */
    @Test
    public void alaterRunDoesNotShowTheDeviceList() {
        this.open(FakeICloudService.withTags());
        this.unlockWithTheFirstDevice();
        Eventually.check(() -> assertTrue(this.memberships.get().blockingFirst().isPresent()));
        this.scenario.close();
        AppDependencies.reset();

        this.open(FakeICloudService.withTags());

        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));
        assertTrue("the device list should never have appeared",
                !isShown(R.id.icloud_device_container));
    }

    /**
     * A membership the account no longer honours falls back to asking, rather than failing.
     *
     * <p>Removing this app's peer is how somebody revokes it, so this is a state a real user
     * creates deliberately - and the right answer is the first-run flow, not an error screen.
     */
    @Test
    public void amembershipTheAccountNoLongerHonoursAsksAgain() {
        this.open(FakeICloudService.withTags());
        this.unlockWithTheFirstDevice();
        Eventually.check(() -> assertTrue(this.memberships.get().blockingFirst().isPresent()));
        this.scenario.close();
        AppDependencies.reset();

        this.open(FakeICloudService.withTags().whereTheMembershipNoLongerWorks());

        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
    }

    /** And it forgets the dead membership, or every later run retries keys that cannot work. */
    @Test
    public void adeadMembershipIsForgotten() {
        this.open(FakeICloudService.withTags());
        this.unlockWithTheFirstDevice();
        Eventually.check(() -> assertTrue(this.memberships.get().blockingFirst().isPresent()));
        this.scenario.close();
        AppDependencies.reset();

        this.open(FakeICloudService.withTags().whereTheMembershipNoLongerWorks());
        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));

        Eventually.check(() -> assertTrue("the unusable membership was kept",
                this.memberships.get().blockingFirst().isEmpty()));
    }
}
