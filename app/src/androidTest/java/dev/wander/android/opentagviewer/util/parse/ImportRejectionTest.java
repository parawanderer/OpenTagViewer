package dev.wander.android.opentagviewer.util.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
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
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dev.wander.android.opentagviewer.util.parse.ZipImporterException.Reason;

/**
 * What the importer says when the file it was handed is not an OpenTagViewer export.
 *
 * <p>Every one of these used to end at the same toast - "Error occurred while importing new
 * devices. Try to restart the app and retry." - which is advice for a broken app, handed to
 * somebody who picked the wrong file. Worse, a file that was not a zip at all was reported as
 * {@code OPENTAGVIEWER.yml was empty!}, because {@link java.util.zip.ZipInputStream} does not
 * announce a non-zip: {@code getNextEntry} just returns null, so the entry loop never ran and
 * the first thing the code could think to complain about was the missing manifest.
 *
 * <p>So these tests are about the {@link Reason}, not the message text. The reason is what
 * decides which string the user sees, and it is the part that was missing.
 *
 * <p>The happy paths live in {@link AppleZipImporterUtilTest}. Kept apart because that class
 * is about what a good export produces and this one is about what a bad file is called.
 */
@RunWith(AndroidJUnit4.class)
public class ImportRejectionTest {

    private static final String BEACON = "0FB0AEAC-C083-405E-A979-4AA6A73F5C56";

    private static final String VALID_YAML =
            "version: 0.0.2\n"
            + "exportTimestamp: 1740685990163\n"
            + "via: test\n"
            + "sourceUser: tester\n";

    private Context context;
    private File file;

    @Before
    public void setUp() {
        this.context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @After
    public void tearDown() {
        if (this.file != null && this.file.exists() && !this.file.delete()) {
            this.file.deleteOnExit();
        }
    }

    // -----------------------------------------------------------------------------------
    // not a zip at all
    // -----------------------------------------------------------------------------------

    /** The one that started this: a holiday photo, which was reported as a missing manifest. */
    @Test
    public void aJpegIsNotAZip() throws IOException {
        // The real thing's first bytes. Nothing about them resembles a zip, and the point is
        // that the importer notices before it tries to read entries out of them.
        final Reason reason = importFailureOf(bytes(
                0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01));

        assertEquals(Reason.NOT_A_ZIP, reason);
    }

    @Test
    public void aTextFileRenamedToZipIsNotAZip() throws IOException {
        assertEquals(Reason.NOT_A_ZIP,
                importFailureOf("this is just some text, not an archive".getBytes(StandardCharsets.UTF_8)));
    }

    /** Zero bytes cannot even be sniffed, and must not read as an empty export. */
    @Test
    public void anEmptyFileIsNotAZip() throws IOException {
        assertEquals(Reason.NOT_A_ZIP, importFailureOf(new byte[0]));
    }

    /**
     * A file too short to hold a signature.
     *
     * <p>Its own case because the sniff reads four bytes and a single {@code read} is not
     * obliged to return all of them - a short file is the one input where a partial read is
     * guaranteed rather than theoretical.
     */
    @Test
    public void aTwoByteFileIsNotAZip() throws IOException {
        assertEquals(Reason.NOT_A_ZIP, importFailureOf(bytes('P', 'K')));
    }

    // -----------------------------------------------------------------------------------
    // a zip, but not ours
    // -----------------------------------------------------------------------------------

    @Test
    public void somebodyElsesZipIsNotAnExport() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("holiday/IMG_0001.jpg", "not really a jpeg, but it is in a real zip");
        entries.put("holiday/notes.txt", "sunny");

        assertEquals(Reason.NOT_AN_EXPORT, importFailureOf(zipOf(entries)));
    }

    /**
     * An archive with no entries at all.
     *
     * <p>A zip - it begins at the end-of-central-directory record rather than a local file
     * header - so it must be rejected for not being an export rather than for not being a zip.
     */
    @Test
    public void anEmptyArchiveIsAZipButNotAnExport() throws IOException {
        assertEquals(Reason.NOT_AN_EXPORT, importFailureOf(zipOf(new LinkedHashMap<>())));
    }

    /**
     * The plists without the manifest.
     *
     * <p>Someone zipping the folders by hand rather than running the exporter produces exactly
     * this, and it is not an export however much of an export it looks like.
     */
    @Test
    public void beaconFilesWithoutTheManifestAreNotAnExport() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("OwnedBeacons/" + BEACON + ".plist", "<plist></plist>");

        assertEquals(Reason.NOT_AN_EXPORT, importFailureOf(zipOf(entries)));
    }

    // -----------------------------------------------------------------------------------
    // ours, but not usable
    // -----------------------------------------------------------------------------------

    @Test
    public void aBlankManifestIsADamagedExport() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("OPENTAGVIEWER.yml", "   \n");
        entries.put("OwnedBeacons/" + BEACON + ".plist", "<plist></plist>");

        assertEquals(Reason.DAMAGED, importFailureOf(zipOf(entries)));
    }

    @Test
    public void aManifestMissingItsRequiredFieldsIsADamagedExport() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("OPENTAGVIEWER.yml", "version: 0.0.2\n"); // no via, sourceUser, timestamp
        entries.put("OwnedBeacons/" + BEACON + ".plist", "<plist></plist>");

        assertEquals(Reason.DAMAGED, importFailureOf(zipOf(entries)));
    }

    /** Cut off mid-stream, as a download that stopped early would be. */
    @Test
    public void aTruncatedZipIsADamagedExport() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("OPENTAGVIEWER.yml", VALID_YAML);
        entries.put("OwnedBeacons/" + BEACON + ".plist", longPlist());

        final byte[] whole = zipOf(entries);
        final byte[] cut = new byte[whole.length / 2];
        System.arraycopy(whole, 0, cut, 0, cut.length);

        final Reason reason = importFailureOf(cut);

        // Not NOT_A_ZIP: it starts with a perfectly good signature, and telling somebody their
        // export is not a zip when the first half of one is exactly what they have is worse
        // than useless.
        assertEquals(Reason.DAMAGED, reason);
    }

    /**
     * A well-formed export carrying no tags.
     *
     * <p>Previously this succeeded, and said "Loading location data for 0 new imported devices"
     * - which reads as the import having worked and the tags having vanished afterwards.
     */
    @Test
    public void anExportWithNoTagsSaysSoRatherThanImportingNothing() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("OPENTAGVIEWER.yml", VALID_YAML);

        assertEquals(Reason.NO_TAGS, importFailureOf(zipOf(entries)));
    }

    // -----------------------------------------------------------------------------------
    // the file itself
    // -----------------------------------------------------------------------------------

    @Test
    public void aFileThatIsNotThereIsUnreadable() {
        final Uri missing = Uri.fromFile(
                new File(this.context.getCacheDir(), "no-such-import-file.zip"));

        final ZipImporterException thrown = assertThrows(ZipImporterException.class,
                () -> new AppleZipImporterUtil(this.context).extractZip(missing));

        assertEquals(Reason.UNREADABLE, thrown.getReason());
    }

    // -----------------------------------------------------------------------------------
    // the plumbing that carries the reason to the user
    // -----------------------------------------------------------------------------------

    /**
     * The reason has to survive being wrapped, because RxJava wraps what it is handed and the
     * subscriber that picks the message never sees the exception that was thrown.
     */
    @Test
    public void theReasonSurvivesBeingWrapped() {
        final Throwable wrapped = new RuntimeException("outer",
                new IllegalStateException("middle",
                        new ZipImporterException(Reason.NOT_AN_EXPORT, "inner")));

        assertEquals(Reason.NOT_AN_EXPORT, ZipImporterException.reasonOf(wrapped));
    }

    /** Anything not diagnosed keeps the generic message, rather than borrowing a wrong one. */
    @Test
    public void anUnrelatedFailureHasNoReason() {
        assertEquals(Reason.UNKNOWN,
                ZipImporterException.reasonOf(new IllegalStateException("something else")));
        assertEquals(Reason.UNKNOWN, ZipImporterException.reasonOf(null));
    }

    /** A cause chain that points at itself must not hang the error handler. */
    @Test
    public void aSelfReferencingCauseDoesNotLoop() {
        final Throwable loop = new RuntimeException("round and round") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertEquals(Reason.UNKNOWN, ZipImporterException.reasonOf(loop));
    }

    /**
     * The manifest failure is caught by the same {@code catch} as everything else here.
     *
     * <p>It used to extend {@code RuntimeException} directly, so the branch written to catch
     * import failures did not catch it. Harmless while both branches did the same thing, and
     * exactly the sort of thing that stops being harmless when one of them stops.
     */
    @Test
    public void aManifestFailureIsAnImporterFailure() {
        assertTrue(ZipImporterException.class.isAssignableFrom(
                AppleZipImporterUtil.ImportFileFormatException.class));
    }

    // -----------------------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------------------

    /** Import these bytes, expect it to be refused, and report why. */
    private Reason importFailureOf(final byte[] content) throws IOException {
        this.file = File.createTempFile("otv-reject-test", ".zip", this.context.getCacheDir());
        try (OutputStream out = new FileOutputStream(this.file)) {
            out.write(content);
        }

        final Uri uri = Uri.fromFile(this.file);
        final ZipImporterException thrown = assertThrows(
                "the importer accepted a file it should have refused",
                ZipImporterException.class,
                () -> new AppleZipImporterUtil(this.context).extractZip(uri));

        return thrown.getReason();
    }

    private static byte[] zipOf(final Map<String, String> entries) throws IOException {
        final File scratch = File.createTempFile("otv-reject-src", ".zip");
        try {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(scratch))) {
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    out.putNextEntry(new ZipEntry(entry.getKey()));
                    out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    out.closeEntry();
                }
            }
            final byte[] read = new byte[(int) scratch.length()];
            try (RandomAccessFile in = new RandomAccessFile(scratch, "r")) {
                in.readFully(read);
            }
            return read;
        } finally {
            if (!scratch.delete()) {
                scratch.deleteOnExit();
            }
        }
    }

    private static byte[] bytes(final int... values) {
        final byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    /** Big enough that cutting the archive in half lands inside the deflate stream. */
    private static String longPlist() {
        final StringBuilder sb = new StringBuilder("<plist><dict>");
        for (int i = 0; i < 500; i++) {
            sb.append("<key>filler").append(i).append("</key><string>value</string>");
        }
        return sb.append("</dict></plist>").toString();
    }
}
