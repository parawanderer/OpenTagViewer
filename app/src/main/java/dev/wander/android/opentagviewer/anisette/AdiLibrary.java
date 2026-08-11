package dev.wander.android.opentagviewer.anisette;

import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Apple's ADI, loaded and ready to call.
 *
 * <p>Opening it is ordinary {@code dlopen} in dependency order - bionic resolves each
 * library's DT_NEEDED against what is already loaded, matching on SONAME, so nothing here
 * needs to be on a search path. Two of the libraries Apple's own code asks for are stubs this
 * app ships, because nothing ever calls into them; see {@code app/src/main/cpp/stubs/}.
 *
 * <p>Every entry point is resolved up front rather than lazily. Apple obfuscates these names
 * and changes them between APK builds, so discovering a missing one halfway through
 * provisioning would leave a half-built session behind - better to refuse to start.
 */
public final class AdiLibrary implements AutoCloseable {
    private static final String TAG = "AdiLibrary";

    /** Opened by libstoreservicescore itself, so it only has to be on disk beside the rest. */
    public static final String CORE_ADI = "libCoreADI.so";

    private final long handle;
    private final Map<AdiFunction, Long> functions;

    private AdiLibrary(long handle, Map<AdiFunction, Long> functions) {
        this.handle = handle;
        this.functions = functions;
    }

    /** Raised when the libraries cannot be loaded or no longer look like what we expect. */
    public static final class AdiUnavailableException extends Exception {
        public AdiUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * Load ADI out of {@code libraryDir} and resolve its entry points.
     *
     * @param loadOrder Apple's libraries, dependencies first. The stubs must already be
     *                  loaded - they come from the APK, so System.loadLibrary handles them.
     */
    public static AdiLibrary open(File libraryDir, List<String> loadOrder)
            throws AdiUnavailableException {
        long storeServicesCore = 0;

        for (final String name : loadOrder) {
            final File library = new File(libraryDir, name);
            if (!library.isFile()) {
                throw new AdiUnavailableException(library + " has not been downloaded");
            }

            final long[] out = new long[1];
            final String error = NativeAdi.open(library.getAbsolutePath(), out);
            if (error != null) {
                throw new AdiUnavailableException("could not load " + name + ": " + error);
            }
            if (name.equals("libstoreservicescore.so")) {
                storeServicesCore = out[0];
            }
        }

        if (storeServicesCore == 0) {
            throw new AdiUnavailableException(
                    "libstoreservicescore.so was not in the load order, so there is nothing to "
                    + "resolve ADI against");
        }

        return new AdiLibrary(storeServicesCore, resolveAll(storeServicesCore));
    }

    private static Map<AdiFunction, Long> resolveAll(long handle)
            throws AdiUnavailableException {
        final Map<AdiFunction, Long> resolved = new EnumMap<>(AdiFunction.class);
        final List<String> missing = new ArrayList<>();

        for (final AdiFunction function : AdiFunction.values()) {
            final long address = NativeAdi.resolve(handle, function.symbol());
            if (address == 0) {
                missing.add(function.toString());
            } else {
                resolved.put(function, address);
            }
        }

        if (!missing.isEmpty()) {
            throw new AdiUnavailableException(
                    "Apple has re-obfuscated their ADI entry points - these are no longer "
                    + "exported: " + missing + ". Anisette has to fall back to a remote server "
                    + "until AdiFunction is updated; see scripts/update_adi_stub_symbols.py.");
        }
        return resolved;
    }

    /**
     * Point ADI at the directory holding libCoreADI.so, which it opens itself, and at where it
     * may persist its own provisioning state.
     *
     * <p>The provisioning directory belongs to ADI, not to us: it writes and reads it directly,
     * so it has to be a stable location that survives restarts. Deleting it by hand is not the
     * same as resetting Anisette - that needs ADIProvisioningErase.
     */
    public void initialise(File libraryDir, File provisioningDir, String androidId)
            throws AdiUnavailableException {
        withPath(AdiFunction.LOAD_LIBRARY_WITH_PATH, libraryDir);

        if (!provisioningDir.isDirectory() && !provisioningDir.mkdirs()) {
            throw new AdiUnavailableException("could not create " + provisioningDir);
        }

        // Path before identifier, matching the reference implementation's order. ADI keeps
        // per-identity state under the provisioning path, so it wants to know where that is
        // before it is told who it is.
        withPath(AdiFunction.SET_PROVISIONING_PATH, provisioningDir);

        check(AdiFunction.SET_ANDROID_ID, NativeAdi.setAndroidId(
                address(AdiFunction.SET_ANDROID_ID),
                androidId.getBytes(StandardCharsets.UTF_8)));

        Log.i(TAG, "ADI initialised, persisting to " + provisioningDir);
    }

    /** Call one of the {@code int(const char *)} functions with a directory. */
    private void withPath(AdiFunction function, File directory) throws AdiUnavailableException {
        check(function, NativeAdi.callWithPath(address(function), directory.getAbsolutePath()));
    }

    /** The function names itself, so no call site has to repeat it as a string. */
    private static void check(AdiFunction function, int result) throws AdiUnavailableException {
        if (result != 0) {
            throw new AdiUnavailableException(
                    function.appleName() + " failed: " + AdiError.describe(result));
        }
    }

    private long address(AdiFunction function) {
        return this.functions.get(function);
    }

    /** @return 0 if provisioned, {@link AdiError#NOT_PROVISIONED} if not, else an error */
    public int getLoginCode(long dsId) {
        return NativeAdi.getLoginCode(address(AdiFunction.GET_LOGIN_CODE), dsId);
    }

    /** @param out receives {@code {session, adiErrorCode}} */
    public byte[] provisioningStart(long dsId, byte[] spim, int[] out) {
        return NativeAdi.provisioningStart(
                address(AdiFunction.PROVISIONING_START), address(AdiFunction.DISPOSE),
                dsId, spim, out);
    }

    public int provisioningEnd(int session, byte[] persistentTokenMetadata, byte[] trustKey) {
        return NativeAdi.provisioningEnd(
                address(AdiFunction.PROVISIONING_END), session, persistentTokenMetadata,
                trustKey);
    }

    public int provisioningDestroy(int session) {
        return NativeAdi.provisioningDestroy(
                address(AdiFunction.PROVISIONING_DESTROY), session);
    }

    /**
     * The one-time password for a login, produced offline now that the machine is provisioned.
     *
     * @return {@code {machineIdentifier, oneTimePassword}}, in that order
     */
    public byte[][] requestOtp(long dsId) throws AdiUnavailableException {
        final int[] out = new int[1];
        final byte[][] result = NativeAdi.otpRequest(
                address(AdiFunction.OTP_REQUEST), address(AdiFunction.DISPOSE), dsId, out);

        if (result == null) {
            check(AdiFunction.OTP_REQUEST, out[0] == 0 ? -1 : out[0]);
            throw new AdiUnavailableException(
                    AdiFunction.OTP_REQUEST.appleName() + " returned no data despite reporting "
                    + "success, which should not happen");
        }
        return result;
    }

    /**
     * Nothing to release: the libraries stay mapped for the life of the process, which is what
     * we want - ADI holds state and reloading it would throw that away. Present so callers can
     * use try-with-resources without having to know that.
     */
    @Override
    public void close() {
        // deliberately empty
    }
}
