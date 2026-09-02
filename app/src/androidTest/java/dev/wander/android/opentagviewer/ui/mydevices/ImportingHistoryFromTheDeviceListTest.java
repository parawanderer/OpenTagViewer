package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBackUnconditionally;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.VerificationModes.times;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.ui.error.ErrorReportActivity;
import dev.wander.android.opentagviewer.util.export.HistoryCsvWriter;
import dev.wander.android.opentagviewer.util.history.HistoryImportException;
import dev.wander.android.opentagviewer.util.history.HistoryImportProgress;
import dev.wander.android.opentagviewer.util.history.HistoryImportResult;

/** The history picker and every result the My Devices screen promises to explain. */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ImportingHistoryFromTheDeviceListTest {
    private static final String TAG_ID = "history-import-ui-tag";
    private static final String TAG_NAME = "History Import UI Tag";
    private static final String UNKNOWN_ID = "history-import-ui-unknown";
    private static final String SOURCE_USER = "history-import-ui@example.invalid";
    private static final long TIMESTAMP =
            Instant.parse("2026-08-15T12:34:56Z").toEpochMilli();
    private static final String PLIST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
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

    private final List<File> files = new ArrayList<>();
    private OpenTagViewerDatabase db;
    private ActivityScenario<MyDevicesListActivity> scenario;
    private CountDownLatch showFakeMerge;
    private CountDownLatch finishFakeImport;

    @Before
    public void seedOneKnownTag() {
        final Context context = getInstrumentation().getTargetContext();
        this.db = OpenTagViewerDatabase.getInstance(context);
        this.forgetTestData();

        final long importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2")
                .importedAt(1_700_000_000_000L)
                .exportedAt(1_699_000_000_000L)
                .sourceUser(SOURCE_USER)
                .exportedVia("OpenTagViewer.wizard:test")
                .build());
        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(TAG_ID).importId(importId).content(PLIST).version("0.0.2")
                .fromAccount(false).isRemoved(false).build());
        this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                .id(TAG_ID).importId(importId).version("0.0.2").isRemoved(false)
                .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<plist version=\"1.0\"><dict>"
                        + "<key>identifier</key><string>" + TAG_ID + "</string>"
                        + "<key>name</key><string>" + TAG_NAME + "</string>"
                        + "</dict></plist>")
                .build());

        Intents.init();
    }

    @After
    public void cleanUp() {
        if (this.showFakeMerge != null) {
            this.showFakeMerge.countDown();
        }
        if (this.finishFakeImport != null) {
            this.finishFakeImport.countDown();
        }
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();
        this.forgetTestData();
        for (File file : this.files) {
            file.delete();
        }
    }

    @Test
    public void validArchiveReportsEveryCountRefreshesTheRowAndTellsTheMap() throws Exception {
        final String header = String.join(",", HistoryCsvWriter.requiredHeaders());
        final String csv = header + "\r\n"
                + row(TAG_ID, TIMESTAMP, "52.3702157", "added") + "\r\n"
                + row(TAG_ID, TIMESTAMP, "52.3702157", "duplicate") + "\r\n"
                + row(TAG_ID, TIMESTAMP + 1, "bad-latitude", "malformed") + "\r\n"
                + row(UNKNOWN_ID, TIMESTAMP + 2, "52.3702157", "unknown") + "\r\n";
        this.answerPickerWith(zip("history.csv", csv));
        this.openAndChooseHistory();

        final Context context = getInstrumentation().getTargetContext();
        final String counts = context.getString(
                R.string.history_import_result_counts, 4, 1, 1, 1, 1);
        Eventually.check(() -> onView(withText(containsString(counts))).inRoot(isDialog())
                .check(matches(isDisplayed())));
        onView(withId(R.id.history_import_progress)).check(doesNotExist());
        onView(withText(containsString(context.getString(
                        R.string.history_import_unknown_guidance))))
                .inRoot(isDialog())
                .check(matches(isDisplayed()));
        onView(withText(R.string.ok)).inRoot(isDialog()).perform(click());

        Eventually.check(() -> onView(allOf(
                withId(R.id.device_item_container), hasDescendant(withText(TAG_NAME))))
                .check(matches(not(hasDescendant(withText(R.string.no_last_location_known))))));

        pressBackUnconditionally();
        Eventually.check(() -> assertEquals(
                Activity.RESULT_OK, this.scenario.getResult().getResultCode()));
        assertTrue(this.scenario.getResult().getResultData()
                .getBooleanExtra("isDeviceListChanged", false));
    }

    @Test
    public void aLongImportShowsAndUpdatesProgressBeforeItsResult() throws Exception {
        final CountDownLatch startedReading = new CountDownLatch(1);
        final CountDownLatch reachedMerge = new CountDownLatch(1);
        this.showFakeMerge = new CountDownLatch(1);
        this.finishFakeImport = new CountDownLatch(1);
        AppDependencies.replaceHistoryImporter((archive, progress) -> {
            progress.changed(HistoryImportProgress.Stage.READING, 0, 0);
            startedReading.countDown();
            awaitTestLatch(this.showFakeMerge);
            progress.changed(HistoryImportProgress.Stage.MERGING, 4, 10);
            reachedMerge.countDown();
            awaitTestLatch(this.finishFakeImport);
            return new HistoryImportResult(10, 0, 10, 0, 0);
        });
        this.answerPickerWith(zip("ignored.txt", "the fake importer owns this"));
        this.openAndChooseHistory();

        assertTrue(startedReading.await(5, TimeUnit.SECONDS));
        Eventually.check(() -> onView(withId(R.id.history_import_progress))
                .inRoot(isDialog()).check((view, missing) -> {
                    if (missing != null) {
                        throw missing;
                    }
                    assertTrue(((CircularProgressIndicator) view).isIndeterminate());
                }));

        this.showFakeMerge.countDown();
        assertTrue(reachedMerge.await(5, TimeUnit.SECONDS));
        Eventually.check(() -> onView(withId(R.id.history_import_progress))
                .inRoot(isDialog()).check((view, missing) -> {
                    if (missing != null) {
                        throw missing;
                    }
                    final CircularProgressIndicator indicator =
                            (CircularProgressIndicator) view;
                    assertFalse(indicator.isIndeterminate());
                    assertEquals(10, indicator.getMax());
                    assertEquals(4, indicator.getProgress());
                }));

        this.finishFakeImport.countDown();
        Eventually.check(() -> onView(withText(R.string.history_import_complete_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withId(R.id.history_import_progress)).check(doesNotExist());
    }

    private static void awaitTestLatch(final CountDownLatch latch)
            throws HistoryImportException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new HistoryImportException(
                        HistoryImportException.Reason.UNEXPECTED,
                        "test import was never released");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new HistoryImportException(
                    HistoryImportException.Reason.UNEXPECTED,
                    "test import was interrupted",
                    error);
        }
    }

    @Test
    public void legacyNameOnlyExportExplainsWhyItIsUnsafe() throws Exception {
        final List<String> currentHeaders = HistoryCsvWriter.requiredHeaders();
        final String legacyHeader = String.join(",",
                currentHeaders.subList(0, currentHeaders.size() - 1));
        this.answerPickerWith(zip("Wallet.csv", legacyHeader + "\r\n"));
        this.openAndChooseHistory();

        Eventually.check(() -> onView(withText(R.string.history_import_legacy_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withId(R.id.history_import_progress)).check(doesNotExist());
        onView(withText(R.string.history_import_legacy_message)).inRoot(isDialog())
                .check(matches(isDisplayed()));
    }

    @Test
    public void damagedOrUnsupportedArchiveGetsItsOwnExplanation() throws Exception {
        this.answerPickerWith(zip("readme.txt", "not an Android history export"));
        this.openAndChooseHistory();

        Eventually.check(() -> onView(withText(R.string.history_import_invalid_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withId(R.id.history_import_progress)).check(doesNotExist());
        onView(withText(R.string.history_import_invalid_message)).inRoot(isDialog())
                .check(matches(isDisplayed()));
        intended(hasComponent(ErrorReportActivity.class.getName()), times(0));
    }

    @Test
    public void readFailureUsesTheGenericFailureMessage() {
        final File missing = new File(
                getInstrumentation().getTargetContext().getCacheDir(),
                "history-import-file-that-does-not-exist.zip");
        missing.delete();
        this.answerPickerWith(missing);
        this.openAndChooseHistory();

        Eventually.check(() -> onView(withText(R.string.history_import_failed_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withId(R.id.history_import_progress)).check(doesNotExist());
        onView(withText(R.string.history_import_failed_message)).inRoot(isDialog())
                .check(matches(isDisplayed()));
    }

    @Test
    public void anUnexpectedFailureReachesTheBugPageWithItsRootCause() throws Exception {
        AppDependencies.replaceHistoryImporter((archive, progress) -> {
            throw new RuntimeException(
                    "history wrapper",
                    new IllegalStateException("parser invariant failed"));
        });
        intending(hasComponent(ErrorReportActivity.class.getName()))
                .respondWith(new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null));
        this.answerPickerWith(zip("ignored.txt", "the fake importer owns this"));
        this.openAndChooseHistory();

        Eventually.check(() -> intended(allOf(
                hasComponent(ErrorReportActivity.class.getName()),
                hasExtra(ErrorReportActivity.EXTRA_BODY,
                        R.string.error_report_body_history_import),
                hasExtra(ErrorReportActivity.EXTRA_CAUSE,
                        "IllegalStateException: parser invariant failed"))));
        onView(withId(R.id.history_import_progress)).check(doesNotExist());
    }

    @Test
    public void cancellingThePickerChangesNeitherHistoryNorTheMapResult() {
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null));

        this.openAndChooseHistory();

        assertEquals(0, this.db.locationReportDao()
                .getInTimeRange(TAG_ID, 0, Long.MAX_VALUE).size());
        onView(withText(R.string.history_import_complete_title)).check(doesNotExist());
        onView(withText(R.string.history_import_failed_title)).check(doesNotExist());

        pressBackUnconditionally();
        Eventually.check(() -> assertEquals(
                Activity.RESULT_OK, this.scenario.getResult().getResultCode()));
        assertFalse(this.scenario.getResult().getResultData()
                .getBooleanExtra("isDeviceListChanged", false));
    }

    private void openAndChooseHistory() {
        this.scenario = ActivityScenario.launchActivityForResult(MyDevicesListActivity.class);
        Eventually.check(() -> onView(withText(TAG_NAME)).check(matches(isDisplayed())));
        onView(withId(R.id.page_menu_button)).perform(click());
        Eventually.check(() -> onView(withText(R.string.import_history))
                .inRoot(isPlatformPopup()).check(matches(isDisplayed())));
        onView(withText(R.string.import_history)).inRoot(isPlatformPopup()).perform(click());
    }

    private void answerPickerWith(final File file) {
        final Intent result = new Intent().setData(Uri.fromFile(file));
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(new Instrumentation.ActivityResult(Activity.RESULT_OK, result));
    }

    private File zip(final String entryName, final String contents) throws Exception {
        final File file = File.createTempFile(
                "history-import-", ".zip",
                getInstrumentation().getTargetContext().getCacheDir());
        this.files.add(file);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(contents.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    private static String row(
            final String beaconId,
            final long timestamp,
            final String latitude,
            final String description) {
        return "2026-08-15T12:34:56Z,2026-08-15 12:34:56Z," + timestamp + ","
                + latitude + ",4.8951679,12,2,1,2026-08-15T12:35:56Z,"
                + description + "," + (timestamp + 60_000L) + "," + latitude
                + ",4.8951679,true," + beaconId;
    }

    private void forgetTestData() {
        this.db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(TAG_ID).build());
        this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(TAG_ID).build());
        for (Import stale : this.db.importDao().getImportsFromUser(SOURCE_USER)) {
            this.db.importDao().delete(stale);
        }
    }
}
