package dev.wander.android.opentagviewer.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.containsString;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.FetchFromICloudActivity;
import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.Shot;
import dev.wander.android.opentagviewer.db.AccountBeaconsForTests;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.BundleBuilder;
import dev.wander.android.opentagviewer.python.icloud.FakeICloudService;
import dev.wander.android.opentagviewer.python.icloud.ICloudException;
import dev.wander.android.opentagviewer.python.icloud.ICloudFailure;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Screens for the wiki, captured from the running app.
 *
 * <p><b>A documentation tool, not a test.</b> It asserts only enough to know it photographed the
 * right screen - a shot of the wrong page is worse than none, because nobody checks a picture
 * against the code. Everything real about these screens is covered by the classes named beside
 * each capture.
 *
 * <p><b>Needs a device with a display.</b> {@code Shot.ofTheScreen} photographs the compositor,
 * which is the only way to catch a dialog and the page behind it together - and on the headless
 * managed device it returns black. It refuses to write a blank frame rather than producing one,
 * so a headless run leaves no files rather than ten useless ones.
 *
 * <pre>
 * ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=dev.wander.android.opentagviewer.ui.WikiScreenshotsTest
 * </pre>
 *
 * <p>Everything on screen is fabricated: the serials, the tag names and the code all come from
 * fakes. No real account is touched, and nothing here belongs to anybody.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class WikiScreenshotsTest {

    private static final String A_TAG = "wiki-shot-tag";
    private static final String ANOTHER_TAG = "wiki-shot-tag-2";
    private static final String A_NAME = "Bike";
    private static final String ANOTHER_NAME = "Keys";
    private static final String A_TEST_USER = "wikishots@example.invalid";

    private static final String A_PLIST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<plist version=\"1.0\"><dict>"
            + "<key>batteryLevel</key><integer>1</integer>"
            + "<key>model</key><string></string>"
            + "<key>pairingDate</key><date>2025-02-27T20:03:32Z</date>"
            + "<key>privateKey</key><dict><key>key</key><dict>"
            + "<key>data</key><data>bm90LWEtcmVhbC1rZXk=</data></dict></dict>"
            + "<key>productId</key><integer>21760</integer>"
            + "<key>stableIdentifier</key><array><string>2001~#0~#A0</string></array>"
            + "<key>systemVersion</key><string>2.0.73</string>"
            + "<key>vendorId</key><integer>76</integer>"
            + "</dict></plist>";

    private ActivityScenario<?> scenario;
    private OpenTagViewerDatabase db;
    private File written;

    /**
     * <b>Skipped per test, and the teardown knows setup never ran.</b>
     *
     * <p>Two wrong shapes were tried first, and each broke the suite a different way. In
     * {@code @Before} alone, the assumption skips the test body but JUnit runs {@code @After}
     * regardless, which tore down Intents that were never initialised and reported every test as
     * FAILED. Moved to {@code @BeforeClass}, the class is passed over in silence - and the
     * instrumentation then reports fewer results than the APK declares tests, so AGP calls the
     * whole run aborted: "Expected 640 tests, received 623", where the 17 missing are exactly the
     * contents of these three classes. Zero failures, and a red build that reads as a dead
     * emulator.
     *
     * <p>So the assumption stays here, where each test is reported as skipped and counted, and
     * {@link #putItBack} returns early rather than undoing work that was never done.
     */
    /** False when the assumption above skipped setup, so teardown undoes nothing. */
    private boolean setUpRan;

    @Before
    public void cleanSlate() {
        OnlyWhenCapturing.wasAskedFor();
        this.setUpRan = true;

        final Context context = getInstrumentation().getTargetContext();
        this.db = OpenTagViewerDatabase.getInstance(context);
        this.written = new File(context.getCacheDir(), "wiki-shot.zip");

        this.forgetEverything();

        Intents.init();
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(new ActivityResult(
                Activity.RESULT_OK, new Intent().setData(Uri.fromFile(this.written))));
    }

    @After
    public void putItBack() {
        if (!this.setUpRan) {
            return;
        }

        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();
        this.forgetEverything();
        if (this.written != null) {
            this.written.delete();
        }
    }

    /**
     * Every tag, not only the ones this class wrote.
     *
     * <p><b>A screenshot needs a screen nobody else has touched.</b> Deleting by id is right for
     * a test, which cares about its own rows; it is not enough here. A run abandoned half way -
     * and several were, while this was being built - leaves another fixture's tags behind, and
     * the next capture then finds two tags called "Bike" and dies with
     * AmbiguousViewMatcherException, or worse photographs a list with somebody else's leftovers
     * in it.
     *
     * <p><b>Safe because of what this is.</b> A documentation tool, run by hand against a
     * throwaway emulator that {@code connectedDebugAndroidTest} uninstalls the app from anyway.
     * It is emphatically not safe on a device holding real imported tags, which is why it lives
     * here rather than in a shared helper an ordinary test might reach for.
     */
    private void forgetEverything() {
        AccountBeaconsForTests.forgetThemAll();

        for (final OwnedBeacon leftover : this.db.ownedBeaconDao().getAll()) {
            this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(leftover.id).build());
            this.db.beaconNamingRecordDao()
                    .delete(BeaconNamingRecord.builder().id(leftover.id).build());
        }
        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
        new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil()).forget().blockingAwait();
    }

    private void openTheICloudFlow(final FakeICloudService fake) {
        AppDependencies.replaceICloud(() -> fake);
        this.scenario = ActivityScenario.launch(FetchFromICloudActivity.class);
    }

    // ---------------------------------------------------------------- the iCloud flow

    /** 1 and 2: choosing whose passcode you have, then typing it. */
    @Test
    public void athepasscodeSteps() {
        this.openTheICloudFlow(FakeICloudService.withTags());

        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("icloud-1-which-device");

        onView(withText(containsString(FakeICloudService.AN_IPHONE.getSerial()))).perform(click());

        Eventually.check(() -> onView(withId(R.id.icloud_passcode_input))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("icloud-2-enter-passcode");
    }

    /** 3: the same step after Apple declined the code. See FetchFromICloudErrorsTest. */
    @Test
    public void bthewrongPasscode() {
        final FakeICloudService fake = FakeICloudService.withTags();
        fake.failUnlockWith(new ICloudException(ICloudFailure.PASSCODE_REJECTED,
                "The escrow service did not accept that passcode."));
        this.openTheICloudFlow(fake);

        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
        onView(withText(containsString(FakeICloudService.AN_IPHONE.getSerial()))).perform(click());

        Eventually.check(() -> onView(withId(R.id.icloud_passcode_input))
                .check(matches(isDisplayed())));
        onView(withId(R.id.icloud_passcode_input)).perform(replaceText("000000"));

        final long before = fake.timesCalled("unlock");
        Eventually.perform("unlock", () -> fake.timesCalled("unlock") > before,
                () -> onView(withId(R.id.icloud_primary_button)).perform(click()));

        Eventually.check(() -> onView(withId(R.id.icloud_passcode_error_container))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("icloud-3-wrong-passcode");
    }

    /** 4 and 5: what was found, then what this app put on the account. */
    @Test
    public void ctheresultsAndTheRegisteredDevice() {
        final FakeICloudService fake = FakeICloudService.withTags();
        this.openTheICloudFlow(fake);

        Eventually.check(() -> onView(withId(R.id.icloud_device_container))
                .check(matches(isDisplayed())));
        onView(withText(containsString(FakeICloudService.AN_IPHONE.getSerial()))).perform(click());

        Eventually.check(() -> onView(withId(R.id.icloud_passcode_input))
                .check(matches(isDisplayed())));
        onView(withId(R.id.icloud_passcode_input)).perform(replaceText("123456"));

        final long before = fake.timesCalled("unlock");
        Eventually.perform("unlock", () -> fake.timesCalled("unlock") > before,
                () -> onView(withId(R.id.icloud_primary_button)).perform(click()));

        Eventually.check(() -> onView(withId(R.id.icloud_results_container))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("icloud-4-tags-found");

        // Next rather than Done, because this run registered a device - see
        // TheDeviceNoteIsShownOnceTest for why a re-read does not reach this page.
        onView(withId(R.id.icloud_primary_button)).perform(click());

        Eventually.check(() -> onView(withId(R.id.icloud_registered_container))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("icloud-5-device-registered");
    }

    /** 6: an account with nothing that can ever unlock a keychain. Final, not a retry. */
    @Test
    public void dtheaccountWithNothingToRecoverFrom() {
        this.openTheICloudFlow(FakeICloudService.withNothingToRecoverFrom());

        Eventually.check(() -> onView(withId(R.id.icloud_no_tags_container))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("icloud-6-no-tags");
    }

    /** 7: and the one that really is worth trying again later. */
    @Test
    public void etheserviceHavingABadDay() {
        this.openTheICloudFlow(FakeICloudService.whereTheServiceIsUnsure());

        Eventually.check(() -> onView(withId(R.id.icloud_retry_container))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("icloud-7-service-unsure");
    }

    // ---------------------------------------------------------------- sharing tags

    private void seedTwoTags() {
        final long importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2").importedAt(1_700_000_000_000L).exportedAt(1_699_000_000_000L)
                .sourceUser(A_TEST_USER).exportedVia("OpenTagViewer.wizard:1.4.0").build());

        for (final String[] tag : new String[][] {{A_TAG, A_NAME}, {ANOTHER_TAG, ANOTHER_NAME}}) {
            this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                    .id(tag[0]).importId(importId).content(A_PLIST)
                    .version("0.0.2").fromAccount(false).isRemoved(false).build());
            this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                    .id(tag[0]).importId(importId).version("0.0.2").isRemoved(false)
                    .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<plist version=\"1.0\"><dict>"
                            + "<key>identifier</key><string>" + tag[0] + "</string>"
                            + "<key>associatedBeacon</key><string>" + tag[0] + "</string>"
                            + "<key>name</key><string>" + tag[1] + "</string>"
                            + "</dict></plist>").build());
        }
    }

    /** 8, 9 and 10: select tags, choose Export Tags, read the code. */
    @Test
    public void fsharingATag() {
        this.seedTwoTags();

        AppDependencies.replaceBundleBuilder((accessories, via, user, at) -> {
            final Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("OPENTAGVIEWER.yml", ("via: " + via).getBytes());
            return new BundleBuilder.Built(entries, null);
        });

        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);

        Eventually.check(() -> onView(withText(A_NAME)).check(matches(isDisplayed())));
        onView(withText(A_NAME)).perform(longClick());

        // Two selected, so the screenshot shows what a multiple selection looks like rather than
        // a single row that could be mistaken for a tap.
        Eventually.check(() -> onView(withId(R.id.selection_menu_button))
                .check(matches(isDisplayed())));
        onView(withText(ANOTHER_NAME)).perform(click());
        Shot.ofTheScreen("export-1-tags-selected");

        onView(withId(R.id.selection_menu_button)).perform(click());
        Eventually.check(() -> onView(withText(R.string.export_tags))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("export-2-menu");

        onView(withText(R.string.export_tags)).perform(click());

        Eventually.check(() -> onView(withId(R.id.exported_bundle_code))
                .check(matches(isDisplayed())));
        Shot.ofTheScreen("export-3-the-code");
    }
}
