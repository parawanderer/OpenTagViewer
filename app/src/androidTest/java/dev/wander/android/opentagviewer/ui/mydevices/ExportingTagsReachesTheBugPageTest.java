package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasType;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;

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

import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.BundleBuilder;
import dev.wander.android.opentagviewer.ui.error.ErrorReportActivity;

/**
 * Exporting tags, driven from the list, when the app cannot do it.
 *
 * <p><b>The whole flow, not the pieces.</b> Its sibling proves {@code TagExporter} refuses and
 * throws the right things; this one proves the screen acts on that - long press a tag, pick
 * Export Tags, choose somewhere to save it, and then have the builder fail. What the person is
 * shown at that moment is the entire value of the feature to them, and it is reached through five
 * layers that each have their own idea of what an error is.
 *
 * <p>The state is not reachable on a working device: it means a Python interpreter that will not
 * start. {@code AppDependencies.replaceBundleBuilder} is why it can be tested at all.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ExportingTagsReachesTheBugPageTest {

    private static final String A_TAG = "export-flow-test-tag";
    private static final String A_NAME = "Export Me";
    private static final String A_TEST_USER = "exportflow@example.invalid";

    /**
     * A real accessory plist, not a skeleton.
     *
     * <p>The first version of this carried only `identifier` and `name`, and the tag never
     * appeared in the list at all - `BeaconDataParser` reads `privateKey` and the rest
     * unconditionally, so a partial record is dropped before anything is drawn. The test failed
     * on "no view matching Export Me", which points at the list and not at the fixture.
     */
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

    /** The naming record is a different shape: an identifier and what the user calls it. */
    private static final String A_NAMING_RECORD =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                    + "<key>identifier</key><string>" + A_TAG + "</string>"
                    + "<key>name</key><string>" + A_NAME + "</string></dict></plist>";

    private OpenTagViewerDatabase db;
    private ActivityScenario<MyDevicesListActivity> scenario;
    private File written;

    @Before
    public void seedOneExportableTag() {
        final Context context = getInstrumentation().getTargetContext();
        this.db = OpenTagViewerDatabase.getInstance(context);
        this.forgetIt();

        final long importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2")
                .importedAt(1_700_000_000_000L)
                .exportedAt(1_699_000_000_000L)
                .sourceUser(A_TEST_USER)
                .exportedVia("OpenTagViewer.wizard:test")
                .build());

        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(A_TAG).importId(importId).content(A_PLIST)
                .version("0.0.2").fromAccount(false).isRemoved(false).build());

        this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                .id(A_TAG).importId(importId).content(A_NAMING_RECORD)
                .version("0.0.2").isRemoved(false).build());

        this.written = new File(context.getCacheDir(), "export-flow-test.zip");

        Intents.init();

        // The document picker, answered with somewhere this test owns. Everything else the screen
        // can fire is named too - a matcher broad enough to catch the launch intent stubs the
        // activity under test and hangs the run.
        intending(anyOf(
                hasAction(Intent.ACTION_CREATE_DOCUMENT),
                hasComponent(ErrorReportActivity.class.getName())))
                .respondWith(new ActivityResult(
                        Activity.RESULT_OK,
                        new Intent().setData(Uri.fromFile(this.written))));
    }

    @After
    public void putEverythingBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();
        this.forgetIt();
        if (this.written != null) {
            this.written.delete();
        }
    }

    private void forgetIt() {
        this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(A_TAG).build());
        this.db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(A_TAG).build());
        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
    }

    /** Long press the tag, open the selection menu, and pick Export Tags. */
    private void exportIt() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);

        dev.wander.android.opentagviewer.Eventually.check(() ->
                onView(withText(A_NAME)).check(matches(isDisplayed())));
        onView(withText(A_NAME)).perform(longClick());

        dev.wander.android.opentagviewer.Eventually.check(() ->
                onView(withId(R.id.selection_menu_button)).check(matches(isDisplayed())));
        onView(withId(R.id.selection_menu_button)).perform(click());

        dev.wander.android.opentagviewer.Eventually.check(() ->
                onView(withText(R.string.export_tags)).check(matches(isDisplayed())));
        onView(withText(R.string.export_tags)).perform(click());
    }

    /**
     * <b>What the picker is asked for, since the picker itself is Android's and stubbed.</b>
     *
     * <p>Espresso cannot drive the document picker - it is another app in another process - so
     * every test here intercepts the intent instead. That makes the request the only part of it
     * this repo can be responsible for, and the parts that matter are the ones nothing else
     * checks: a suggested name ending in {@code .zip}, and {@code application/zip} so the picker
     * offers somewhere sensible. Get the extension wrong and the recipient receives a file this
     * app will not offer to import.
     */
    @Test
    public void athepickerIsAskedForAZipWithAName() {
        AppDependencies.replaceBundleBuilder((accessories, via, user, at) -> {
            final Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("OPENTAGVIEWER.yml", ("via: " + via).getBytes());
            return new BundleBuilder.Built(entries, null);
        });

        this.exportIt();

        dev.wander.android.opentagviewer.Eventually.check(() -> intended(allOf(
                hasAction(Intent.ACTION_CREATE_DOCUMENT),
                hasType("application/zip"))));
    }

    /**
     * <b>The one this exists for: a builder that cannot run lands on the report page.</b>
     *
     * <p>And on the page's <i>export</i> wording, not the protocol one. "Something came back that
     * this app does not know how to read" is false here - nothing came back from anywhere, the app
     * failed to build a file - and a page that misdescribes what happened is worse than a generic
     * one, because the reader corrects for it and stops trusting the rest.
     */
    @Test
    public void abuilderThatCannotRunSendsThemToTheReportPage() {
        AppDependencies.replaceBundleBuilder((accessories, via, user, at) -> {
            throw new BundleBuilder.BundleBuildException("Python did not start.");
        });

        this.exportIt();

        dev.wander.android.opentagviewer.Eventually.check(() -> intended(allOf(
                hasComponent(ErrorReportActivity.class.getName()),
                hasExtra(ErrorReportActivity.EXTRA_BODY, R.string.error_report_body_export))));
    }

    /** And the cause travels with them, so the report says something. */
    @Test
    public void bthecauseIsCarriedToThePage() {
        AppDependencies.replaceBundleBuilder((accessories, via, user, at) -> {
            throw new BundleBuilder.BundleBuildException("Python did not start.");
        });

        this.exportIt();

        dev.wander.android.opentagviewer.Eventually.check(() -> intended(hasExtra(
                ErrorReportActivity.EXTRA_CAUSE,
                "BundleBuildException: Python did not start.")));
    }

    /**
     * <b>And a working export does not go near it.</b>
     *
     * <p>Worth its own test, because a page that appears when nothing is wrong is one people
     * learn to dismiss - and then it is worth nothing on the day it is right. The code dialog
     * turning up instead is what success looks like here.
     */
    @Test
    public void caworkingExportShowsTheCodeAndNoBugPage() {
        AppDependencies.replaceBundleBuilder((accessories, via, user, at) -> {
            final Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("OPENTAGVIEWER.yml", ("via: " + via + "\n").getBytes());
            return new BundleBuilder.Built(entries, null);
        });

        this.exportIt();

        dev.wander.android.opentagviewer.Eventually.check(() ->
                onView(withId(R.id.exported_bundle_code)).check(matches(isDisplayed())));

        // The two things that make the code usable: it says it cannot be shown again, and it
        // says to send it separately from the file.
        onView(withId(R.id.exported_bundle_not_recoverable)).check(matches(isDisplayed()));
        onView(withId(R.id.exported_bundle_body)).check(matches(isDisplayed()));
    }
}
