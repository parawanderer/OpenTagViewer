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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Proof of concept: can this app run Apple's ADI (Anisette) libraries itself, instead of
 * sending every login through somebody else's Anisette server?
 *
 * <p>Everything answerable from a desktop already has been (notes in tmp/POC-anisette-native.md):
 * the libraries are still on Apple's CDN, they are Android ELF objects for all four ABIs, and
 * all eleven ADI entry points exist in the current build. Three questions need a device, in
 * order of what a bad answer costs:
 *
 * <ol>
 *   <li><b>Will Android load them from app storage?</b> Apps targeting API 29+ are not meant to
 *       execute code from writable storage, and targetSdk here is 35. If dlopen is refused,
 *       the alternative is mapping and relocating the ELF by hand into anonymous pages, which
 *       is weeks of work rather than days.</li>
 *   <li><b>Do the obfuscated symbols resolve</b> in a live process?</li>
 *   <li><b>Does ADI initialise</b> - does ADILoadLibraryWithPath actually return 0?</li>
 * </ol>
 *
 * <p>These talk to Apple's CDN and download ~11 MB, so they are skipped unless asked for:
 *
 * <pre>
 * ./gradlew :app:testEmulatorDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.adiPoc=true \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.wander.android.opentagviewer.poc.NativeAdiLoadTest
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)   // each question only makes sense if the last passed
public class NativeAdiLoadTest {
    private static final String TAG = "NativeAdiLoadTest";

    /** Instrumentation argument that opts in to these network-dependent tests. */
    private static final String POC_ENABLED_ARG = "adiPoc";

    /**
     * The transitive DT_NEEDED closure of libstoreservicescore.so, dependencies first.
     *
     * <p>Order matters. bionic resolves a library's DT_NEEDED against libraries already
     * loaded, matching on SONAME, so loading bottom-up means every dependency is satisfied by
     * the time its dependent is opened. None of these are on the default search path.
     *
     * <p>Apple's libraries dominate the list: libstoreservicescore needs libmediaplatform and
     * libCoreFoundation, which drag in ICU, curl, libxml2 and libdispatch. That is why this
     * costs ~11 MB rather than the ~2.6 MB the two ADI libraries alone would - and why
     * ignoring DT_NEEDED, the way Dadoum's hand-written loader does, is worth doing later.
     */
    private static final List<String> LOAD_ORDER = Arrays.asList(
            "libc++_shared.so",
            "libBlocksRuntime.so",
            "libdispatch.so",
            "libicudata_sv_apple.so",
            "libicuuc_sv_apple.so",
            "libicui18n_sv_apple.so",
            "libcurl.so",
            "libxml2.so",
            "libCoreFoundation.so",
            "libmediaplatform.so",
            "libstoreservicescore.so"
    );

    /**
     * Not opened by us. libstoreservicescore opens it itself once ADILoadLibraryWithPath has
     * been told which directory it is in, so it only has to be on disk beside the others.
     */
    private static final String CORE_ADI = "libCoreADI.so";

    /**
     * The eleven ADI entry points by their obfuscated symbol names, from Dadoum/Provision
     * lib/provision/adi.d. These names are not stable across APK builds, so anything shipping
     * this has to fail legibly when one goes missing rather than crash.
     */
    private static final List<String> ADI_SYMBOLS = Arrays.asList(
            "kq56gsgHG6",   // ADILoadLibraryWithPath
            "Sph98paBcz",   // ADISetAndroidID
            "nf92ngaK92",   // ADISetProvisioningPath
            "p435tmhbla",   // ADIProvisioningErase
            "tn46gtiuhw",   // ADISynchronize
            "fy34trz2st",   // ADIProvisioningDestroy
            "uv5t6nhkui",   // ADIProvisioningEnd
            "rsegvyrt87",   // ADIProvisioningStart
            "aslgmuibau",   // ADIGetLoginCode
            "jk24uiwqrg",   // ADIDispose
            "qi864985u0"    // ADIOTPRequest
    );

    private static final String LOAD_LIBRARY_WITH_PATH = "kq56gsgHG6";

    private static File libraryDir;

    /** Handle for libstoreservicescore.so, shared between the ordered tests. */
    private static long storeServicesCore;

    @BeforeClass
    public static void downloadTheLibraries() throws Exception {
        assumeThePoCWasAskedFor();

        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String abi = Build.SUPPORTED_ABIS[0];
        libraryDir = new File(context.getFilesDir(), "adi-poc/" + abi);

        Log.i(TAG, "device ABI is " + abi + ", caching libraries in " + libraryDir);

        final List<String> everything = new ArrayList<>(LOAD_ORDER);
        everything.add(CORE_ADI);
        AdiLibraryFetcher.fetchInto(libraryDir, abi, everything);
    }

    /** The question the whole feature hangs on. */
    @Test
    public void step1_theLibrariesLoadFromAppStorage() {
        assumeThePoCWasAskedFor();

        for (final String name : LOAD_ORDER) {
            final File library = new File(libraryDir, name);
            assertTrue(library + " was not downloaded", library.isFile());

            final long[] handle = new long[1];
            final String error = NativeAdiProbe.open(library.getAbsolutePath(), handle);

            if (error != null) {
                fail("dlopen(" + name + ") failed: " + error + "\n  => " + diagnose(error));
            }

            Log.i(TAG, "opened " + name);
            if (name.equals("libstoreservicescore.so")) {
                storeServicesCore = handle[0];
            }
        }
    }

    /**
     * Separates the two failure modes, because they lead to completely different work: W^X
     * means writing an ELF loader, a missing dependency means the load order above is wrong
     * and is a five-minute fix.
     */
    private static String diagnose(String error) {
        final String lower = error.toLowerCase();
        if (lower.contains("not permitted") || lower.contains("permission")
                || lower.contains("w^x") || lower.contains("execute")) {
            return "W^X refused it - the manual mmap+relocate loader is required";
        }
        if (lower.contains("cannot locate symbol") || lower.contains("library") && lower.contains("not found")) {
            return "an unresolved dependency, not W^X - the load order or the closure is wrong";
        }
        return "unrecognised - read the full message above before concluding anything";
    }

    /** Loading is not calling: the entry points still have to be there under these names. */
    @Test
    public void step2_theAdiEntryPointsResolve() {
        assumeThePoCWasAskedFor();
        Assume.assumeTrue("libstoreservicescore.so did not load", storeServicesCore != 0);

        final StringBuilder missing = new StringBuilder();
        for (final String symbol : ADI_SYMBOLS) {
            if (NativeAdiProbe.resolve(storeServicesCore, symbol) == 0) {
                missing.append("\n  ").append(symbol);
            } else {
                Log.i(TAG, "resolved " + symbol);
            }
        }

        if (missing.length() > 0) {
            fail("ADI entry points missing from this APK build - the obfuscated names have "
                    + "moved, which will happen eventually:" + missing);
        }
    }

    /**
     * The first real call into Apple's code. ADILoadLibraryWithPath tells libstoreservicescore
     * where libCoreADI.so is; it opens that itself. A 0 here means ADI initialised.
     */
    @Test
    public void step3_adiInitialisesAgainstCoreAdi() {
        assumeThePoCWasAskedFor();
        Assume.assumeTrue("libstoreservicescore.so did not load", storeServicesCore != 0);

        final long function = NativeAdiProbe.resolve(storeServicesCore, LOAD_LIBRARY_WITH_PATH);
        Assume.assumeTrue("ADILoadLibraryWithPath did not resolve", function != 0);

        final int result = NativeAdiProbe.loadLibraryWithPath(
                function, libraryDir.getAbsolutePath());

        Log.i(TAG, "ADILoadLibraryWithPath returned " + result);
        assertEquals("ADILoadLibraryWithPath returned an ADI error code", 0, result);
    }

    /**
     * Skips rather than fails when the opt-in argument is absent, so an ordinary
     * testEmulatorDebugAndroidTest run does not start hitting Apple's CDN.
     */
    private static void assumeThePoCWasAskedFor() {
        final String value = InstrumentationRegistry.getArguments().getString(POC_ENABLED_ARG);
        Assume.assumeTrue(
                "skipped: pass -Pandroid.testInstrumentationRunnerArguments." + POC_ENABLED_ARG
                        + "=true to run the Anisette proof of concept",
                "true".equals(value));
    }
}
