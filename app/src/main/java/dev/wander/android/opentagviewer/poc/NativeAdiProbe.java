package dev.wander.android.opentagviewer.poc;

/**
 * Thin Java face over {@code adi_probe.cpp}: open Apple's ADI libraries, resolve their
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
public final class NativeAdiProbe {

    static {
        // Built from app/src/main/cpp/. Named after the app rather than after ADI because it
        // is this app's one native library, not a dedicated one.
        System.loadLibrary("opentagviewer");
    }

    private NativeAdiProbe() {
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
     * Call ADILoadLibraryWithPath with the directory holding libCoreADI.so.
     *
     * @param function the already-resolved address of the function
     * @return 0 on success, otherwise an ADI error code
     */
    public static native int loadLibraryWithPath(long function, String directory);
}
