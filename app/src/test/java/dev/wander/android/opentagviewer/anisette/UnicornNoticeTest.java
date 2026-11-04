package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The GPL-2.0 notice the APK carries for Unicorn, and that it names the Unicorn the build uses.
 *
 * <p>Nothing else notices a stale one: bump Unicorn in {@code MacAdi.cmake} and the APK goes on
 * telling people where to find the source of a version it no longer contains.
 */
public class UnicornNoticeTest {

    // Unit tests run from the module directory.
    private static final File NOTICES = new File("src/main/assets/licences/unicorn");

    private static String read(final File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void theApkCarriesTheGplAndUnicornsOwnLicenceFiles() throws Exception {
        assertTrue(read(new File(NOTICES, "COPYING")).contains("GNU GENERAL PUBLIC LICENSE\n"
                + "\t\t       Version 2, June 1991"));
        for (final String name : new String[] {"COPYING.LGPL2", "COPYING_GLIB", "qemu-LICENSE"}) {
            assertTrue(name, new File(NOTICES, name).length() > 0);
        }
    }

    @Test
    public void theNoticeNamesTheUnicornTheBuildFetches() throws Exception {
        final String cmake = read(new File("src/main/cpp/macadi/MacAdi.cmake"));
        final Matcher url = Pattern.compile("URL (https://\\S+unicorn\\S+\\.tar\\.gz)").matcher(cmake);
        final Matcher sha = Pattern.compile("URL_HASH SHA256=([0-9a-f]{64})").matcher(cmake);
        assertTrue(url.find());
        assertTrue(sha.find());

        final String notice = read(new File(NOTICES, "NOTICE.txt"));
        assertTrue("NOTICE.txt does not give " + url.group(1), notice.contains(url.group(1)));
        assertTrue("NOTICE.txt does not give " + sha.group(1), notice.contains(sha.group(1)));
    }
}
