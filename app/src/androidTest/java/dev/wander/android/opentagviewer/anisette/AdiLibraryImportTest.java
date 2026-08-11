package dev.wander.android.opentagviewer.anisette;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Taking the ADI libraries out of an Apple Music APK somebody supplied by hand.
 *
 * <p>This exists so that people are not stranded when Apple replaces the build the app knows
 * how to read - at which point sign-in stops working until a new release ships. It is also the
 * one place the app reads a file chosen from anywhere on the device, so what it refuses matters
 * as much as what it accepts.
 *
 * <p>The rejections are what is tested here. Accepting the real thing needs the real APK, which
 * is a 100 MB download from Apple and belongs with the other opt-in tests; every failure path
 * can be arranged exactly, and each one leaves a trap if it is handled sloppily.
 */
@RunWith(AndroidJUnit4.class)
public class AdiLibraryImportTest {

    private Context context;
    private String abi;
    private File libraryDir;

    @Before
    public void useAThrowawayDirectory() {
        this.context = getInstrumentation().getTargetContext();
        this.abi = Build.SUPPORTED_ABIS[0];
        this.libraryDir = new File(this.context.getCacheDir(), "adi-import-test");

        deleteContents(this.libraryDir);
    }

    /**
     * The likeliest mistake: some other APK entirely.
     *
     * <p>It has to say which files were missing. "That did not work" would leave somebody
     * re-downloading the same wrong file.
     */
    @Test
    public void anApkWithoutTheLibrariesIsRejectedAndSaysWhatWasMissing() {
        final Uri apk = zipContaining("classes.dex", "not a library");

        final String problem = AdiLibraryImporter.importFrom(
                context, apk, libraryDir, abi);

        assertNotNull("this cannot be accepted", problem);
        assertTrue("it should name what it wanted: " + problem,
                problem.contains("libstoreservicescore.so"));
        assertNothingWasKept();
    }

    /**
     * The right names carrying the wrong bytes.
     *
     * <p>The important one. These are libraries the app then loads and executes, so the only
     * thing standing between a file off the internet and running its code is the recorded
     * hash. A test that only checked filenames would pass while the app loaded anything.
     */
    @Test
    public void librariesThatDoNotMatchTheRecordedHashesAreRejected() {
        final Uri apk = zipContaining(
                "lib/" + abi + "/libc++_shared.so", "pretend this is Apple's",
                "lib/" + abi + "/libstoreservicescore.so", "and so is this",
                "lib/" + abi + "/" + AdiLibrary.CORE_ADI, "and this");

        final String problem = AdiLibraryImporter.importFrom(
                context, apk, libraryDir, abi);

        assertNotNull("wrong bytes must not be accepted", problem);
        assertNothingWasKept();
    }

    /**
     * A rejected import leaves nothing behind.
     *
     * <p>Not tidiness. The fetcher skips the network for files that are already present, so a
     * half-written import would be picked up on the next sign-in as though it had been
     * verified - which is how unchecked bytes would end up being loaded.
     */
    @Test
    public void aRejectedImportLeavesNothingForTheNextAttemptToFind() {
        AdiLibraryImporter.importFrom(context, zipContaining(
                "lib/" + abi + "/libc++_shared.so", "wrong",
                "lib/" + abi + "/libstoreservicescore.so", "wrong",
                "lib/" + abi + "/" + AdiLibrary.CORE_ADI, "wrong"), libraryDir, abi);

        assertNothingWasKept();
    }

    /** Libraries for a different CPU are not this device's libraries. */
    @Test
    public void librariesForAnotherArchitectureAreNotUsed() {
        final String otherAbi = "armeabi-v7a".equals(abi) ? "x86" : "armeabi-v7a";

        final String problem = AdiLibraryImporter.importFrom(context, zipContaining(
                "lib/" + otherAbi + "/libc++_shared.so", "for another CPU",
                "lib/" + otherAbi + "/libstoreservicescore.so", "for another CPU",
                "lib/" + otherAbi + "/" + AdiLibrary.CORE_ADI, "for another CPU"),
                libraryDir, abi);

        assertNotNull(problem);
        assertTrue("it should say which device it was looking for: " + problem,
                problem.contains(abi));
        assertNothingWasKept();
    }

    /** A file that is not a zip at all fails as a rejection, not as a crash. */
    @Test
    public void somethingThatIsNotAnApkIsRejectedRatherThanThrowing() {
        final File notAZip = new File(context.getCacheDir(), "not-an-apk.bin");
        write(notAZip, "just some bytes");

        final String problem = AdiLibraryImporter.importFrom(
                context, Uri.fromFile(notAZip), libraryDir, abi);

        assertNotNull("a rejection, not an exception", problem);
        assertNothingWasKept();
    }

    /** Every library named in one place, so the importer and the loader cannot disagree. */
    @Test
    public void theRequiredSetIsTheOneTheLoaderUses() {
        final List<String> required = LocalAnisette.requiredLibraries();

        assertEquals(3, required.size());
        assertTrue(required.contains("libstoreservicescore.so"));
        assertTrue(required.contains(AdiLibrary.CORE_ADI));
    }

    // ------------------------------------------------------------------------------------

    private void assertNothingWasKept() {
        for (final String name : LocalAnisette.requiredLibraries()) {
            assertFalse("a rejected import left " + name + " behind",
                    new File(libraryDir, name).exists());
        }
    }

    /** A zip built from alternating entry name and contents. */
    private Uri zipContaining(final String... namesAndContents) {
        final File apk = new File(context.getCacheDir(), "test-apk.zip");

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (final Exception e) {
            throw new IllegalStateException("could not build the test APK", e);
        }

        return Uri.fromFile(apk);
    }

    private static void write(final File file, final String contents) {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(contents.getBytes(StandardCharsets.UTF_8));
        } catch (final Exception e) {
            throw new IllegalStateException("could not write " + file, e);
        }
    }

    private static void deleteContents(final File directory) {
        final File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (final File file : files) {
            if (!file.delete()) {
                throw new IllegalStateException("could not clear " + file);
            }
        }
    }
}
