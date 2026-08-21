package dev.wander.android.opentagviewer.ui.importing;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import com.chaquo.python.Python;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.ui.maps.AMapWithTagsOnIt;

/**
 * A zip, chosen from the file picker, ends up as tags on the map.
 *
 * <p><b>The route every existing user depends on, and it had no coverage above the parser.</b>
 * {@code AppleZipImporterUtilTest} and its neighbours take a bundle apart thoroughly - but they
 * call {@code extractZip} directly. Nothing drove the picker, handed the result to the screen
 * and watched a pin appear, so the whole span between "the user chose a file" and "the tag is on
 * the map" was covered by running the app by hand.
 *
 * <p>It matters more than the account route it sits beside. Reading tags out of an Apple account
 * is new; importing a zip made on a Mac is how <i>everybody</i> got their tags in until now, and
 * how anybody given tags by somebody else still does.
 *
 * <p><b>The zip is real and built here</b> - the metadata file, an {@code OwnedBeacons} plist and
 * a {@code BeaconNamingRecord} per tag, in the layout the exporter writes - so the actual
 * importer runs. Only the picker is faked, because a system file chooser cannot be driven from a
 * test.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ImportingAZipPutsTagsOnTheMapTest {

    private static final String BEACON_A = "1A2B3C4D-1111-4222-8333-444455556666";
    private static final String BEACON_B = "9F8E7D6C-2222-4333-8444-555566667777";
    private static final String RECORD_ID = "0A1B2C3D-3333-4444-8555-666677778888";

    private static final String IMPORTED_FROM = "somebody-elses-mac@example.com";

    /** Where the double says both tags are, once the map asks. */
    private static final double LATITUDE = 51.500729;
    private static final double LONGITUDE = -0.124625;

    /**
     * The metadata file, in the exporter's own field names.
     *
     * <p><b>{@code exportTimestamp}, not {@code exportedAt}.</b> The second is what the Room
     * entity calls the same value, and using it here produces a bundle the importer refuses
     * outright - before it looks at a single tag. Which is the right behaviour, and was how
     * this test first found out it had invented a field name.
     */
    private static final String BUNDLE_METADATA =
            "version: 0.0.2\n"
                    + "exportTimestamp: 1699000000000\n"
                    + "via: OpenTagViewer.wizard:test\n"
                    + "sourceUser: " + IMPORTED_FROM + "\n";

    private final AMapWithTagsOnIt theMap = new AMapWithTagsOnIt();

    private Context context;
    private OpenTagViewerDatabase db;
    private File zipFile;

    @Before
    public void openAMapWithNothingOnItYet() {
        this.context = getInstrumentation().getTargetContext();
        this.db = OpenTagViewerDatabase.getInstance(this.context);

        this.forgetTheImportedTags();

        Intents.init();

        // Seeded with no tags at all: somebody signed in with nothing yet, which is exactly who
        // reaches for Import.
        this.theMap.seed();

        // The fixture installs the double with nothing to report, which is right for a map whose
        // pins come from the database. Here the tags arrive with no locations at all, so a pin
        // can only appear if the fetch that follows the import returns one.
        Python.getInstance().getModule("apple_test_double")
                .callAttr("install", LATITUDE, LONGITUDE, 2.0);

        this.theMap.open();

        Eventually.check(() -> assertTrue("the map never became ready",
                this.theMap.map().isReady()));
    }

    @After
    public void putEverythingBack() {
        Intents.release();
        this.theMap.putItBack();
        this.forgetTheImportedTags();

        if (this.zipFile != null && this.zipFile.exists() && !this.zipFile.delete()) {
            this.zipFile.deleteOnExit();
        }
    }

    /**
     * <b>Both tags in the bundle are written down, with the state that makes them locatable.</b>
     *
     * <p>{@code accessory_json} is asserted for the same reason it is in the account journey: a
     * missing one is backfilled on first fetch rather than being fatal, so a tag that imported
     * and can never be located looks exactly like a tag that imported.
     */
    @Test
    public void thetagsInTheZipAreImportedAndAreLocatable() {
        this.chooseTheZipFromTheImportMenu();

        Eventually.check(() -> assertEquals("the bundle's two tags were not both imported",
                2, this.importedTags().size()));

        for (final OwnedBeacon imported : this.importedTags()) {
            assertNotNull(imported.id + " imported with no accessory state, so it looks"
                    + " imported and can never be located", imported.accessoryJson);
            assertNotNull(imported.id + " imported with no plist", imported.content);
        }
    }

    /**
     * <b>And they arrive on the map, without the user going anywhere.</b>
     *
     * <p>The hand-off that had nothing on it. The screen takes a {@code Uri} from an activity
     * result, imports it, fetches for what arrived and redraws - and a break anywhere along
     * that leaves a user who picked the right file staring at an unchanged map, with the tags
     * sitting in the database where only My Devices would show them.
     */
    @Test
    public void andappearOnTheMapWithoutLeavingTheScreen() {
        this.chooseTheZipFromTheImportMenu();

        Eventually.check(() -> assertEquals("the imported tags never reached the map",
                2, this.theMap.map().markerCount()));

        for (final String beaconId : List.of(BEACON_A, BEACON_B)) {
            final List<dev.wander.android.opentagviewer.ui.maps.FakeMapProvider.PlacedMarker> pins =
                    this.theMap.map().markers().stream()
                            .filter(placed -> beaconId.equals(placed.marker.getId()))
                            .collect(Collectors.toList());

            assertEquals("no pin was drawn for " + beaconId, 1, pins.size());
            assertEquals(LATITUDE, pins.get(0).marker.getLatitude(), 0.000001);
            assertEquals(LONGITUDE, pins.get(0).marker.getLongitude(), 0.000001);
        }
    }

    /** And the tag cards are built for them too, not only the pins. */
    @Test
    public void andgetTagCardsOfTheirOwn() {
        this.chooseTheZipFromTheImportMenu();

        Eventually.check(() -> assertTrue("no tag cards were built for the imported tags",
                this.theMap.cards().size() >= 2));
    }

    // ------------------------------------------------------------------ the picker

    /**
     * Open the overflow, choose Import, and answer the picker with our zip.
     *
     * <p>The stub is registered before the menu is touched, because the launcher fires the
     * moment the item is chosen and an intent that goes out unstubbed reaches the real system
     * file chooser - which no test can answer.
     */
    private void chooseTheZipFromTheImportMenu() {
        final Uri bundle = this.writeAZipWithTwoTagsInIt();

        final Intent chosen = new Intent();
        chosen.setData(bundle);
        intending(hasAction(Intent.ACTION_GET_CONTENT))
                .respondWith(new ActivityResult(Activity.RESULT_OK, chosen));

        Eventually.check(() -> onView(withId(R.id.button_more_settings))
                .check(matches(isDisplayed())));
        onView(withId(R.id.button_more_settings)).perform(click());

        // Not wrapped in Eventually: a PopupMenu is up by the time the click returns, and asking
        // for a platform-popup root that is not there yet is the slow question, not the quick
        // one.
        onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.do_import))
                .inRoot(isPlatformPopup()).perform(click());
    }

    // ------------------------------------------------------------------ the bundle

    /**
     * A bundle on disk, in the layout the exporter produces.
     *
     * <p>Written to the app's own cache so a {@code file://} Uri is readable without a provider.
     * That is safe here and would not be in the app: nothing leaves the process, because the
     * picker is stubbed and the result is handed straight back.
     */
    private Uri writeAZipWithTwoTagsInIt() {
        final Map<String, String> entries = new LinkedHashMap<>();
        entries.put("OPENTAGVIEWER.yml", BUNDLE_METADATA);
        entries.put("OwnedBeacons/" + BEACON_A + ".plist", ownedBeaconPlist(BEACON_A));
        entries.put("BeaconNamingRecord/" + BEACON_A + "/" + RECORD_ID + ".plist",
                namingRecordPlist(BEACON_A, "Borrowed Bike"));
        entries.put("OwnedBeacons/" + BEACON_B + ".plist", ownedBeaconPlist(BEACON_B));
        entries.put("BeaconNamingRecord/" + BEACON_B + "/" + RECORD_ID + ".plist",
                namingRecordPlist(BEACON_B, "Borrowed Keys"));

        try {
            this.zipFile = File.createTempFile(
                    "otv-import-journey", ".zip", this.context.getCacheDir());

            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(this.zipFile))) {
                for (final Map.Entry<String, String> entry : entries.entrySet()) {
                    out.putNextEntry(new ZipEntry(entry.getKey()));
                    out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    out.closeEntry();
                }
            }
        } catch (final IOException cannotWrite) {
            throw new AssertionError("could not build a bundle to import", cannotWrite);
        }

        return Uri.fromFile(this.zipFile);
    }

    /**
     * An {@code OwnedBeacons} plist the real converter can actually convert.
     *
     * <p><b>The key material has to be real in shape, and a made-up string will not do.</b> The
     * importer hands each plist to Python's {@code convertPlistToJson}, which builds a
     * {@code FindMyAccessory} - and that reaches for a 28-byte master key and two 32-byte shared
     * secrets. A short placeholder is refused there.
     *
     * <p>Refused <b>quietly</b>, which is what made this worth a comment: a missing accessory
     * state is not fatal by design, because it is backfilled on the first fetch. So the first
     * version of this test imported two tags perfectly happily and neither could ever be
     * located - the same trap {@code FakeICloudService.AN_OWNED_BEACON_PLIST} was written to
     * escape, and this borrows its key material for the same reason. It is real in shape and
     * secret in no sense whatsoever.
     */
    private static String ownedBeaconPlist(final String beaconId) {
        return dev.wander.android.opentagviewer.python.icloud.FakeICloudService
                .AN_OWNED_BEACON_PLIST
                // The fixture names one identifier; a bundle with two tags needs two. The
                // importer takes the beacon id from the file name, so this only keeps the
                // document internally consistent.
                .replace("F612A183-492B-45A8-A5A2-233CA9062A94", beaconId);
    }

    private static String namingRecordPlist(final String beaconId, final String name) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<plist version=\"1.0\"><dict>"
                + "<key>identifier</key><string>" + beaconId + "</string>"
                + "<key>associatedBeacon</key><string>" + beaconId + "</string>"
                + "<key>name</key><string>" + name + "</string>"
                + "</dict></plist>";
    }

    // ------------------------------------------------------------------ housekeeping

    private List<OwnedBeacon> importedTags() {
        return this.db.ownedBeaconDao().getAll().stream()
                .filter(beacon -> BEACON_A.equals(beacon.id) || BEACON_B.equals(beacon.id))
                .collect(Collectors.toList());
    }

    private void forgetTheImportedTags() {
        for (final String beaconId : List.of(BEACON_A, BEACON_B)) {
            this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(beaconId).build());
            this.db.beaconNamingRecordDao().delete(
                    dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord.builder()
                            .id(beaconId).build());
        }

        for (final var stale : this.db.importDao().getImportsFromUser(IMPORTED_FROM)) {
            this.db.importDao().delete(stale);
        }
    }
}
