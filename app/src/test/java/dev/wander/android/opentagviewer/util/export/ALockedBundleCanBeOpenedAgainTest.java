package dev.wander.android.opentagviewer.util.export;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.LocalFileHeader;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.wander.android.opentagviewer.util.parse.BundlePasscode;

/**
 * A bundle this app writes, opened again.
 *
 * <p><b>On the JVM, because nothing here needs a device</b> - it is zip4j writing bytes and zip4j
 * reading them back. See AGENTS.md rule 13. The full round trip through {@code
 * AppleZipImporterUtil} is instrumented, because that one wants a {@code Context} and a Python
 * interpreter; what is proved here is the container, which is the half that can be wrong in a way
 * nobody notices until a recipient tries to open the file.
 *
 * <p>The failure this exists for is not a crash. A bundle written with the wrong encryption
 * scheme, or with entries silently truncated, is a file that looks exactly like a working one -
 * and it is discovered by somebody else, on another phone, after the sender has deleted their
 * copy.
 */
public class ALockedBundleCanBeOpenedAgainTest {

    /** Shaped like the real thing: a manifest, a beacon record, a naming record. */
    private static Map<String, byte[]> someEntries() {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("OPENTAGVIEWER.yml",
                "version: 0.0.2\nvia: OpenTagViewer.android:1.1.0\n".getBytes(StandardCharsets.UTF_8));
        entries.put("OwnedBeacons/F612A183-492B-45A8-A5A2-233CA9062A94.plist",
                "<plist><dict><key>privateKey</key></dict></plist>".getBytes(StandardCharsets.UTF_8));
        entries.put("BeaconNamingRecord/F612A183-492B-45A8-A5A2-233CA9062A94/6C68CF6D.plist",
                "<plist><dict><key>name</key><string>cat</string></dict></plist>"
                        .getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private static byte[] zipped(final Map<String, byte[]> entries, final String passcode)
            throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        BundleZipWriter.write(out, entries, passcode);
        return out.toByteArray();
    }

    /** Reads it all back, or throws the way a real reader would. */
    private static Map<String, byte[]> unzipped(final byte[] archive, final String passcode)
            throws IOException {
        final Map<String, byte[]> found = new LinkedHashMap<>();

        try (ZipInputStream zip = passcode == null
                ? new ZipInputStream(new ByteArrayInputStream(archive))
                : new ZipInputStream(new ByteArrayInputStream(archive), passcode.toCharArray())) {

            LocalFileHeader header;
            while ((header = zip.getNextEntry()) != null) {
                final ByteArrayOutputStream content = new ByteArrayOutputStream();
                final byte[] buffer = new byte[4096];
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    content.write(buffer, 0, read);
                }
                found.put(header.getFileName(), content.toByteArray());
            }
        }

        return found;
    }

    /**
     * <b>Everything that went in comes back, byte for byte.</b>
     *
     * <p>Byte equality rather than "it parses", because a plist carries the private key. A bundle
     * that survives a lossy round trip imports cleanly and then locates nothing, which is the
     * worst failure available here: silent, delayed, and somebody else's.
     */
    @Test
    public void whatWentInComesBackOut() throws Exception {
        final Map<String, byte[]> entries = someEntries();
        final String code = BundlePasscode.generate();

        final Map<String, byte[]> back = unzipped(zipped(entries, code), code);

        assertEquals(entries.keySet(), back.keySet());
        for (final Map.Entry<String, byte[]> entry : entries.entrySet()) {
            assertArrayEquals(entry.getKey() + " came back different",
                    entry.getValue(), back.get(entry.getKey()));
        }
    }

    /**
     * <b>AES-256, not ZipCrypto.</b>
     *
     * <p>The format's legacy scheme has been broken since the nineties, and zip4j will happily
     * write it. A bundle protected by it would be protected in name only - and would still open
     * with the code, so every other test here would pass.
     */
    @Test
    public void itisLockedWithSomethingWorthHaving() throws Exception {
        final String code = BundlePasscode.generate();
        final byte[] archive = zipped(someEntries(), code);

        // The right code, because zip4j builds the decrypter inside getNextEntry() - opening with
        // a deliberately wrong one throws before there is a header to inspect.
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive),
                code.toCharArray())) {
            final LocalFileHeader first = zip.getNextEntry();

            assertNotNull(first);
            assertTrue("the entry is not encrypted at all", first.isEncrypted());
            assertEquals(EncryptionMethod.AES, first.getEncryptionMethod());
            assertEquals(AesKeyStrength.KEY_STRENGTH_256,
                    first.getAesExtraDataRecord().getAesKeyStrength());
        }
    }

    /** The wrong code does not quietly produce rubbish. */
    @Test
    public void thewrongCodeDoesNotOpenIt() throws Exception {
        final byte[] archive = zipped(someEntries(), "H4K29WMR7TQX");

        assertThrows(IOException.class, () -> unzipped(archive, "0000000000AA"));
    }

    /** And no code at all is refused rather than returning empty files. */
    @Test
    public void noCodeAtAllDoesNotOpenItEither() throws Exception {
        final byte[] archive = zipped(someEntries(), "H4K29WMR7TQX");

        assertThrows(Exception.class, () -> unzipped(archive, null));
    }

    /**
     * The unlocked form still works, for a recipient on an app older than 1.1.0.
     *
     * <p>That app cannot decrypt anything at all, so this is the only bundle it can open - which
     * is why the option exists in the exporter and has to keep existing here.
     */
    @Test
    public void anunlockedBundleNeedsNoCode() throws Exception {
        final Map<String, byte[]> entries = someEntries();

        final Map<String, byte[]> back = unzipped(zipped(entries, null), null);

        assertEquals(entries.keySet(), back.keySet());
        assertArrayEquals(entries.get("OPENTAGVIEWER.yml"), back.get("OPENTAGVIEWER.yml"));
    }

    /**
     * <b>The generated code survives being shown to a person and typed back in.</b>
     *
     * <p>This is the interoperability contract, and it spans two languages and a human. The app
     * generates the code, groups it for display, and the recipient types the grouped form into
     * the import dialog - where {@code normalise} has to return the exact bytes the zip was
     * locked with. A mismatch tells somebody their correct code is wrong.
     */
    @Test
    public void thecodeShownIsTheCodeThatOpensIt() throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            final String code = BundlePasscode.generate();
            final String shown = BundlePasscode.format(code);

            assertEquals("H4K2-9WMR-7TQX is the shape", 14, shown.length());
            assertEquals(code, BundlePasscode.normalise(shown));
        }
    }

    /** And it is a code the exporter would also have produced. */
    @Test
    public void thegeneratedCodeUsesTheAgreedAlphabet() {
        for (int attempt = 0; attempt < 200; attempt++) {
            final String code = BundlePasscode.generate();

            assertEquals(BundlePasscode.LENGTH, code.length());
            for (final char c : code.toCharArray()) {
                assertTrue("'" + c + "' is not in the alphabet the importer accepts",
                        BundlePasscode.ALPHABET.indexOf(c) >= 0);
            }
        }
    }

    /** Two exports are not the same code. */
    @Test
    public void everyCodeIsANewOne() {
        assertFalse(BundlePasscode.generate().equals(BundlePasscode.generate()));
    }

    /** An empty map would produce a zip with nothing in it, which is not a bundle. */
    @Test
    public void nothingToWriteProducesNothingReadable() throws Exception {
        final Map<String, byte[]> back =
                unzipped(zipped(new LinkedHashMap<>(), "H4K29WMR7TQX"), "H4K29WMR7TQX");

        assertTrue(back.isEmpty());
        assertNull(back.get("OPENTAGVIEWER.yml"));
    }
}
