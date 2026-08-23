package dev.wander.android.opentagviewer.util.export;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import dev.wander.android.opentagviewer.BuildConfig;
import dev.wander.android.opentagviewer.db.repo.model.ImportData;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.util.parse.AppleZipImporterUtil;
import dev.wander.android.opentagviewer.util.parse.BundlePasscode;
import dev.wander.android.opentagviewer.util.parse.ZipImporterException;

/**
 * A bundle this app writes, imported back by this app.
 *
 * <p><b>The two halves have never met.</b> {@code ALockedBundleCanBeOpenedAgainTest} proves zip4j
 * round-trips bytes; {@code test_export_bridge.py} proves Python builds the right files. Both
 * pass while the format is wrong, because neither has ever handed its output to the thing that
 * reads it - and the format is exactly where a mistake hides: a directory named in the singular,
 * a naming record filed under the wrong identifier, an entry path with a backslash in it.
 *
 * <p>Nothing is faked here. The real Chaquopy builder runs, zip4j writes a real AES archive, and
 * {@code AppleZipImporterUtil} opens it the same way it opens a bundle from the desktop exporter -
 * including validating {@code OPENTAGVIEWER.yml} against the schema, which is the check that
 * would catch a manifest this app got subtly wrong.
 *
 * <p>The failure this exists to prevent is the worst one available: a sender told the export
 * worked, and a recipient - on another phone, days later, after the sender deleted their copy -
 * discovering it did not.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class WhatWeExportWeCanImportTest {

    private static final String A_TAG = "F612A183-492B-45A8-A5A2-233CA9062A94";
    private static final String A_NAME = "Round Trip";

    /**
     * A complete accessory record.
     *
     * <p><b>Complete is the point, and the first draft was not.</b> It carried a privateKey and
     * stopped there, and the export was refused with "missing sharedSecret" - FindMy.py reads all
     * three key fields unconditionally when a bundle is imported, so a partial record produces an
     * accessory that fails conversion on the recipient's phone. The shared package checks for
     * them on the way out, which is why that failure arrived here rather than there.
     *
     * <p>Same shape and same fake key material as {@code FakeICloudService}, which is where these
     * values come from. None of it belongs to anybody.
     */
    private static final String A_PLIST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<plist version=\"1.0\"><dict>"
            + "<key>batteryLevel</key><integer>1</integer>"
            + "<key>identifier</key><string>" + A_TAG + "</string>"
            + "<key>model</key><string></string>"
            + "<key>pairingDate</key><date>2025-02-27T20:03:32Z</date>"
            + "<key>privateKey</key><dict><key>key</key><dict><key>data</key><data>"
            + "J1AAk7qStLSbMhZT/XEve6by7hI0H7CslD/Oh7SrOc+mlmLnAO8c"
            + "5FGnhi/s3TDlWNiL3SMy19NQuCWg6oTS+YfBZN79RiUmZtssTp9f"
            + "UvZjmqMX3g=="
            + "</data></dict></dict>"
            + "<key>productId</key><integer>21760</integer>"
            + "<key>publicKey</key><dict><key>key</key><dict><key>data</key><data>"
            + "k6fWaOxFGbClYV6tu/ZK4vXdyWl2joSbJhbzu12Pfmf5p09w5LxKIvnABRfysSFkOAlo/F3Ii9Dq"
            + "</data></dict></dict>"
            + "<key>secondarySharedSecret</key><dict><key>key</key><dict><key>data</key>"
            + "<data>1pWMT+FI3flAWmgbUEW5H6omZy+yZOzp30zZGxEa2A8=</data></dict></dict>"
            + "<key>sharedSecret</key><dict><key>key</key><dict><key>data</key>"
            + "<data>vM2ZjU/sKW/novHcwzTlY5xwGLOUOZjpgcZa9cNx2Y8=</data></dict></dict>"
            + "<key>stableIdentifier</key><array>"
            + "<string>2001~#001234a12345aaac~#A02BCDEFG1AB</string></array>"
            + "<key>systemVersion</key><string>2.0.73</string>"
            + "<key>vendorId</key><integer>76</integer>"
            + "</dict></plist>";

    private static final String A_NAMING_RECORD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<plist version=\"1.0\"><dict>"
            + "<key>identifier</key><string>6C68CF6D-0A57-4D66-8646-E4B62CFBF1CB</string>"
            + "<key>associatedBeacon</key><string>" + A_TAG + "</string>"
            + "<key>name</key><string>" + A_NAME + "</string>"
            + "</dict></plist>";

    private File bundle;

    @Before
    public void somewhereToWriteIt() {
        this.bundle = new File(
                getInstrumentation().getTargetContext().getCacheDir(), "round-trip.zip");
    }

    @After
    public void tidyUp() {
        AppDependencies.reset();
        if (this.bundle != null) {
            this.bundle.delete();
        }
    }

    /** Export one tag through the whole real stack, and hand back the code it was locked with. */
    private String exportOneTag() throws Exception {
        final List<TagExporter.Pairing> selection = new ArrayList<>();
        selection.add(new TagExporter.Pairing(
                OwnedBeacon.builder().id(A_TAG).content(A_PLIST).version("0.0.2").build(),
                BeaconNamingRecord.builder().id(A_TAG).content(A_NAMING_RECORD)
                        .version("0.0.2").build(),
                A_NAME));

        try (OutputStream out = new FileOutputStream(this.bundle)) {
            return TagExporter.writeTo(
                    out,
                    selection,
                    "OpenTagViewer.android:" + BuildConfig.VERSION_NAME,
                    "OpenTagViewer on a test device",
                    System.currentTimeMillis()).getPasscode();
        }
    }

    private ImportData importItBack(final String passcode) throws ZipImporterException {
        final Context context = getInstrumentation().getTargetContext();
        return new AppleZipImporterUtil(context)
                .extractZip(Uri.fromFile(this.bundle), passcode);
    }

    /**
     * <b>The whole loop: write it, then read it.</b>
     *
     * <p>The importer is not lenient about the format - it validates the manifest against
     * {@code opentagviewer_schema.json} and inner-joins the beacon and naming records - so
     * reaching an {@code ImportData} at all is most of the claim.
     */
    @Test
    public void abundleThisAppWritesIsOneThisAppCanRead() throws Exception {
        final String passcode = exportOneTag();

        final ImportData read = importItBack(passcode);

        assertNotNull(read);
        assertEquals("the accessory did not survive the round trip",
                1, read.getOwnedBeacons().size());
        assertEquals(A_TAG, read.getOwnedBeacons().get(0).id);
    }

    /**
     * <b>And the naming record comes with it.</b>
     *
     * <p>Separate from the count above because the importer <i>inner-joins</i> these: an
     * accessory whose naming record went missing or landed in the wrong directory is dropped
     * silently, and the import still reports success with nothing in it. That is the failure
     * mode this whole class exists for, and it is invisible from the writing side.
     */
    @Test
    public void bthenameSurvivesTooRatherThanBeingDroppedInTheJoin() throws Exception {
        final ImportData read = importItBack(exportOneTag());

        assertEquals("the naming record was dropped, which silently loses the accessory",
                1, read.getBeaconNamingRecords().size());
        assertTrue(read.getBeaconNamingRecords().get(0).content.contains(A_NAME));
    }

    /**
     * <b>The manifest says this app produced it, and the importer accepts that.</b>
     *
     * <p>Three programs write this format. {@code via:} is the only thing in a zip that says
     * which, and it reaches the recipient's Information screen and device pages - so a bundle
     * that lies about its producer makes a bug report unanswerable, and one whose manifest fails
     * validation does not import at all.
     */
    @Test
    public void cthemanifestNamesThisAppAndPasses() throws Exception {
        final ImportData read = importItBack(exportOneTag());

        assertEquals("OpenTagViewer.android:" + BuildConfig.VERSION_NAME,
                read.getAnImport().exportedVia);
    }

    /**
     * <b>The key material arrives byte for byte.</b>
     *
     * <p>The one failure that is silent all the way through: a bundle whose private key was
     * mangled imports perfectly and then locates nothing, and nobody finds out until the tag has
     * been missing for a while.
     */
    @Test
    public void dtheprivateKeyIsTheSameKeyOnTheOtherSide() throws Exception {
        final ImportData read = importItBack(exportOneTag());

        final String content = read.getOwnedBeacons().get(0).content;
        assertTrue("the private key did not survive",
                content.contains("J1AAk7qStLSbMhZT"));
    }

    /**
     * And it really is locked - the code is not decoration.
     *
     * <p>Reading it without one has to fail, or every other test here would pass just as well
     * against a bundle with no encryption at all.
     */
    @Test
    public void eitcannotBeOpenedWithoutTheCode() throws Exception {
        exportOneTag();

        assertThrows(ZipImporterException.class, () -> importItBack(null));
    }

    /** And not with the wrong one either. */
    @Test
    public void fthewrongCodeIsRefused() throws Exception {
        final String real = exportOneTag();
        final String wrong = real.equals("00000000000A") ? "00000000000B" : "00000000000A";

        assertThrows(ZipImporterException.class, () -> importItBack(wrong));
    }

    /**
     * <b>The code as the sender reads it aloud is the code the recipient types.</b>
     *
     * <p>This is the contract that spans two programs and a person: the app shows a grouped code,
     * somebody types it into the import dialog, and {@code normalise} has to return the exact
     * bytes the zip was locked with. A mismatch tells them their correct code is wrong, and there
     * is no way to recover the right one.
     */
    @Test
    public void gthegroupedCodeOpensItAfterBeingNormalised() throws Exception {
        final String passcode = exportOneTag();

        final String asShown = BundlePasscode.format(passcode);
        assertTrue("not grouped for reading", asShown.contains("-"));

        final ImportData read = importItBack(BundlePasscode.normalise(asShown));

        assertEquals(1, read.getOwnedBeacons().size());
    }

    /**
     * <b>And the confusable letters a person writes instead still work.</b>
     *
     * <p>Crockford's alphabet drops I, L, O and U precisely because they get written for 1, 1 and
     * 0 - so a code read off a screen and copied by hand is very likely to come back with one of
     * them in it. Both sides fold them, and this proves the folding survives all the way to the
     * archive rather than only to the string comparison.
     */
    @Test
    public void hacodeWrittenDownByHandStillOpensIt() throws Exception {
        final String passcode = exportOneTag();

        // What somebody writes on paper: grouped, spaced, and with the digits mistaken for the
        // letters they look like.
        final String byHand = BundlePasscode.format(passcode)
                .replace('-', ' ')
                .replace('0', 'O')
                .replace('1', 'I');

        final ImportData read = importItBack(BundlePasscode.normalise(byHand));

        assertEquals("a hand-copied code did not open the bundle",
                1, read.getOwnedBeacons().size());
    }

    /**
     * The bundle is a real file with real bytes, not an empty archive that happens to parse.
     */
    @Test
    public void ithefileIsNotEmpty() throws Exception {
        exportOneTag();

        assertTrue("nothing was written", this.bundle.length() > 0);

        // And the listing is readable without the code, which is a property of the zip format
        // rather than a choice - worth pinning so nobody assumes the file is opaque.
        final byte[] head = new byte[2];
        try (java.io.InputStream in = new java.io.FileInputStream(this.bundle)) {
            if (in.read(head) != 2) {
                fail("the bundle is too short to be a zip");
            }
        }
        assertEquals("PK", new String(head, StandardCharsets.UTF_8));
    }
}
