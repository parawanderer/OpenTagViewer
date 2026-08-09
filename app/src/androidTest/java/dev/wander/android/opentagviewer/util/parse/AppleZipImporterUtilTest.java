package dev.wander.android.opentagviewer.util.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.db.repo.model.ImportData;

/**
 * Covers the zip parsing stage of an import, and in particular whether a KeyAlignmentRecord
 * survives the trip from the zip into OwnedBeacon.alignmentPlist.
 * <br>
 * This was previously untested, and the whole 0.0.2 path was silently dead: the file type
 * enum, the switch case and the database column all existed, but there was no entry for
 * KEY_ALIGNMENT_RECORD in the pattern map, so every alignment record was logged as an
 * unexpected file and dropped. Nothing failed - imports just quietly kept searching each
 * tag's entire key history.
 * <br>
 * These tests deliberately do not exercise plist to accessory-JSON conversion. That call goes
 * through Chaquopy and is covered by the pytest suite for main.py; the importer catches its
 * failures and leaves accessoryJson null for the lazy backfill to retry, so it does not need
 * a Python runtime here.
 */
@RunWith(AndroidJUnit4.class)
public class AppleZipImporterUtilTest {

    /** Uppercase v4 UUIDs - the matchers are case sensitive, as macOS writes them uppercase. */
    private static final String BEACON_A = "0FB0AEAC-C083-405E-A979-4AA6A73F5C56";
    private static final String BEACON_B = "46E881A1-3CD9-4965-AEA9-2D95414661E7";
    private static final String RECORD_ID = "19FDE267-DE22-4B9A-BC44-E22C5970FCDC";

    private static final String YAML_0_0_2 =
            "version: 0.0.2\n"
            + "exportTimestamp: 1740685990163\n"
            + "via: test\n"
            + "sourceUser: tester\n";

    private static final String YAML_0_0_1 =
            "version: 0.0.1\n"
            + "exportTimestamp: 1740685990163\n"
            + "via: test\n"
            + "sourceUser: tester\n";

    private Context context;
    private File zipFile;

    @Before
    public void setUp() {
        this.context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @After
    public void tearDown() {
        if (this.zipFile != null && this.zipFile.exists() && !this.zipFile.delete()) {
            this.zipFile.deleteOnExit();
        }
    }

    @Test
    public void readsTheAlignmentRecordFromA002Export() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("OPENTAGVIEWER.yml", YAML_0_0_2);
        entries.put("OwnedBeacons/" + BEACON_A + ".plist", ownedBeaconPlist(BEACON_A));
        entries.put("BeaconNamingRecord/" + BEACON_A + "/" + RECORD_ID + ".plist", namingRecordPlist("Tag A"));
        entries.put("KeyAlignmentRecords/" + BEACON_A + "/" + RECORD_ID + ".plist", alignmentPlist(4321));

        ImportData result = importZipOf(entries);

        assertEquals(1, result.getOwnedBeacons().size());
        OwnedBeacon beacon = result.getOwnedBeacons().get(0);

        assertEquals(BEACON_A, beacon.id);
        assertNotNull(
                "The alignment record was dropped during import - check the KEY_ALIGNMENT_RECORD"
                        + " entry in AppleZipImporterUtil.MATCHERS",
                beacon.alignmentPlist);
        assertTrue(
                "Expected the accessory's own alignment record, got: " + beacon.alignmentPlist,
                beacon.alignmentPlist.contains("lastIndexObserved"));
    }

    @Test
    public void keysTheAlignmentRecordByAccessoryNotByRecordId() throws IOException {
        // The record's own uuid appears in the filename too. Keying by it instead of by the
        // parent directory would attach every record to the wrong accessory, or to none.
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("OPENTAGVIEWER.yml", YAML_0_0_2);
        entries.put("OwnedBeacons/" + BEACON_A + ".plist", ownedBeaconPlist(BEACON_A));
        entries.put("BeaconNamingRecord/" + BEACON_A + "/" + RECORD_ID + ".plist", namingRecordPlist("Tag A"));
        entries.put("KeyAlignmentRecords/" + BEACON_A + "/" + RECORD_ID + ".plist", alignmentPlist(1111));

        entries.put("OwnedBeacons/" + BEACON_B + ".plist", ownedBeaconPlist(BEACON_B));
        entries.put("BeaconNamingRecord/" + BEACON_B + "/" + RECORD_ID + ".plist", namingRecordPlist("Tag B"));
        entries.put("KeyAlignmentRecords/" + BEACON_B + "/" + RECORD_ID + ".plist", alignmentPlist(2222));

        ImportData result = importZipOf(entries);

        assertEquals(2, result.getOwnedBeacons().size());
        for (OwnedBeacon beacon : result.getOwnedBeacons()) {
            assertNotNull("No alignment record for " + beacon.id, beacon.alignmentPlist);

            final String expectedIndex = BEACON_A.equals(beacon.id) ? "1111" : "2222";
            assertTrue(
                    "Alignment record for " + beacon.id + " has the wrong index: "
                            + beacon.alignmentPlist,
                    beacon.alignmentPlist.contains(expectedIndex));
        }
    }

    @Test
    public void legacyExportsImportWithoutAnAlignmentRecord() throws IOException {
        // Exports made before 0.0.2 carry no KeyAlignmentRecords at all. They must still
        // import; the column stays null and main.py falls back to probing for the alignment.
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("OPENTAGVIEWER.yml", YAML_0_0_1);
        entries.put("OwnedBeacons/" + BEACON_A + ".plist", ownedBeaconPlist(BEACON_A));
        entries.put("BeaconNamingRecord/" + BEACON_A + "/" + RECORD_ID + ".plist", namingRecordPlist("Tag A"));

        ImportData result = importZipOf(entries);

        assertEquals(1, result.getOwnedBeacons().size());
        assertNull(result.getOwnedBeacons().get(0).alignmentPlist);
        assertEquals("0.0.1", result.getOwnedBeacons().get(0).version);
    }

    @Test
    public void ignoresAnAlignmentRecordForAnUnknownAccessory() throws IOException {
        // macOS can hold a KeyAlignmentRecord for an accessory that is no longer in
        // OwnedBeacons. It has nothing to attach to and must not affect the rest of the import.
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("OPENTAGVIEWER.yml", YAML_0_0_2);
        entries.put("OwnedBeacons/" + BEACON_A + ".plist", ownedBeaconPlist(BEACON_A));
        entries.put("BeaconNamingRecord/" + BEACON_A + "/" + RECORD_ID + ".plist", namingRecordPlist("Tag A"));
        entries.put("KeyAlignmentRecords/" + BEACON_B + "/" + RECORD_ID + ".plist", alignmentPlist(9999));

        ImportData result = importZipOf(entries);

        assertEquals(1, result.getOwnedBeacons().size());
        assertEquals(BEACON_A, result.getOwnedBeacons().get(0).id);
        assertNull(result.getOwnedBeacons().get(0).alignmentPlist);
    }

    // -----------------------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------------------

    private ImportData importZipOf(final Map<String, String> entries) throws IOException {
        this.zipFile = File.createTempFile("otv-import-test", ".zip", this.context.getCacheDir());

        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(this.zipFile))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }

        return new AppleZipImporterUtil(this.context).extractZip(Uri.fromFile(this.zipFile));
    }

    /**
     * Shaped like a real OwnedBeacons plist but carrying no key material. The importer only
     * stores this verbatim - parsing it is main.py's job and is covered by its own tests.
     */
    private static String ownedBeaconPlist(final String beaconId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "\t<key>identifier</key>\n"
                + "\t<string>" + beaconId + "</string>\n"
                + "\t<key>pairingDate</key>\n"
                + "\t<date>2025-02-27T20:33:10Z</date>\n"
                + "</dict>\n"
                + "</plist>\n";
    }

    private static String namingRecordPlist(final String name) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "\t<key>name</key>\n"
                + "\t<string>" + name + "</string>\n"
                + "</dict>\n"
                + "</plist>\n";
    }

    /** The two fields that matter: where the rolling key index was, and when. */
    private static String alignmentPlist(final int lastIndexObserved) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "\t<key>lastIndexObserved</key>\n"
                + "\t<integer>" + lastIndexObserved + "</integer>\n"
                + "\t<key>lastIndexObservationDate</key>\n"
                + "\t<date>2026-08-01T12:00:00Z</date>\n"
                + "</dict>\n"
                + "</plist>\n";
    }
}
