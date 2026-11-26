package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isNotChecked;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import android.content.Intent;
import android.database.Cursor;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.DeviceInfoActivity;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;

/**
 * Removing a tag imported from a file, from both screens that offer it: the keys always go, and
 * the location history goes only when the box is ticked.
 *
 * <p>What the repository does with each answer is pinned by
 * {@code RemovingATagForgetsItsKeysTest}. This checks the screens offer the choice, unticked, and
 * pass on what was chosen.
 *
 * <p>Seeds the real on-device database, as {@link RemoveAccountTagTest} does and for the same
 * reason, and deletes only its own rows by id, before and after.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class RemovingAFileTagTest {

    private static final String THE_TAG = "test-removing-a-file-tag";
    private static final String ITS_NAME = "Tag To Be Removed";
    private static final String A_TEST_USER = "removingafiletagtest@example.invalid";

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

    private OpenTagViewerDatabase db;
    private ActivityScenario<?> scenario;

    @Before
    public void seedTheDatabase() {
        this.db = OpenTagViewerDatabase.getInstance(getInstrumentation().getTargetContext());
        this.removeEverythingThisTestWrites();

        final long importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2")
                .importedAt(1_700_000_000_000L)
                .exportedAt(1_699_000_000_000L)
                .sourceUser(A_TEST_USER)
                .exportedVia("OpenTagViewer.wizard:test")
                .build());

        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(THE_TAG)
                .importId(importId)
                .content(A_PLIST)
                .version("0.0.2")
                .fromAccount(false)
                .isRemoved(false)
                .build());

        this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                .id(THE_TAG)
                .importId(importId)
                .version("0.0.2")
                .isRemoved(false)
                .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                        + "<key>identifier</key><string>" + THE_TAG + "</string>"
                        + "<key>name</key><string>" + ITS_NAME + "</string>"
                        + "</dict></plist>")
                .build());

        this.db.locationReportDao().insertAll(LocationReport.builder()
                .hashId("report-" + THE_TAG)
                .beaconId(THE_TAG)
                .publishedAt(1_000L)
                .description("Wi-Fi")
                .timestamp(1_000L)
                .confidence(0)
                .latitude(52.370216)
                .longitude(4.895168)
                .horizontalAccuracy(83)
                .status(144)
                .lastUpdate(1_000L)
                .provenance(LocationReport.PROVENANCE_APPLE)
                .build());
    }

    @After
    public void putTheDatabaseBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        this.removeEverythingThisTestWrites();
    }

    private void removeEverythingThisTestWrites() {
        // By id, never `clearAllTables`: this is the real database. Deleting the row cascades to
        // the history this test wrote.
        this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(THE_TAG).build());
        this.db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(THE_TAG).build());
        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
    }

    /** Whether the row is still in the table at all, hidden or not. */
    private boolean rowExists() {
        try (Cursor c = this.db.query(
                "SELECT COUNT(*) FROM OwnedBeacons WHERE id = ?", new Object[] {THE_TAG})) {
            c.moveToFirst();
            return c.getInt(0) > 0;
        }
    }

    private String storedPlist() {
        try (Cursor c = this.db.query(
                "SELECT content FROM OwnedBeacons WHERE id = ?", new Object[] {THE_TAG})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private int historyRows() {
        return this.db.locationReportDao().getInTimeRange(THE_TAG, 0L, 10_000L).size();
    }

    private void openTheListAndChooseRemove() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);
        Eventually.check(() -> onView(withText(ITS_NAME)).check(matches(isDisplayed())));

        onView(withText(ITS_NAME)).perform(longClick());
        Eventually.check(() -> onView(withId(R.id.selection_menu_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.selection_menu_button)).perform(click());
        onView(withText(R.string.remove_devices)).inRoot(isPlatformPopup()).perform(click());

        Eventually.check(() -> onView(withId(R.id.dialog_also_erase))
                .inRoot(isDialog()).check(matches(isDisplayed())));
    }

    /** Offered, worded for one tag, and unticked. */
    @Test
    public void theDialogOffersToEraseTheHistoryUnticked() {
        this.openTheListAndChooseRemove();

        onView(withId(R.id.dialog_also_erase)).inRoot(isDialog()).check(matches(allOf(
                isNotChecked(), withText(R.string.remove_tag_also_erase_history))));
    }

    /** Left unticked: the keys go, the history stays for a re-import to bring back. */
    @Test
    public void removingDeletesTheKeysAndKeepsTheHistory() {
        this.openTheListAndChooseRemove();

        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click());

        Eventually.check(() -> assertNull("the tag should no longer be listed",
                this.db.ownedBeaconDao().getById(THE_TAG)));
        assertNull("its private key is still in the database", this.storedPlist());
        assertEquals("its history should have been kept", 1, this.historyRows());
    }

    /** Ticked: the row goes, and its history with it. */
    @Test
    public void tickingTheBoxErasesTheHistoryToo() {
        this.openTheListAndChooseRemove();

        onView(withId(R.id.dialog_also_erase)).inRoot(isDialog()).perform(click());
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click());

        Eventually.check(() -> assertFalse("the row is still there", this.rowExists()));
        assertEquals("its history is still there", 0, this.historyRows());
    }

    /** The tag's own page offers the same choice and honours it. */
    @Test
    public void theTagPageErasesTheHistoryWhenTicked() {
        final Intent intent = new Intent(
                getInstrumentation().getTargetContext(), DeviceInfoActivity.class);
        intent.putExtra("beaconId", THE_TAG);
        this.scenario = ActivityScenario.launch(intent);

        Eventually.check(() -> onView(withId(R.id.page_menu_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.page_menu_button)).perform(click());
        onView(withText(R.string.remove_device)).inRoot(isPlatformPopup()).perform(click());

        Eventually.check(() -> onView(withId(R.id.dialog_also_erase))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withId(R.id.dialog_also_erase)).inRoot(isDialog()).perform(click());

        // Confirming finishes the page, so the click may report the activity gone.
        Eventually.perform("Confirm", () -> !this.rowExists(),
                () -> onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click()));

        assertEquals("its history is still there", 0, this.historyRows());
    }
}
