package dev.wander.android.opentagviewer.poc;

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

/**
 * The same thing {@link NativeAdiLoadTest} proves, but without Apple's CoreFoundation,
 * mediaplatform, ICU, curl, libxml2, libdispatch or BlocksRuntime.
 *
 * <p>libstoreservicescore.so imports symbols from only three libraries - libc++_shared (141),
 * libmediaplatform (125) and libCoreFoundation (93). The other seven are in the dependency
 * closure purely because those two depend on them, and ICU alone is 14.6 MB.
 *
 * <p>bionic resolves DT_NEEDED by SONAME against libraries that are already loaded, and does
 * not check who built them. So this run substitutes generated stubs carrying those two SONAMEs
 * (see app/src/main/cpp/stubs/) and downloads three libraries instead of twelve.
 *
 * <pre>
 * NativeAdiLoadTest    12 libraries   12.6 MB downloaded   ~28 MB on disk
 * this                  3 libraries    ~2.9 MB downloaded   ~5.2 MB on disk
 * </pre>
 *
 * <p>Whether that is sound depends on ADI never actually <em>calling</em> into those two
 * libraries. Every stub logs its own name under the tag {@code adi-stub} and returns zero, so
 * a passing run with no {@code adi-stub} output means nothing was reached, and a passing run
 * with some output tells us exactly which symbols need implementing for real.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class StubbedAdiLoadTest {
    private static final String TAG = "StubbedAdiLoadTest";

    private static final String POC_ENABLED_ARG = "adiPoc";

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
        assumeThePoCWasAskedFor();

        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String abi = Build.SUPPORTED_ABIS[0];

        // A separate directory from NativeAdiLoadTest's, so neither test can pass because the
        // other one left a library lying around.
        libraryDir = new File(context.getFilesDir(), "adi-poc-stubbed/" + abi);
        nativeLibraryDir = new File(context.getApplicationInfo().nativeLibraryDir);

        final java.util.List<String> everything = new java.util.ArrayList<>(FROM_APPLE);
        everything.add(CORE_ADI);

        final long downloaded = AdiLibraryFetcher.fetchInto(libraryDir, abi, everything);
        Log.i(TAG, "fetched " + everything.size() + " libraries from Apple ("
                + downloaded / 1000 + " kB), substituting " + STUBS.size() + " stubs from "
                + nativeLibraryDir);
    }

    /** Ours first, so the SONAMEs are already taken by the time Apple's library asks. */
    @Test
    public void step1_stubsStandInForApplesLibraries() {
        assumeThePoCWasAskedFor();

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
        assumeThePoCWasAskedFor();
        Assume.assumeTrue("libstoreservicescore.so did not load", storeServicesCore != 0);

        for (final AdiFunction function : AdiFunction.values()) {
            assertTrue(function + " did not resolve",
                    NativeAdiProbe.resolve(storeServicesCore, function.symbol()) != 0);
        }
    }

    /**
     * The one that matters. If ADI initialises against stubbed CoreFoundation and
     * mediaplatform, then 20 MB of ICU, curl and libxml2 never has to be downloaded at all.
     */
    @Test
    public void step3_adiInitialisesWithoutApplesDependencies() {
        assumeThePoCWasAskedFor();
        Assume.assumeTrue("libstoreservicescore.so did not load", storeServicesCore != 0);

        final long function = NativeAdiProbe.resolve(
                storeServicesCore, AdiFunction.LOAD_LIBRARY_WITH_PATH.symbol());
        Assume.assumeTrue(AdiFunction.LOAD_LIBRARY_WITH_PATH + " did not resolve", function != 0);

        final int result = NativeAdiProbe.loadLibraryWithPath(
                function, libraryDir.getAbsolutePath());

        Log.i(TAG, "ADILoadLibraryWithPath returned " + result
                + " - check logcat for tag adi-stub to see what ADI reached into");
        assertEquals("ADILoadLibraryWithPath returned an ADI error code", 0, result);
    }

    private static long open(File library) {
        final long[] handle = new long[1];
        final String error = NativeAdiProbe.open(library.getAbsolutePath(), handle);
        if (error != null) {
            fail("dlopen(" + library.getName() + ") failed: " + error
                    + "\n  => if this names a missing symbol, the stub lists in "
                    + "app/src/main/cpp/stubs/ are incomplete and need regenerating");
        }
        return handle[0];
    }

    private static void assumeThePoCWasAskedFor() {
        final String value = InstrumentationRegistry.getArguments().getString(POC_ENABLED_ARG);
        Assume.assumeTrue(
                "skipped: pass -Pandroid.testInstrumentationRunnerArguments." + POC_ENABLED_ARG
                        + "=true to run the Anisette proof of concept",
                "true".equals(value));
    }
}
