package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Log;
import android.view.View;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.color.MaterialColors;

import java.io.File;
import java.io.FileOutputStream;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import dev.wander.android.opentagviewer.anisette.AdiDeviceIdentity;
import dev.wander.android.opentagviewer.db.AccountBeaconsForTests;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.ui.compat.TheNavigationBar;
import dev.wander.android.opentagviewer.python.icloud.FakeICloudService;
import dev.wander.android.opentagviewer.python.icloud.ICloudService;

/**
 * Reading the account, driven end to end with iCloud replaced.
 *
 * <p>Every state here needs an Apple account nobody can arrange on demand - one with no device
 * to recover from, one whose keychain service is having a bad afternoon, one that refuses a
 * passcode three times. A working account is in none of them, so without a fake these screens
 * could only ever be reasoned about, which is how a screen ends up telling somebody with a
 * perfectly good account that they permanently own no tags.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class FetchFromICloudFlowTest {

    private static final String PASSCODE = "123456";

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

    private void chooseTheFirstDevice() {
        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();

        Eventually.perform("the device button",
                () -> isShown(R.id.icloud_passcode_container),
                () -> onView(withText(containsString(FakeICloudService.AN_IPHONE.getSerial())))
                        .perform(click()));
        TestPace.afterAStep();
    }

    private void typeThePasscode() {
        final long before = this.icloud.timesCalled("unlock");

        onView(withId(R.id.icloud_passcode_input)).perform(replaceText(PASSCODE));
        TestPace.afterAStep();

        Eventually.perform("unlock", () -> this.icloud.timesCalled("unlock") > before,
                () -> onView(withId(R.id.icloud_primary_button)).perform(click()));
        TestPace.afterAStep();
    }

    private boolean isShown(final int id) {
        final boolean[] shown = {false};
        this.scenario.onActivity(activity ->
                shown[0] = activity.findViewById(id).getVisibility() == android.view.View.VISIBLE);
        return shown[0];
    }

    /**
     * <b>The Unlock button is not under the navigation bar.</b>
     *
     * <p>This is the screen from @parawanderer's report - a screenshot of the passcode step with
     * Unlock behind the gesture pill, on a phone whose bar is tall. Every screen is checked for
     * this in {@code NothingSitsUnderTheNavigationBarTest}; this one is checked here instead,
     * because it closes itself in {@code onCreate} without a usable iCloud session and this class
     * is what knows how to give it one. Rebuilding that setup there would be the copy that drifts.
     *
     * <p>The bar is invented rather than measured - see {@link TheNavigationBar} for why that is
     * the only way to ask this on a device whose real inset is zero.
     */
    @Test
    public void theunlockButtonStaysClearOfTheNavigationBar() {
        this.open(FakeICloudService.withTags());

        this.chooseTheFirstDevice();

        TheNavigationBar.doesNotCover(this.scenario, "FetchFromICloudActivity");
    }

    /** The whole errand: choose a device, unlock, see what is on the account. */
    @Test
    public void thewholeFlowReachesTheTagsOnTheAccount() {
        this.open(FakeICloudService.withTags().alsoSkipping("My MacBook", "My iPhone"));

        this.chooseTheFirstDevice();
        this.typeThePasscode();

        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.icloud_results_found))
                .check(matches(withText(containsString("2")))));
        Eventually.check(() -> onView(withText(containsString("Bike")))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();
    }

    /**
     * <b>And the results tell them what is now sitting on their Apple account.</b>
     *
     * <p>Connecting registered this app as a device. Apple will show that row next to "If you do
     * not recognise this device" with a Remove button, and removing it breaks the connection this
     * screen just made - hours later, somewhere else, with nothing linking the two.
     *
     * <p>{@code WhatWeRegisterAsIsLegibleTest} covers whether the card can be read. This covers
     * the part that test cannot see: that it is on the screen the user actually arrives at, below
     * the tags and reachable by scrolling. A card that inflates perfectly into a screen nobody
     * routes to would pass there and be worth nothing.
     */
    @Test
    public void theresultsSayWhatIsNowOnTheAppleAccount() {
        this.open(FakeICloudService.withTags().alsoSkipping("My MacBook", "My iPhone"));

        this.chooseTheFirstDevice();
        this.typeThePasscode();

        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();

        // The tags are not the last word: what this app put on the account comes after them.
        Eventually.check(() -> onView(withId(R.id.icloud_primary_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.icloud_primary_button)).perform(click());
        TestPace.afterAStep();

        Eventually.check(() -> onView(withId(R.id.icloud_registered_container))
                .check(matches(isDisplayed())));

        // **The tile has to describe the machine the app actually registers as.** Read from the
        // same profile the identity is built from rather than written out here, because a literal
        // would keep passing after the profile changed - and the failure it would hide is a
        // screen telling somebody to look for a device that is not on their account. Rule 11.
        final AdiDeviceIdentity.Hardware hardware = AdiDeviceIdentity.Hardware.DEFAULT;

        onView(withId(R.id.icloud_registered_device_name))
                .check(matches(withText(hardware.deviceListName())));
        // Model and OS share a line, and the serial sits behind a translated label, so these are
        // containment checks - the surrounding wording is the locale's business, the values are
        // not.
        onView(withId(R.id.icloud_registered_device_model))
                .check(matches(withText(containsString(hardware.marketingName()))));
        onView(withId(R.id.icloud_registered_device_model))
                .check(matches(withText(containsString(hardware.osVersion()))));
        onView(withId(R.id.icloud_registered_device_serial))
                .check(matches(withText(containsString(AdiDeviceIdentity.APP_SERIAL))));
        TestPace.afterAStep();

        onView(withId(R.id.icloud_registered_body)).perform(scrollTo());
        onView(withId(R.id.icloud_registered_body))
                .check(matches(withText(containsString(AdiDeviceIdentity.APP_SERIAL))));

        // The serial reaches the sentence as well as the tile. The resource holds a ^1 slot, and
        // a template that lost it expands to a sentence about "the serial" that never says which.
        onView(withId(R.id.icloud_registered_link))
                .check(matches(withText(containsString("account.apple.com"))));

        // **The only picture of this page assembled.** WhatWeRegisterAsIsLegibleTest inflates the
        // layout without an activity, so the tile is blank and the slots unexpanded there - the
        // chip and the link styling exist only once something has filled them in.
        this.writeShotOf(R.id.icloud_registered_container, "registered_page");
        TestPace.afterAStep();
    }

    private void writeShotOf(final int id, final String name) {
        final String dir = InstrumentationRegistry.getArguments()
                .getString("additionalTestOutputDir");
        if (dir == null) {
            return;
        }

        this.scenario.onActivity(activity -> {
            try {
                final View view = activity.findViewById(id);
                final Bitmap bitmap = Bitmap.createBitmap(
                        view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);

                // The container is transparent; without this the PNG is the page drawn on black.
                final Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(MaterialColors.getColor(
                        view, com.google.android.material.R.attr.colorSurface));
                view.draw(canvas);

                try (FileOutputStream out =
                             new FileOutputStream(new File(new File(dir), name + ".png"))) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            } catch (final Exception e) {
                // A screenshot explains a failure; it is never the reason for one.
                Log.w("FetchFromICloudFlowTest", "could not write " + name, e);
            }
        });
    }

    /**
     * <b>And that page is the end of the flow, not a detour off it.</b>
     *
     * <p>It was reached by a button that used to say Done and now says Next, so the risk is a
     * screen nobody can leave: the step is new, and the button it inherits is the one that used
     * to close the activity.
     */
    @Test
    public void thedeviceNoteIsTheLastStepAndCloses() {
        this.open(FakeICloudService.withTags());

        this.chooseTheFirstDevice();
        this.typeThePasscode();

        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));
        onView(withId(R.id.icloud_primary_button)).perform(click());

        Eventually.check(() -> onView(withId(R.id.icloud_registered_container))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();

        // Asking the activity, not Espresso: a click that closed the screen and a click that
        // missed both leave onView throwing, and only one of them is a failure.
        Eventually.perform("the done button", this::isFinishing,
                () -> onView(withId(R.id.icloud_primary_button)).perform(click()));
    }

    /**
     * Whether the screen has gone.
     *
     * <p>Asked of the scenario, not of the activity. {@code onActivity} throws
     * {@code "Cannot run onActivity since Activity has been destroyed already"} - so the obvious
     * spelling of this fails precisely when the answer is yes, and a test waiting for the screen
     * to close cannot use it to find out that the screen closed.
     */
    private boolean isFinishing() {
        return this.scenario.getState() == Lifecycle.State.DESTROYED;
    }

    /** The passcode goes to the device that was actually chosen, not the first in the list. */
    @Test
    public void thepasscodeGoesToTheChosenDevice() {
        this.open(FakeICloudService.withTags());

        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
        Eventually.perform("the second device",
                () -> isShown(R.id.icloud_passcode_container),
                () -> onView(withText(containsString(FakeICloudService.A_MAC.getSerial())))
                        .perform(click()));

        this.typeThePasscode();

        Eventually.check(() -> assertEquals(
                List.of(FakeICloudService.A_MAC.getSerial()), this.icloud.unlockedWith()));
    }

    /**
     * An account with nothing that can unlock its keychain.
     *
     * <p>Final, and the answer is the import path - so the screen offers that rather than a
     * retry that will never work.
     */
    @Test
    public void anaccountWithNothingToRecoverFromIsToldSo() {
        this.open(FakeICloudService.withNothingToRecoverFrom());

        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_shared_note))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.icloud_retry_container))
                .check(matches(not(isDisplayed()))));
        TestPace.afterAStep();
    }

    /**
     * <b>And a service having a bad day is emphatically not that screen.</b>
     *
     * <p>Collapsing the two tells somebody with a perfectly good account that they permanently
     * own no tags, and sends them off to find a friend with a Mac.
     */
    @Test
    public void aserviceHavingABadDayOffersARetryInstead() {
        this.open(FakeICloudService.whereTheServiceIsUnsure());

        Eventually.check(() -> onView(withId(R.id.icloud_retry_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_container))
                .check(matches(not(isDisplayed()))));
        TestPace.afterAStep();
    }

    /** Retrying starts a fresh session rather than reusing the one that failed. */
    @Test
    public void retryingAsksAgain() {
        this.open(FakeICloudService.whereTheServiceIsUnsure());
        Eventually.check(() -> onView(withId(R.id.icloud_retry_container))
                .check(matches(isDisplayed())));

        final long before = this.icloud.timesCalled("recoveryOptions");
        Eventually.perform("try again",
                () -> this.icloud.timesCalled("recoveryOptions") > before,
                () -> onView(withId(R.id.icloud_primary_button)).perform(click()));
    }

    /** An account with a Mac on it and no tags lands on the same advice, a step later. */
    @Test
    public void anaccountWithNoTagsOnItSaysSoAfterFetching() {
        this.open(FakeICloudService.withNoTagsOnTheAccount());

        this.chooseTheFirstDevice();
        this.typeThePasscode();

        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_container))
                .check(matches(isDisplayed())));
    }

    /**
     * A rejected passcode can be tried again, and the wording does not call it wrong.
     *
     * <p>FindMy.py's own first advice is to try the same one again, because the exchange has
     * been seen to fail intermittently and then succeed.
     */
    @Test
    public void arejectedPasscodeCanBeTriedAgain() {
        this.open(FakeICloudService.withTags().refusingThePasscode(1));

        this.chooseTheFirstDevice();
        this.typeThePasscode();

        Eventually.check(() -> onView(withId(R.id.icloud_passcode_error_container))
                .check(matches(isDisplayed())));
        Eventually.check(() -> onView(withId(R.id.icloud_attempts_text))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();

        this.typeThePasscode();

        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));
    }

    /**
     * Attempts run out rather than going on for ever.
     *
     * <p>Apple's escrow services generally cap attempts and what this one allows is not
     * established, which is a good reason not to find out on somebody's real account.
     */
    @Test
    public void theattemptsAreCapped() {
        this.open(FakeICloudService.withTags()
                .refusingThePasscode(ICloudService.MAX_UNLOCK_ATTEMPTS + 5));

        this.chooseTheFirstDevice();
        for (int i = 0; i < ICloudService.MAX_UNLOCK_ATTEMPTS; i++) {
            this.typeThePasscode();
        }

        Eventually.check(() -> assertTrue(
                "more attempts were spent than the cap allows",
                this.icloud.timesCalled("unlock") <= ICloudService.MAX_UNLOCK_ATTEMPTS));
        Eventually.check(() -> onView(withId(R.id.icloud_passcode_container))
                .check(matches(not(isDisplayed()))));
    }

    /** An empty passcode must not spend one of them. */
    @Test
    public void anemptyPasscodeSpendsNothing() {
        this.open(FakeICloudService.withTags());
        this.chooseTheFirstDevice();

        onView(withId(R.id.icloud_primary_button)).perform(click());

        assertEquals("an empty box must not cost an attempt", 0, this.icloud.timesCalled("unlock"));
    }

    /** The session is closed when the screen goes, or its sockets leak for the process's life. */
    @Test
    public void thesessionIsClosedOnTheWayOut() {
        this.open(FakeICloudService.withTags());
        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));

        this.scenario.close();
        this.scenario = null;

        Eventually.check(() -> assertTrue("the iCloud session was never closed",
                this.icloud.wasClosed()));
    }
}
