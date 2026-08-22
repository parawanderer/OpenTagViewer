package dev.wander.android.opentagviewer.util.parse;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.ui.BeaconIcon;
import dev.wander.android.opentagviewer.db.repo.model.BeaconData;
import dev.wander.android.opentagviewer.db.repo.model.ImportData;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.db.util.BeaconCombinerUtil;
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
        assertFalse("no emoji: nobody has ever named this tag, and the icon covers it",
                info.isEmojiFilled());
        assertEquals("so it must fall to the self-generated icon, not Apple's logo",
                R.drawable.tag_self_generated, BeaconIcon.forBeacon(info));
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
        final UserBeaconOptions chosen = UserBeaconOptions.builder()
                .beaconId(row.id)
                .lastUpdate(System.currentTimeMillis())
                .uiName("My hidden bike")
                .uiEmoji("🚲")
                .build();

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

    /**
     * The one that was missing, and the reason all of the above passed while the feature did not
     * work.
     *
     * <p>Every test before this hands {@link BeaconDataParser} a {@code BeaconData} it built
     * itself, which skips the step that actually decides what reaches a screen.
     * {@link BeaconCombinerUtil#combine} used to iterate the <i>naming records</i> - so a tag
     * with none was dropped on the floor before the parser was ever asked about it. The import
     * reported "1 device", the fetch path collected reports for it happily, and it appeared
     * nowhere.
     *
     * <p>So this goes through the join, and the two that follow go through it for the two
     * screens that call it.
     */
    @Test
    public void itsurvivesTheJoinThatFeedsEveryScreen() {
        final List<BeaconData> joined = BeaconCombinerUtil.combine(imported());

        assertEquals("a tag with no naming record must not be dropped by the join",
                1, joined.size());
        assertEquals(IDENTIFIER, joined.get(0).getBeaconId());
        assertNotNull("and it must keep the row that has its keys in it",
                joined.get(0).getOwnedBeaconInfo());
        assertNull("nothing ever named it, so there is nothing to join to",
                joined.get(0).getBeaconNamingRecord());
    }

    /**
     * The device list's own call, which passes user options as a third list.
     *
     * <p>A separate case because it is a different overload, and because the options have to
     * survive the change of what the join iterates - they are keyed by beacon id either way,
     * but that is worth an assertion rather than an assumption.
     */
    @Test
    public void thedeviceListSeesItToo() {
        final OwnedBeacon row = imported().getOwnedBeacons().get(0);
        final UserBeaconOptions chosen = UserBeaconOptions.builder()
                .beaconId(row.id)
                .lastUpdate(System.currentTimeMillis())
                .uiName("My hidden bike")
                .uiEmoji("🚲")
                .build();

        final List<BeaconData> joined = BeaconCombinerUtil.combine(
                List.of(row), List.of(), List.of(chosen));

        assertEquals(1, joined.size());
        assertNotNull("the user's own name and emoji must still find it",
                joined.get(0).getUserBeaconOptions());
    }

    /** End to end, as the screen does it: import, join, parse, and read the name off it. */
    @Test
    public void thewholeChainProducesSomethingToShow() {
        final BeaconInformation info =
                BeaconDataParser.parse(BeaconCombinerUtil.combine(imported())).get(0);

        assertEquals(NAME, info.getName());
        assertTrue(info.isCustomAccessory());
    }

    /**
     * And an Apple tag still comes out of the join whole.
     *
     * <p>The risk in turning the join around is the mirror of the bug it fixes: driving from the
     * owned beacons could just as easily leave the naming record behind, which would cost every
     * real tag its name.
     */
    @Test
    public void anapplePairedTagKeepsItsNamingRecord() {
        final String id = "2C2A1B0C-9D8E-4F6A-8B4C-3D2E1F0A9B8C";
        final OwnedBeacon paired = OwnedBeacon.builder().id(id).content("<plist/>").build();
        final BeaconNamingRecord named =
                BeaconNamingRecord.builder().id(id).content("<plist/>").build();

        final List<BeaconData> joined =
                BeaconCombinerUtil.combine(List.of(paired), List.of(named), List.of());

        assertEquals(1, joined.size());
        assertNotNull(joined.get(0).getOwnedBeaconInfo());
        assertNotNull("without this an Apple tag loses its name and emoji",
                joined.get(0).getBeaconNamingRecord());
    }
}
