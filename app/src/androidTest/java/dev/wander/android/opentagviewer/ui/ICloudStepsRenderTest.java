package dev.wander.android.opentagviewer.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import com.google.android.material.progressindicator.CircularProgressIndicator;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.db.AccountBeaconsForTests;
import dev.wander.android.opentagviewer.FetchFromICloudActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.icloud.FakeICloudService;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Every step of the iCloud screen, drawn from the running activity.
 *
 * <p><b>Captured from the real screen rather than an inflated layout.</b> The layout cannot be
 * inflated on its own - {@code CircularProgressIndicator} refuses outside an activity - and this
 * is the more honest picture anyway: it is what ships, with the real theme, the real inflater and
 * the real Material widgets, rather than an approximation assembled for the test's convenience.
 *
 * <p>The pictures are for a person to look at; the spacing on these screens was set from them.
 * What is asserted is the thing a picture cannot tell you: that the step actually has height.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ICloudStepsRenderTest {

    private ActivityScenario<FetchFromICloudActivity> scenario;
    private KeychainMembershipRepository memberships;

    @Before
    public void forgetAnyStoredMembership() {
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
        AppDependencies.replaceICloud(() -> fake);
        this.scenario = ActivityScenario.launch(FetchFromICloudActivity.class);
    }

    private boolean isShown(final int id) {
        final boolean[] shown = {false};
        this.scenario.onActivity(a -> shown[0] = a.findViewById(id).getVisibility() == View.VISIBLE);
        return shown[0];
    }

    @Test
    public void thedeviceListAndPasscodeSteps() throws IOException {
        final FakeICloudService fake = FakeICloudService.withTags();
        this.open(fake);

        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
        this.capture("icloud_1_devices.png", R.id.icloud_device_container);

        Eventually.perform("a device", () -> isShown(R.id.icloud_passcode_container),
                () -> onView(withText(containsString(FakeICloudService.AN_IPHONE.getSerial())))
                        .perform(click()));
        this.capture("icloud_2_passcode.png", R.id.icloud_passcode_container);
    }

    @Test
    public void theoverviewOfWhatWasFound() throws IOException {
        final FakeICloudService fake =
                FakeICloudService.withTags().alsoSkipping("My MacBook", "My iPhone");
        this.open(fake);

        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
        Eventually.perform("a device", () -> isShown(R.id.icloud_passcode_container),
                () -> onView(withText(containsString(FakeICloudService.AN_IPHONE.getSerial())))
                        .perform(click()));

        onView(withId(R.id.icloud_passcode_input)).perform(replaceText("123456"));
        Eventually.perform("unlock", () -> fake.timesCalled("fetch") > 0,
                () -> onView(withId(R.id.icloud_primary_button)).perform(click()));

        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));
        this.capture("icloud_3_results.png", R.id.icloud_results_container);
    }

    /**
     * The spinner actually turns.
     *
     * <p><b>A screenshot cannot tell you this</b>, which is the whole problem: a still frame of a
     * rotating arc and a static icon are the same picture, and @parawanderer reasonably asked
     * which one it was. So this draws it twice, a few frames apart, and insists the pixels
     * changed. A spinner that had quietly stopped - or was never a spinner - fails here and
     * nowhere else.
     */
    /**
     * It is a real progress indicator, not a picture of one.
     *
     * <p><b>What this cannot prove is that it turns</b>, and the attempt is worth recording so
     * nobody repeats it: drawing the view to a bitmap twice, a quarter-second apart, produced
     * identical pixels even with the animator duration scale forced to 1. That is the headless
     * managed device, not the app - a view drawn by hand off-screen does not tick its animator -
     * and asserting on it would have been a test of this harness.
     *
     * <p>So this asserts the things that are true and checkable: the widget is Material's
     * indeterminate {@code CircularProgressIndicator}, the same one the sign-in screen uses, and
     * it is on screen while the account is being read. Whether it visibly spins is a question for
     * a device with a window, and it is answered by looking.
     */
    @Test
    public void thewaitShowsARealProgressIndicator() {
        this.open(FakeICloudService.withTags().takingItsTime(6000));

        Eventually.check(() -> onView(withId(R.id.icloud_loading_container))
                .check(matches(isDisplayed())));

        final boolean[] indeterminate = {false};
        final boolean[] shown = {false};
        this.scenario.onActivity(activity -> {
            final CircularProgressIndicator spinner = activity.findViewById(R.id.icloud_spinner);
            indeterminate[0] = spinner.isIndeterminate();
            shown[0] = spinner.isShown();
        });

        assertTrue("the wait must show an indeterminate indicator, not a fixed one",
                indeterminate[0]);
        assertTrue("the indicator is not on screen while the account is being read", shown[0]);
    }

    /** The screen somebody actually looks at first, and the one that looked wrong. */
    @Test
    public void thewaitingScreen() throws IOException {
        this.open(FakeICloudService.withTags().takingItsTime(4000));

        Eventually.check(() -> onView(withId(R.id.icloud_loading_container))
                .check(matches(isDisplayed())));
        this.capture("icloud_0_waiting.png", R.id.icloud_loading_container);
    }

    @Test
    public void thenoTagsScreen() throws IOException {
        this.open(FakeICloudService.withNothingToRecoverFrom());

        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_container))
                .check(matches(isDisplayed())));
        this.capture("icloud_4_no_tags.png", R.id.icloud_no_tags_container);
    }

    @Test
    public void theserviceHavingABadDayScreen() throws IOException {
        this.open(FakeICloudService.whereTheServiceIsUnsure());

        Eventually.check(() -> onView(withId(R.id.icloud_retry_container))
                .check(matches(isDisplayed())));
        this.capture("icloud_5_retry.png", R.id.icloud_retry_container);
    }

    /**
     * Draw the whole window, and insist the step in it has height.
     *
     * <p>The window rather than the step alone, because the spacing being judged is partly the
     * room above and below it - a step cropped to its own bounds hides exactly that.
     */
    private void capture(final String name, final int stepId) throws IOException {
        assertTrue(name + ": the step measured to nothing", heightOf(stepId) > 0);

        final Bitmap[] shot = new Bitmap[1];
        this.scenario.onActivity(activity -> {
            final View decor = activity.getWindow().getDecorView();
            final Bitmap bitmap = Bitmap.createBitmap(
                    Math.max(decor.getWidth(), 1), Math.max(decor.getHeight(), 1),
                    Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(bitmap));
            shot[0] = bitmap;
        });

        write(shot[0], name);
    }

    private int heightOf(final int stepId) {
        final int[] height = {0};
        this.scenario.onActivity(a -> height[0] = a.findViewById(stepId).getHeight());
        return height[0];
    }

    private static void write(final Bitmap bitmap, final String name) throws IOException {
        final String directory = androidx.test.platform.app.InstrumentationRegistry
                .getArguments().getString("additionalTestOutputDir");
        if (directory == null || bitmap == null) {
            return;
        }

        try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
