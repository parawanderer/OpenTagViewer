package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;

import android.content.Context;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;

/**
 * The device list noticing locations that arrived while it was in the background.
 *
 * <p><b>Reported by @parawanderer, and the interesting part is that nothing was lost.</b> A tag
 * read "No last location known"; opening its history and paging back a few days found locations
 * perfectly well; going back to the list still said "No last location known". Reaching the same
 * list a different way - through the map - showed "Last Updated: 3 days ago". The reports had
 * been in the database the whole time.
 *
 * <p>The list loaded its locations once, in {@code onCreate}, and refreshed them afterwards only
 * when the device page reported a removal or a rename. Fetching history is neither, so the
 * screen had no reason to look again - and a stale screen is indistinguishable from missing data
 * to the person reading it. Which is why this is worth a test: the failure mode is a correct
 * database and a wrong screen, and no amount of testing the repository would find it.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class TheDeviceListNoticesNewLocationsTest {

    private static final String A_TEST_USER = "device-list-refresh@example.com";
    private static final String THE_TAG = "a-wallet";
    private static final String THE_NAME = "Shane's Wallet";

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
    private ActivityScenario<MyDevicesListActivity> scenario;

    @Before
    public void seedAtagWithNoLocationsYet() {
        final Context context = getInstrumentation().getTargetContext();
        this.db = OpenTagViewerDatabase.getInstance(context);
        this.forgetIt();

        final long importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2").importedAt(1_700_000_000_000L).exportedAt(1_699_000_000_000L)
                .sourceUser(A_TEST_USER).exportedVia("OpenTagViewer.wizard:test").build());

        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(THE_TAG).importId(importId).content(A_PLIST).version("0.0.2")
                .fromAccount(false).isRemoved(false)
                .build());

        this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                .id(THE_TAG).importId(importId).version("0.0.2").isRemoved(false)
                .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                        + "<key>identifier</key><string>" + THE_TAG + "</string>"
                        + "<key>name</key><string>" + THE_NAME + "</string>"
                        + "</dict></plist>")
                .build());
    }

    @After
    public void putEverythingBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        this.forgetIt();
    }

    private void forgetIt() {
        this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(THE_TAG).build());
        this.db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(THE_TAG).build());
        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
    }

    /** What paging back through the history screen leaves behind: rows in LocationReport. */
    private void givenHistoryWasFetchedWhileTheListWasAway() {
        this.db.locationReportDao().insertAll(LocationReport.builder()
                .hashId("a-found-report")
                .beaconId(THE_TAG)
                .publishedAt(System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000L)
                .description("Wi-Fi")
                .timestamp(System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000L)
                .confidence(0)
                .latitude(52.370216)
                .longitude(4.895168)
                .horizontalAccuracy(83)
                .status(144)
                .lastUpdate(System.currentTimeMillis())
                .provenance(LocationReport.PROVENANCE_APPLE)
                .build());
    }

    private void assertTheRowSays(final int expected) {
        final String text = getInstrumentation().getTargetContext().getString(expected);

        onView(allOf(withId(R.id.device_item_container), hasDescendant(withText(THE_NAME))))
                .check(matches(hasDescendant(withText(text))));
    }

    private void assertTheRowDoesNotSay(final int unwanted) {
        final String text = getInstrumentation().getTargetContext().getString(unwanted);

        onView(allOf(withId(R.id.device_item_container), hasDescendant(withText(THE_NAME))))
                .check(matches(not(hasDescendant(withText(text)))));
    }

    /** With nothing stored, the generic line is correct. */
    @Test
    public void atagWithNoLocationsSaysSo() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);

        Eventually.check(() -> onView(withText(THE_NAME)).check(matches(isDisplayed())));
        this.assertTheRowSays(R.string.no_last_location_known);
    }

    /**
     * <b>The reported bug.</b>
     *
     * <p>Locations land while this screen is in the background - which is what the history
     * screen does - and coming back to it has to show them. Driven by backgrounding and
     * resuming the activity rather than by launching the history screen, because the mechanism
     * is "this screen was away and something changed underneath it", and history is only one of
     * several ways that happens: a background account read is another.
     */
    @Test
    public void locationsFoundWhileAwayShowUpOnReturning() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);

        Eventually.check(() -> onView(withText(THE_NAME)).check(matches(isDisplayed())));
        this.assertTheRowSays(R.string.no_last_location_known);

        this.givenHistoryWasFetchedWhileTheListWasAway();

        // Away, and back - exactly what opening the tag page and pressing back does.
        this.scenario.moveToState(Lifecycle.State.CREATED);
        this.scenario.moveToState(Lifecycle.State.RESUMED);

        Eventually.check(() -> this.assertTheRowDoesNotSay(R.string.no_last_location_known));
    }

    /**
     * And the row says something specific, not merely something different.
     *
     * <p>Guards against a fix that clears the line without filling it in - an empty subtitle
     * would satisfy the assertion above and still tell the user nothing.
     */
    @Test
    public void thereturningRowNamesWhenItWasLastSeen() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);
        Eventually.check(() -> onView(withText(THE_NAME)).check(matches(isDisplayed())));

        this.givenHistoryWasFetchedWhileTheListWasAway();

        this.scenario.moveToState(Lifecycle.State.CREATED);
        this.scenario.moveToState(Lifecycle.State.RESUMED);

        // "3 days ago", however this locale phrases it, inside the relative-time sentence.
        Eventually.check(() -> onView(allOf(
                withId(R.id.device_item_container), hasDescendant(withText(THE_NAME))))
                .check(matches(hasDescendant(allOf(
                        withId(R.id.list_item_last_update),
                        withText(containsDays()))))));
    }

    private static org.hamcrest.Matcher<String> containsThe(final String what) {
        return org.hamcrest.Matchers.containsString(what);
    }

    private static org.hamcrest.Matcher<String> containsDays() {
        return containsThe("day");
    }
}
