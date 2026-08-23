package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.DeviceInfoActivity;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.Shot;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;

/**
 * Which exporter produced <i>this</i> tag, on the tag's own page.
 *
 * <p><b>The Information screen cannot answer this, and that is why both exist.</b> It lists every
 * producer on the install - correct, and no use when a report is about one tag out of twelve
 * behaving oddly. Which program wrote a bundle decides what to expect of the tags in it: whether
 * a key alignment record came with them, which format the plists are, whether a known exporter
 * bug applies. Per tag, that is a fact; aggregated, it is a shortlist.
 *
 * <p>The row costs nothing to fill. This page already reads the {@code Import} for its "Exported
 * by" and "Exported at" rows, so {@code exportedVia} was in the object it already had.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class WhichExporterMadeThisTagTest {

    private static final String A_TAG = "test-provenance-tag";
    private static final String A_TEST_USER = "whichexportermadethistag@example.invalid";
    private static final String AN_EXPORTER = "OpenTagViewer.cli:1.3.0";

    private static final String A_PLIST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<plist version=\"1.0\"><dict>"
            + "<key>batteryLevel</key><integer>1</integer>"
            + "<key>model</key><string></string>"
            + "<key>pairingDate</key><date>2025-02-27T20:03:32Z</date>"
            + "<key>privateKey</key><dict><key>key</key><dict>"
            + "<key>data</key><data>bm90LWEtcmVhbC1rZXk=</data></dict></dict>"
            + "<key>productId</key><integer>4660</integer>"
            + "<key>stableIdentifier</key><array><string>2001~#0~#A0</string></array>"
            + "<key>systemVersion</key><string>1.0</string>"
            + "<key>vendorId</key><integer>76</integer>"
            + "</dict></plist>";

    private OpenTagViewerDatabase db;
    private ActivityScenario<DeviceInfoActivity> scenario;

    @Before
    public void clearTheDecks() {
        this.db = OpenTagViewerDatabase.getInstance(getInstrumentation().getTargetContext());
        this.forgetIt();
    }

    @After
    public void putEverythingBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        AppDependencies.reset();
        this.forgetIt();
    }

    /** <b>The one this exists for.</b> */
    @Test
    public void athetagSaysWhichExporterWroteIt() {
        this.seed(AN_EXPORTER);
        this.open();

        Eventually.check(() -> onView(allOf(
                withId(R.id.device_settings_exported_with),
                hasDescendant(withText(AN_EXPORTER))))
                .check(matches(isDisplayed())));

        Shot.ofTheScreen("the_device_page-exported_with");
    }

    /**
     * <b>And an export from before {@code via:} existed says so, rather than vanishing.</b>
     *
     * <p>Format 0.0.1 predates the field, so null is a real value here. Hiding the row would read
     * as a gap in the page; saying it was not recorded dates the bundle, which is itself the
     * answer to "how old is this export".
     */
    @Test
    public void banexportTooOldToRecordItselfSaysThatInstead() {
        this.seed(null);
        this.open();

        final Context context = getInstrumentation().getTargetContext();
        Eventually.check(() -> onView(allOf(
                withId(R.id.device_settings_exported_with),
                hasDescendant(withText(
                        context.getString(R.string.exported_with_something_unrecorded)))))
                .check(matches(isDisplayed())));
    }

    private void seed(final String via) {
        final long importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2")
                .importedAt(1_700_000_000_000L)
                .exportedAt(1_699_000_000_000L)
                .sourceUser(A_TEST_USER)
                .exportedVia(via)
                .build());

        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(A_TAG)
                .importId(importId)
                .content(A_PLIST)
                .version("0.0.2")
                .fromAccount(false)
                .isRemoved(false)
                .build());

        this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                .id(A_TAG)
                .importId(importId)
                .version("0.0.2")
                .isRemoved(false)
                .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                        + "<key>identifier</key><string>" + A_TAG + "</string>"
                        + "<key>name</key><string>A Tag With A History</string>"
                        + "</dict></plist>")
                .build());
    }

    private void open() {
        final Intent intent = new Intent(
                getInstrumentation().getTargetContext(), DeviceInfoActivity.class);
        intent.putExtra("beaconId", A_TAG);
        this.scenario = ActivityScenario.launch(intent);
    }

    private void forgetIt() {
        this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(A_TAG).build());
        this.db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(A_TAG).build());
        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
    }
}
