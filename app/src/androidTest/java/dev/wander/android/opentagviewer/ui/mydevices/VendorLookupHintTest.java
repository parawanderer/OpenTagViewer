package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

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
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.HardwareDescriber;

/**
 * Telling somebody what the hex on the Type row is.
 *
 * <p>When nothing recognises an accessory, Type reads something like
 * {@code vendor 0x0ABC product 0x1234}. Those are real Bluetooth SIG registry values and the
 * question they answer is one browser search away - but only for a reader who knows the number
 * means something, and until now the app knew that and did not say it.
 * {@code where_to_look_up} has been implemented in Python and reachable from Java for as long as
 * the heuristic has existed, with no screen calling it.
 *
 * <p><b>The hint has to stay off for everything else.</b> An AirTag, a Chipolo, an iPad - all
 * recognised, nothing to explain, and a line of registry trivia under every one of them would be
 * noise on the screen people actually look at.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class VendorLookupHintTest {

    private static final String A_TAG = "test-unrecognised-tag";
    private static final String A_TEST_USER = "vendorlookuphinttest@example.invalid";

    /** Vendor 0x0ABC is in no table anywhere, which is the point. */
    private static final int AN_UNKNOWN_VENDOR = 0x0ABC;

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
            + "<key>vendorId</key><integer>" + AN_UNKNOWN_VENDOR + "</integer>"
            + "</dict></plist>";

    private OpenTagViewerDatabase db;
    private ActivityScenario<DeviceInfoActivity> scenario;

    /** Answers whatever the test needs, without a Python interpreter. */
    private static final class Describer implements HardwareDescriber {
        private final String description;
        private final String lookup;

        Describer(final String description, final String lookup) {
            this.description = description;
            this.lookup = lookup;
        }

        @Override
        public String describe(final String plistXml) {
            return this.description;
        }

        @Override
        public String whereToLookUp(final String plistXml) {
            return this.lookup;
        }

        /** Not what this class is about; an accessory, so nothing here offers to write. */
        @Override
        public Boolean isOwnDevice(final String plistXml) {
            return Boolean.FALSE;
        }
    }

    @Before
    public void seedOneUnrecognisedTag() {
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
                        + "<key>name</key><string>Something Unrecognised</string>"
                        + "</dict></plist>")
                .build());
    }

    @After
    public void putEverythingBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        AppDependencies.reset();
        this.forgetIt();
    }

    private void forgetIt() {
        this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(A_TAG).build());
        this.db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(A_TAG).build());
        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
    }

    private void open(final String description, final String lookup) {
        AppDependencies.replaceHardwareDescriber(new Describer(description, lookup));

        final Intent intent = new Intent(
                getInstrumentation().getTargetContext(), DeviceInfoActivity.class);
        intent.putExtra("beaconId", A_TAG);
        this.scenario = ActivityScenario.launch(intent);
    }

    /** <b>The one this exists for.</b> */
    @Test
    public void anunrecognisedAccessoryIsToldWhatItsVendorNumberIs() {
        this.open("vendor 0x0ABC product 0x1234", "something to look up");

        Eventually.check(() -> onView(withId(R.id.device_type_lookup_hint))
                .check(matches(isDisplayed())));
    }

    /**
     * And the number in the sentence is this accessory's, not a placeholder.
     *
     * <p>Formatted in the app rather than taken from Python's sentence, so this is also what
     * pins that the two agree about which vendor is being talked about.
     */
    @Test
    public void thehintNamesTheVendorFromTheRecord() {
        this.open("vendor 0x0ABC product 0x1234", "something to look up");

        Eventually.check(() -> onView(withId(R.id.device_type_lookup_hint))
                .check(matches(withText(containsString("0x0ABC")))));
    }

    /**
     * <b>Silent for everything the heuristic can name</b>, which is nearly every tag.
     *
     * <p>Python returns null the moment the vendor is in its table, so a Chipolo or an AirTag
     * reaches here with nothing to say. If this ever fails, every recognised tag in the app has
     * grown a line of registry trivia under it.
     */
    @Test
    public void arecognisedAccessorySaysNothingAboutRegistries() {
        this.open("Chipolo tag", null);

        Eventually.check(() -> onView(withId(R.id.device_settings_device_type))
                .check(matches(isDisplayed())));
        onView(withId(R.id.device_type_lookup_hint)).check(matches(not(isDisplayed())));
    }

    /** And a heuristic that answered nothing at all still does not invent a hint. */
    @Test
    public void aheuristicThatKnowsNothingShowsNoHint() {
        this.open(null, null);

        Eventually.check(() -> onView(withId(R.id.device_settings_device_type))
                .check(matches(isDisplayed())));
        onView(withId(R.id.device_type_lookup_hint)).check(matches(not(isDisplayed())));
    }
}
