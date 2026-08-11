package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * <p>libstoreservicescore.so imports symbols from only three libraries - libc++_shared (141),
 * libmediaplatform (125) and libCoreFoundation (93). The other seven are in the dependency
 * closure purely because those two depend on them, and ICU alone is 14.6 MB.
 *
 * <p>bionic resolves DT_NEEDED by SONAME against libraries that are already loaded, and does
 * not check who built them. So this run substitutes generated stubs carrying those two SONAMEs
 * (see app/src/main/cpp/stubs/) and downloads three libraries instead of twelve.
 *
 * <pre>
 * following DT_NEEDED   12 libraries   12.6 MB downloaded   ~28 MB on disk
 * stubbing               3 libraries    ~2.9 MB downloaded   ~5.2 MB on disk
 * </pre>
 *
 * <p>Whether that is sound depends on ADI never actually <em>calling</em> into those two
 * libraries. Every stub reports itself under the tag {@code adi-stub}: symbols known to be
 * called harmlessly log at INFO (see {@code libmediaplatform.expected}), and anything else
 * logs at ERROR and counts against the session. So a run whose only {@code adi-stub} output is
 * INFO is a clean one, and an ERROR names exactly which symbol needs implementing for real.
 *
 * <p>This is how the app loads ADI.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class StubbedAdiLoadTest {
    private static final String TAG = "StubbedAdiLoadTest";

    private static final String LIVE_TESTS_ARG = "anisetteLiveTests";

    /**
     * Apple libraries we still need. libc++_shared stays real because its mangled names are
     * {@code __ndk1}-namespaced and the NDK's own libc++ should not be assumed ABI-identical
     * without checking.
     */
    private static final List<String> FROM_APPLE = Arrays.asList(
            "libc++_shared.so",
            "libstoreservicescore.so"
    );

    /** Opened by libstoreservicescore itself once it knows the directory - only has to exist. */
    private static final String CORE_ADI = "libCoreADI.so";

    /**
     * Ours, generated at build time and packaged into the APK.
     *
     * <p>Loaded through System.loadLibrary rather than by path, because AGP defaults to
     * {@code extractNativeLibs=false}: the libraries stay compressed inside the APK and are
     * never written to the app's native library directory, so there is no file to open. The
     * linker maps them straight out of the APK and registers them under their SONAMEs, which
     * is all that matters here.
     */
    private static final List<String> STUBS = Arrays.asList(
            "CoreFoundation",
            "mediaplatform"
    );

    private static File libraryDir;
    private static File nativeLibraryDir;
    private static long storeServicesCore;

    @BeforeClass
    public static void downloadOnlyWhatIsNeeded() throws Exception {
        // Returns rather than raising: an Assume here skips the class, which the runner
        // reports as a failed run rather than skipped tests.
        if (!liveTestsWereAskedFor()) {
            return;
        }

        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String abi = Build.SUPPORTED_ABIS[0];

        libraryDir = new File(context.getFilesDir(), "adi-test-stubbed/" + abi);
        nativeLibraryDir = new File(context.getApplicationInfo().nativeLibraryDir);

        final java.util.List<String> everything = new java.util.ArrayList<>(FROM_APPLE);
        everything.add(CORE_ADI);

        final long downloaded = AdiLibraryFetcher.fetchInto(libraryDir, abi, everything);
        Log.i(TAG, "fetched " + everything.size() + " libraries from Apple ("
                + downloaded / 1000 + " kB), substituting " + STUBS.size() + " stubs from "
                + nativeLibraryDir);
    }

    /**
     * Nothing beyond the three libraries was fetched.
     *
     * <p>This is the assertion that keeps the download small. Stubbing is what stops
     * DT_NEEDED dragging in ICU, curl, libxml2, libdispatch and BlocksRuntime - about 20 MB,
     * 14.6 MB of it ICU alone - and if that ever regresses, nothing else here would notice:
     * every other test would still pass, just after downloading five times as much.
     *
     * <p>Asserted as an exact set rather than a list of things to avoid, so that a dependency
     * Apple adds in a future build is caught too.
     */
    @Test
    public void step0_onlyTheThreeNeededLibrariesAreDownloaded() {
        assumeLiveTestsWereAskedFor();

        final Set<String> expected = new TreeSet<>(FROM_APPLE);
        expected.add(CORE_ADI);

        final String[] present = libraryDir.list();
        assertTrue("nothing was downloaded to " + libraryDir, present != null);

        final Set<String> actual = new TreeSet<>(Arrays.asList(present));
        assertEquals("the set of downloaded libraries has changed - if this grew, DT_NEEDED is "
                        + "being followed again and the download is back to ~12.6 MB",
                expected, actual);
    }

    /** Ours first, so the SONAMEs are already taken by the time Apple's library asks. */
    @Test
    public void step1_stubsStandInForApplesLibraries() {
        assumeLiveTestsWereAskedFor();

        for (final String name : STUBS) {
            try {
                System.loadLibrary(name);
            } catch (final UnsatisfiedLinkError e) {
                fail("lib" + name + ".so was not packaged, so there is nothing standing in for "
                        + "Apple's: " + e.getMessage());
            }
            Log.i(TAG, "substituted lib" + name + ".so");
        }

        for (final String name : FROM_APPLE) {
            final File library = new File(libraryDir, name);
            assertTrue(library + " was not downloaded", library.isFile());

            final long handle = open(library);
            if (name.equals("libstoreservicescore.so")) {
                storeServicesCore = handle;
            }
        }
    }

    @Test
    public void step2_theAdiEntryPointsStillResolve() {
        assumeLiveTestsWereAskedFor();
        Assume.assumeTrue("libstoreservicescore.so did not load", storeServicesCore != 0);

        for (final AdiFunction function : AdiFunction.values()) {
            assertTrue(function + " did not resolve",
                    NativeAdi.resolve(storeServicesCore, function.symbol()) != 0);
        }
    }

    /**
     * The one that matters. If ADI initialises against stubbed CoreFoundation and
     * mediaplatform, then 20 MB of ICU, curl and libxml2 never has to be downloaded at all.
     */
    @Test
    public void step3_adiInitialisesWithoutApplesDependencies() {
        assumeLiveTestsWereAskedFor();
        Assume.assumeTrue("libstoreservicescore.so did not load", storeServicesCore != 0);

        final long function = NativeAdi.resolve(
                storeServicesCore, AdiFunction.LOAD_LIBRARY_WITH_PATH.symbol());
        Assume.assumeTrue(AdiFunction.LOAD_LIBRARY_WITH_PATH + " did not resolve", function != 0);

        final int result = NativeAdi.callWithPath(
                function, libraryDir.getAbsolutePath());

        Log.i(TAG, "ADILoadLibraryWithPath returned " + result
                + " - check logcat for tag adi-stub to see what ADI reached into");
        assertEquals("ADILoadLibraryWithPath returned an ADI error code", 0, result);
    }

    private static long open(File library) {
        final long[] handle = new long[1];
        final String error = NativeAdi.open(library.getAbsolutePath(), handle);
        if (error != null) {
            fail("dlopen(" + library.getName() + ") failed: " + error
                    + "\n  => if this names a missing symbol, the stub lists in "
                    + "app/src/main/cpp/stubs/ are incomplete and need regenerating");
        }
        return handle[0];
    }

    private static boolean liveTestsWereAskedFor() {
        return "true".equals(
                InstrumentationRegistry.getArguments().getString(LIVE_TESTS_ARG));
    }

    private static void assumeLiveTestsWereAskedFor() {
        Assume.assumeTrue(
                "skipped: pass -Pandroid.testInstrumentationRunnerArguments." + LIVE_TESTS_ARG
                        + "=true these tests talk to Apple",
                liveTestsWereAskedFor());
    }
}
