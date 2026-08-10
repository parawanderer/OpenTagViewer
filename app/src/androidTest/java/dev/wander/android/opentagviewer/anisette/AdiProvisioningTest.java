package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The provisioning round trip, end to end, against Apple's real servers.
 *
 * <p>This is the last unknown in running Anisette in-app. Loading the libraries and
 * initialising ADI are already proven ({@link NativeAdiLoadTest}, {@link StubbedAdiLoadTest});
 * what is not is whether Apple will actually provision a machine that we invented, and whether
 * ADI accepts what comes back.
 *
 * <p>No Apple account is involved: {@code dsId} is -2, which is anonymous provisioning. The
 * identity presented is randomly generated, as it is on every public Anisette server.
 *
 * <p>Talks to Apple, so it is skipped unless asked for:
 *
 * <pre>
 * ./gradlew :app:testEmulatorDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.adiPoc=true \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.wander.android.opentagviewer.anisette.AdiProvisioningTest
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class AdiProvisioningTest {
    private static final String TAG = "AdiProvisioningTest";

    private static final String POC_ENABLED_ARG = "adiPoc";

    /** Apple's, in dependency order. CoreFoundation and mediaplatform are our stubs. */
    private static final List<String> FROM_APPLE = Arrays.asList(
            "libc++_shared.so",
            "libstoreservicescore.so"
    );

    private static final List<String> STUBS = Arrays.asList("CoreFoundation", "mediaplatform");

    private static File libraryDir;
    private static File provisioningDir;

    @BeforeClass
    public static void loadAdi() throws Exception {
        assumeThePoCWasAskedFor();

        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String abi = Build.SUPPORTED_ABIS[0];

        libraryDir = new File(context.getFilesDir(), "adi-provisioning/" + abi);
        provisioningDir = new File(context.getFilesDir(), "adi-provisioning-state");

        for (final String stub : STUBS) {
            System.loadLibrary(stub);
        }

        final List<String> everything = new ArrayList<>(FROM_APPLE);
        everything.add(AdiLibrary.CORE_ADI);
        AdiLibraryFetcher.fetchInto(libraryDir, abi, everything);
    }

    /**
     * Provision, then confirm ADI agrees it is provisioned.
     *
     * <p>Deliberately one test rather than three: provisioning is a single transaction with
     * Apple, and a half-completed one leaves state on both sides. Splitting it would mean
     * either re-running it per test or ordering tests by side effect.
     */
    @Test
    public void aMachineCanBeProvisionedFromScratch() throws Exception {
        assumeThePoCWasAskedFor();

        final AdiDeviceIdentity identity = AdiDeviceIdentity.generate();
        Log.i(TAG, "provisioning as " + identity.uniqueDeviceIdentifier()
                + " (ADI id " + identity.adiIdentifier() + ")");

        try (AdiLibrary adi = AdiLibrary.open(libraryDir, FROM_APPLE)) {
            adi.initialise(libraryDir, provisioningDir, identity.adiIdentifier());

            // A fresh identity has never been provisioned, so this is the state we expect to
            // start from. If it is not, the test is not testing what it claims to.
            final int before = adi.getLoginCode(AdiProvisioning.ANONYMOUS_DS_ID);
            assertEquals("a newly generated identity should not be provisioned yet",
                    AdiError.NOT_PROVISIONED, before);

            final AdiProvisioning provisioning = new AdiProvisioning(identity, adi);
            final boolean ran = provisioning.provisionIfNeeded(AdiProvisioning.ANONYMOUS_DS_ID);
            assertTrue("provisioning should have run for a fresh identity", ran);

            final int after = adi.getLoginCode(AdiProvisioning.ANONYMOUS_DS_ID);
            assertEquals("ADI should consider the machine provisioned now", 0, after);

            // Second call must be a no-op rather than provisioning again - re-provisioning on
            // every login is exactly the pattern that makes Apple suspicious.
            assertTrue("provisioning twice should be a no-op",
                    !provisioning.provisionIfNeeded(AdiProvisioning.ANONYMOUS_DS_ID));

            assertNotEquals("ADI should have persisted something to its provisioning path",
                    0, provisioningDir.list() == null ? 0 : provisioningDir.list().length);
        }
    }

    private static void assumeThePoCWasAskedFor() {
        final String value = InstrumentationRegistry.getArguments().getString(POC_ENABLED_ARG);
        Assume.assumeTrue(
                "skipped: pass -Pandroid.testInstrumentationRunnerArguments." + POC_ENABLED_ARG
                        + "=true to run the Anisette proof of concept",
                "true".equals(value));
    }
}
