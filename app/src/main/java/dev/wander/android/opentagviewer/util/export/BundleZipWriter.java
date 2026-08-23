package dev.wander.android.opentagviewer.util.export;

import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

/**
 * Puts the files of an export bundle into a zip, locked with a code.
 *
 * <p><b>Java writes the container because Python cannot.</b> The format itself is built by
 * {@code opentagviewer_export.build_export}, shared with the desktop exporter so there is one
 * implementation of it - but that package's own sink needs {@code pyzipper} for an encrypted
 * archive, which is not in Chaquopy's pip list and pulls a native crypto dependency. zip4j is
 * already here for <i>reading</i> locked bundles, and it writes them too. So Python owns the
 * format and Java owns the container, and neither has to grow a dependency for the other.
 *
 * <p><b>AES-256 under the WinZip scheme</b>, which is what {@code zipsink.py} produces and what
 * {@code AppleZipImporterUtil} already opens. Not ZipCrypto: it is the format's legacy scheme,
 * broken since the nineties, and a bundle protected by it would be protected in name only.
 *
 * <p>The listing is not encrypted, only the entries. Anybody holding the file can see how many
 * accessories are in it and what their identifiers are; they cannot read a key without the code.
 * That is a property of the zip format rather than a choice made here, and it is worth knowing
 * before treating the file as opaque.
 */
public final class BundleZipWriter {

    private BundleZipWriter() {}

    /**
     * @param entries  path within the zip to its bytes, as {@code build_export} produced them.
     *                 Iteration order is preserved, so a caller handing over a {@code
     *                 LinkedHashMap} gets a predictable archive.
     * @param passcode the code to lock it with, or null for an unlocked bundle. Null exists for
     *                 recipients on an app older than 1.1.0, which cannot decrypt anything at
     *                 all - see the exporter's own checkbox for the same reason.
     * @throws IOException if the destination will not take it. The caller is writing to a place
     *                     the user picked, so a full disk or a removed drive is ordinary.
     */
    public static void write(
            final OutputStream destination,
            final Map<String, byte[]> entries,
            final String passcode) throws IOException {

        final boolean locked = passcode != null && !passcode.isEmpty();

        // **char[], because that is what zip4j takes.** It clears the array after use; handing it
        // a String would leave the code in the string pool for as long as the process lives, on a
        // device somebody else may later pick up.
        try (ZipOutputStream zip = locked
                ? new ZipOutputStream(destination, passcode.toCharArray())
                : new ZipOutputStream(destination)) {

            for (final Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(parametersFor(entry.getKey(), locked));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    /**
     * How one file goes in.
     *
     * <p>Built per entry rather than once and reused: zip4j reads the file name off these, so a
     * shared instance would need mutating between entries and that is a footgun in a loop.
     */
    private static ZipParameters parametersFor(final String name, final boolean locked) {
        final ZipParameters parameters = new ZipParameters();
        parameters.setFileNameInZip(name);
        parameters.setCompressionMethod(CompressionMethod.DEFLATE);

        if (locked) {
            parameters.setEncryptFiles(true);
            parameters.setEncryptionMethod(EncryptionMethod.AES);
            parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        }

        return parameters;
    }
}
