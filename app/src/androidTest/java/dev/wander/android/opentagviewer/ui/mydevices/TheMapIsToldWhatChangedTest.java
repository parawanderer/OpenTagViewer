package dev.wander.android.opentagviewer.ui.mydevices;

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
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.Espresso;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.AccountBeaconsForTests;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.icloud.FakeICloudService;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Telling the map that the device list changed.
 *
 * <p><b>The map is not rebuilt when it is returned to.</b> It reads the beacons once and keeps
 * them, so anything that adds tags has to say so on the way out - {@code isDeviceListChanged} in
 * the result - and the map re-reads when it sees it. Nothing crashes when that flag is wrong; the
 * tags are in the database, they are on the device list, and the map simply goes on showing what
 * it had. @parawanderer imported a real account and found an empty map.
 *
 * <p><b>The flag was destroyed by the thing that made it true.</b> Finishing an account read sets
 * it and then calls {@code recreate()} so the new tags appear - and {@code recreate()} builds a
 * new activity, where a plain field is false again. So the failure needed the whole sequence:
 * import, rebuild, leave. Any test that set the flag and left without the rebuild in between
 * would have passed.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class TheMapIsToldWhatChangedTest {

    private static final String PASSCODE = "123456";

    private FakeICloudService icloud;
    private KeychainMembershipRepository memberships;
    private ActivityScenario<MyDevicesListActivity> scenario;

    @Before
    public void startFromNothingImported() {
        final Context context = getInstrumentation().getTargetContext();

        this.memberships = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(context), new AppCryptographyUtil());
        this.memberships.forget().blockingAwait();
        AccountBeaconsForTests.forgetThemAll();

        this.icloud = FakeICloudService.withTags();
        AppDependencies.replaceICloud(() -> this.icloud);
    }

    @After
    public void putEverythingBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        AppDependencies.reset();
        AccountBeaconsForTests.forgetThemAll();
        this.memberships.forget().blockingAwait();
    }

    private boolean isShown(final int id) {
        final boolean[] shown = {false};
        this.scenario.onActivity(activity -> {
            final View found = activity.findViewById(id);
            shown[0] = found != null && found.getVisibility() == View.VISIBLE;
        });
        return shown[0];
    }

    /**
     * <b>Import from the account, then leave: the map is told.</b>
     */
    @Test
    public void animportFromTheAccountSurvivesTheListRebuildingItself() {
        this.scenario = ActivityScenario.launchActivityForResult(MyDevicesListActivity.class);

        Eventually.check(() -> onView(withId(R.id.my_devices_empty_fetch_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.my_devices_empty_fetch_button)).perform(click());

        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withText(containsString(
                FakeICloudService.AN_IPHONE.getSerial()))).perform(click()));

        onView(withId(R.id.icloud_passcode_input)).perform(replaceText(PASSCODE));
        Eventually.perform("unlock", () -> this.icloud.timesCalled("fetch") > 0,
                () -> onView(withId(R.id.icloud_primary_button)).perform(click()));

        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));
        onView(withId(R.id.icloud_primary_button)).perform(click());

        // What this app registered as on the Apple account, which the results step now leads to.
        Eventually.check(() -> onView(withId(R.id.icloud_registered_container))
                .check(matches(isDisplayed())));
        onView(withId(R.id.icloud_primary_button)).perform(click());

        // The list rebuilds itself here - which is what used to lose the flag.
        Eventually.check(() -> onView(withId(R.id.my_devices_list))
                .check(matches(isDisplayed())));

        Espresso.pressBackUnconditionally();

        Eventually.check(() -> assertEquals("the screen should have finished",
                Activity.RESULT_OK, this.scenario.getResult().getResultCode()));
        assertTrue("the map was not told the device list changed, so it will keep showing"
                        + " whatever it already had",
                this.scenario.getResult().getResultData()
                        .getBooleanExtra("isDeviceListChanged", false));
    }

    /**
     * And leaving without changing anything does not ask the map to re-read.
     *
     * <p>Worth pinning: the cheap way to make the test above pass is to send the flag every time,
     * which turns every visit to this screen into a full re-read and a round of location fetches
     * on the map.
     */
    @Test
    public void leavingWithoutImportingAnythingSaysNothingChanged() {
        this.scenario = ActivityScenario.launchActivityForResult(MyDevicesListActivity.class);

        Eventually.check(() -> onView(withId(R.id.my_devices_empty_fetch_button))
                .check(matches(isDisplayed())));

        Espresso.pressBackUnconditionally();

        Eventually.check(() -> assertEquals(
                Activity.RESULT_OK, this.scenario.getResult().getResultCode()));
        assertTrue("nothing changed, so the map should not be asked to re-read",
                !this.scenario.getResult().getResultData()
                        .getBooleanExtra("isDeviceListChanged", false));
    }
}
