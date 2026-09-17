package dev.wander.android.opentagviewer.anisette;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Apple's real ADI libraries, loaded and initialised through the app's own code, on the device.
 *
 * <p><b>Runs by default, and talks to nobody at Apple except the CDN the libraries come from.</b> No
 * provisioning and no account: provisioning is what makes {@link AdiProvisioningTest} opt-in, and
 * everything here stops before it. The download is the three libraries out of the Apple Music APK
 * by range request, about 5 MB, checked against the pinned manifest before anything is opened.
 *
 * <p><b>What this covers that nothing else does.</b> {@code adi-on-bionic.yml} loads the library on
 * both architectures, but from a C probe: it never goes through {@link AdiLibraryFetcher},
 * {@link LocalAnisette#openAndInitialise}, {@link AdiLibrary}, the JNI in {@code adi.cpp}, or the
 * separate process the app loads it in first. {@link StubbedAdiLoadTest} is opt-in, and calls
 * {@link NativeAdi} directly rather than the path the app takes. And
 * {@link ApplesLibraryInASeparateProcessTest} only ever gives that process an empty directory - so
 * until this, "the process survived" had never once meant "Apple's library loaded there".
 *
 * <p>On the managed device this is x86_64. The arm64 build, which issue #232 crashed on, is
 * {@code adi-on-bionic.yml}'s.
 */
@RunWith(AndroidJUnit4.class)
public class ApplesRealLibraryOnThisDeviceTest {

    private static final String ABI = Build.SUPPORTED_ABIS[0];

    /** A throwaway identifier in the shape ADI accepts, for a machine that is never provisioned. */
    private static final String THROWAWAY_ADI_ID = "0000000000000000";

    private static Context context;

    /**
     * Not {@link LocalAnisette#libraryDirectory}: tests elsewhere put their own files there, and a
     * download here should not decide what they find.
     */
    private static File libraryDir;

    @BeforeClass
    public static void downloadAndVerifyApplesLibraries() throws Exception {
        context = getInstrumentation().getTargetContext();
        libraryDir = new File(context.getFilesDir(), "adi-on-this-device/" + ABI);

        AdiLibraryFetcher.fetchInto(libraryDir, ABI, LocalAnisette.requiredLibraries());

        final AdiLibraryManifest manifest = AdiLibraryManifest.load(context);
        for (final String name : LocalAnisette.requiredLibraries()) {
            assertNull("the download does not match adi-libraries.json - has Apple replaced the APK?",
                    manifest.verify(new File(libraryDir, name), ABI));
        }
    }

    /**
     * <b>The separate process, with the real library.</b> A reply of "survived" with no error is the
     * only outcome that lets the app go on to load it in its own process, so it has to be reachable.
     */
    @Test
    public void aitLoadsAndInitialisesInTheSeparateProcess() {
        final AppleLibraryProbe probe = new AppleLibraryProbe(context, libraryDir);

        assertEquals(TryItElsewhereFirst.Outcome.SURVIVED, probe.run());
        assertNull("the process survived, but Apple's library did not load there",
                probe.loadError());
    }

    /**
     * <b>The app's own path, in this process, as far as it goes without Apple's servers.</b> The
     * libraries open, every entry point resolves, ADI initialises, and asks whether the machine is
     * provisioned - which it is not, and must say so rather than fail or crash.
     *
     * <p>Then the stand-in libraries are asked whether ADI called into them. The generated stubs
     * count every call; anything but zero means Apple's code now depends on something we only
     * pretend to provide, and the answers it gives cannot be trusted.
     */
    @Test
    public void bitLoadsAndInitialisesInThisProcessWithoutTouchingAGeneratedStub() throws Exception {
        final File provisioningDir = new File(context.getCacheDir(), "adi-on-this-device-provisioning");
        deleteRecursively(provisioningDir);

        final AdiLibrary library = LocalAnisette.openAndInitialise(
                libraryDir, provisioningDir, THROWAWAY_ADI_ID);

        assertEquals("a machine that was never provisioned should say exactly that",
                AdiError.NOT_PROVISIONED, library.getLoginCode(AdiProvisioning.ANONYMOUS_DS_ID));
        assertEquals("ADI called a generated stub - see logcat tag adi-stub for which one",
                0, NativeAdi.generatedStubCalls());
    }

    private static void deleteRecursively(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
