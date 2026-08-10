package dev.wander.android.opentagviewer.anisette;

/**
 * Thin Java face over {@code adi.cpp}: open Apple's ADI libraries, resolve their
 * entry points, and call them.
 *
 * <p>These cannot be ordinary {@code native} methods. The functions are plain C exports with
 * obfuscated names that differ between APK builds, so they have to be resolved by name at
 * runtime and called through function pointers.
 *
 * <p>Handles and function addresses are passed around as {@code long}. That is unpleasant, and
 * deliberate: it keeps all knowledge of which symbol is which in Java, where the table of
 * names lives, rather than splitting it across two languages.
 */
public final class NativeAdi {

    static {
        // Built from app/src/main/cpp/. Named after the app rather than after ADI because it
        // is this app's one native library, not a dedicated one.
        System.loadLibrary("opentagviewer");
    }

    private NativeAdi() {
    }

    /**
     * dlopen one library by absolute path.
     *
     * @param handleOut a one-element array that receives the handle on success
     * @return null on success, or dlerror()'s message - which is what tells W^X apart from a
     *         missing dependency
     */
    public static native String open(String path, long[] handleOut);

    /** @return the address of {@code symbol}, or 0 if this build does not export it */
    public static native long resolve(long handle, String symbol);

    /**
     * Call an ADI function of the shape {@code int(const char *)} - both
     * ADILoadLibraryWithPath and ADISetProvisioningPath have it.
     *
     * @param function the already-resolved address of the function
     * @return 0 on success, otherwise an ADI error code
     */
    public static native int callWithPath(long function, String directory);

    /** ADISetAndroidID. The identifier is invented once and then kept forever. */
    public static native int setAndroidId(long function, byte[] identifier);

    /**
     * ADIGetLoginCode, which doubles as "is this machine provisioned".
     *
     * @return 0 if provisioned, {@link AdiError#NOT_PROVISIONED} if not, otherwise a real error
     */
    public static native int getLoginCode(long function, long dsId);

    /**
     * ADIProvisioningStart. Turns Apple's server metadata into the client metadata that has to
     * be sent back, and opens a session that {@link #provisioningEnd} closes.
     *
     * @param dispose the resolved address of ADIDispose - ADI allocates the result, and it is
     *                freed natively rather than leaving a raw pointer visible to Java
     * @param out     receives {@code {session, adiErrorCode}}; must be at least length 2
     * @return the client metadata, or null if ADI returned an error
     */
    public static native byte[] provisioningStart(
            long function, long dispose, long dsId, byte[] spim, int[] out);

    /** ADIProvisioningEnd, with what Apple returned for the client metadata. */
    public static native int provisioningEnd(
            long function, int session, byte[] persistentTokenMetadata, byte[] trustKey);

    /** ADIProvisioningDestroy. Abandons a session that will not be completed. */
    public static native int provisioningDestroy(long function, int session);
}
