package dev.wander.android.opentagviewer.util.parse;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.db.repo.model.BeaconData;
import dev.wander.android.opentagviewer.db.repo.model.ImportData;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.db.room.entity.UserBeaconOptions;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Importing a tag whose keys were never in an Apple account.
 *
 * <p><b>The fixture is written by the real exporter</b> -
 * {@code scripts/make_custom_accessory_fixture.py} calls {@code build_export} with a
 * {@code FixedRollingKeyPairAccessory} - so this checks the app against the format something
 * else produces. A zip assembled here would only ever check the app against itself, and would
 * keep passing on the day {@code bundle.py} changed shape.
 *
 * <p>It carries <b>no {@code OwnedBeacons} at all</b>, which is deliberate and is the case most
 * likely to be got wrong: before this, an export of nothing but self-generated tags was refused
 * as carrying no tags.
 */
@RunWith(AndroidJUnit4.class)
public class CustomAccessoryImportTest {

    private static final String FIXTURE = "custom_accessory_fixture.zip";

    /** Must match the generator. Note it is **not** a UUID - that is the point of it. */
    private static final String IDENTIFIER = "openhaystack-demo-tag";
    private static final String NAME = "Bike (self-generated)";
    private static final int KEY_COUNT = 3;

    private Context appContext;
    private File bundle;

    @Before
    public void setUp() throws IOException {
        this.appContext = getInstrumentation().getTargetContext();

        this.bundle = File.createTempFile("otv-custom", ".zip", this.appContext.getCacheDir());
        try (InputStream in = getInstrumentation().getContext().getAssets().open(FIXTURE);
             OutputStream out = new FileOutputStream(this.bundle)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    @After
    public void tearDown() {
        if (this.bundle != null && this.bundle.exists() && !this.bundle.delete()) {
            this.bundle.deleteOnExit();
        }
    }

    private ImportData imported() {
        return new AppleZipImporterUtil(this.appContext).extractZip(Uri.fromFile(this.bundle));
    }

    /**
     * The headline: the entry is read rather than skipped.
     *
     * <p>It used to be dropped silently - an unknown directory, no error, and a tag that simply
     * did not arrive. The user's own export hits this.
     */
    @Test
    public void aselfGeneratedTagIsImported() {
        final ImportData data = imported();

        assertEquals(1, data.getOwnedBeacons().size());
        assertEquals(IDENTIFIER, data.getOwnedBeacons().get(0).id);
    }

    /**
     * A bundle of only these is a bundle of tags.
     *
     * <p>The empty check tested `OwnedBeacons` alone, so this exact fixture - which has none -
     * was rejected as "well-formed but carries no OwnedBeacons". That reads to the user as the
     * export having worked and the tags having gone missing.
     */
    @Test
    public void abundleWithNothingButSelfGeneratedTagsIsNotEmpty() {
        assertNotNull(imported());
    }

    /**
     * Stored as the JSON it is, with no plist and nothing pretending to be one.
     *
     * <p>{@code content} holds the plist an Apple-paired tag was exported as, and this kind has
     * none. Writing anything there - an empty string, a synthesised document - would be a lie
     * the fetch path would later try to parse.
     */
    @Test
    public void itIsStoredAsJsonWithNoPlist() throws Exception {
        final OwnedBeacon row = imported().getOwnedBeacons().get(0);

        assertNull("there is no plist for a tag Apple has never seen", row.content);
        assertNull("and no alignment record either", row.alignmentPlist);
        assertNotNull(row.accessoryJson);

        final JSONObject mapping = new JSONObject(row.accessoryJson);
        assertEquals("custom_rolling_key_accessory", mapping.getString("type"));
        assertEquals(IDENTIFIER, mapping.getString("identifier"));
        assertEquals(KEY_COUNT, mapping.getJSONArray("private_keys").length());
    }

    /** The format version travels with it, so a reader can tell what it is looking at. */
    @Test
    public void thebundleDeclaresTheVersionThatCarriesThese() {
        assertEquals("0.0.3", imported().getAnImport().version);
    }

    /**
     * It reaches the screen as a self-generated tag, named from its own mapping.
     *
     * <p>Everything the display model normally reads comes from two plists that do not exist
     * here, so this is the assertion that the alternative path produces something usable rather
     * than a row of blanks.
     */
    @Test
    public void itDescribesItselfOnTheDeviceScreen() {
        final OwnedBeacon row = imported().getOwnedBeacons().get(0);

        final BeaconInformation info = BeaconDataParser.parse(List.of(
                new BeaconData(row.id, row, null, null))).get(0);

        assertTrue("this is what stops the screen saying Unknown", info.isCustomAccessory());
        assertEquals(IDENTIFIER, info.getBeaconId());
        assertEquals(NAME, info.getName());
        assertEquals(KEY_COUNT, info.getCustomAccessoryKeyCount());
        assertTrue("it needs some emoji, or it renders as a gap where every other row has one",
                info.isEmojiFilled());
    }

    /**
     * And the user can rename it and re-emoji it, like any other tag.
     *
     * <p>Free, because overrides are applied by beacon id and know nothing about where a tag
     * came from - but worth asserting, since the alternative construction path could easily
     * have forgotten to apply them, and nothing else would have noticed.
     */
    @Test
    public void theusersOwnNameAndEmojiWin() {
        final OwnedBeacon row = imported().getOwnedBeacons().get(0);
        final UserBeaconOptions chosen = new UserBeaconOptions(
                row.id, System.currentTimeMillis(), "My hidden bike", "🚲");

        final BeaconInformation info = BeaconDataParser.parse(List.of(
                new BeaconData(row.id, row, null, chosen))).get(0);

        assertEquals("My hidden bike", info.getName());
        assertEquals("🚲", info.getEmoji());
    }

    /**
     * An Apple-paired tag is untouched by any of this.
     *
     * <p>The half that makes the rest mean something: the new branch keys off "has no plist",
     * so a bug there would divert real tags into it and lose every field they have.
     */
    @Test
    public void anapplePairedTagStillTakesTheOldPath() {
        final OwnedBeacon paired = OwnedBeacon.builder()
                .id("2C2A1B0C-9D8E-4F6A-8B4C-3D2E1F0A9B8C")
                .content("<plist/>")
                .accessoryJson("{\"type\":\"accessory\"}")
                .build();

        assertTrue("a row with a plist is not a self-generated tag",
                !CustomAccessoryParser.isCustomAccessory(
                        new BeaconData(paired.id, paired, null, null)));
    }
}
