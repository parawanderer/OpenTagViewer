package dev.wander.android.opentagviewer.anisette;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Apple's macOS CoreADI, running under an x86_64 emulator in {@code libmacadi.so}.
 *
 * <p><b>Why a Mac, on an Android phone.</b> Since 2026-10-12 Apple issues the Find My token
 * ({@code searchPartyToken}) only to a sign-in whose Anisette machine was minted by a Mac or an
 * iPhone, and since about 2026-10-20 its edge refuses any client that says it is Android. The
 * Android libraries {@link LocalAnisette} loads mint Android machines, so they can no longer
 * reach Find My at all. This runs the Mac library instead - the one in iTunes 12.8.3 for Mac -
 * which mints Mac machines. See sacca97/os-findmy-e, which this is ported from, for the evidence.
 *
 * <p><b>One call is one emulated session.</b> The native side puts the emulator back to its
 * post-load state before each of {@link #headers} and {@link #provisioningBegin}, so nothing
 * builds up however long the app runs. Provisioning is the exception:
 * {@link #provisioningBegin} and {@link #provisioningFinish} share one session and nothing may run
 * between them.
 *
 * <p><b>Not thread-safe.</b> Callers hold their own lock; {@link EmulatedMacAnisette} does.
 */
public final class MacCoreAdi implements Closeable {

    /** The one build this knows how to drive: iTunes 12.8.3, CoreADI 4.19, x86_64 slice. */
    public static final String BUILD_SHA256 =
            "2eafc3c79c7f41a8afbfbeaaf23ee5dab5ad5bda55ec8e54dbc0ddf1d20ce630";

    /**
     * Where {@code _vdfut768ig}, the library's single dispatcher, sits in that build.
     *
     * <p>Tied to {@link #BUILD_SHA256}: the dispatcher's offset is a property of those exact bytes,
     * and a different build at this offset would run arbitrary code as if it were the dispatcher.
     */
    static final long DISPATCH_OFFSET = 0x979A0L;

    /** Apple's "no account" pseudo-DSID, which machine-level Anisette is always asked for. */
    public static final long ANONYMOUS_DS_ID = -2L;

    /** The library's own answer that this machine has never been provisioned. */
    public static final class NotProvisionedException extends IllegalStateException {
        public NotProvisionedException(String message) {
            super(message);
        }
    }

    /** One Anisette header set. */
    public static final class Headers {
        /** {@code X-Apple-I-MD-M}, raw. */
        public final byte[] machineId;
        /** {@code X-Apple-I-MD}, raw. */
        public final byte[] oneTimePassword;
        /** {@code X-Apple-I-MD-RINFO}. */
        public final long routing;

        Headers(byte[] machineId, byte[] oneTimePassword, long routing) {
            this.machineId = machineId;
            this.oneTimePassword = oneTimePassword;
            this.routing = routing;
        }
    }

    static {
        System.loadLibrary("macadi");
    }

    private long handle;

    /**
     * Load the library's bytes into a fresh emulator.
     *
     * <p>The caller has already checked them against {@link #BUILD_SHA256};
     * {@link MacCoreAdiFetcher} will not hand over anything else.
     */
    public MacCoreAdi(byte[] image) {
        this.handle = nativeOpen(image, DISPATCH_OFFSET);
    }

    /** What the library hashes into the machine id. Fixed per machine; see {@link MacIdentity}. */
    public void setHostUuid(byte[] uuid) {
        nativeSetHostUuid(live(), uuid);
    }

    /** Tests only. A frozen clock makes the OTP a constant, which Apple refuses within a minute. */
    void setFrozenTime(long unixSeconds) {
        nativeSetFrozenTime(live(), unixSeconds);
    }

    public void storePut(String name, byte[] contents) {
        nativeStorePut(live(), name, contents);
    }

    /** The library's provisioning state, by file name. Persist it after every call. */
    public Map<String, byte[]> storeFiles() {
        final Object[] flat = nativeStoreFiles(live());
        final Map<String, byte[]> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < flat.length; i += 2) {
            out.put(new String((byte[]) flat[i], StandardCharsets.UTF_8), (byte[]) flat[i + 1]);
        }
        return out;
    }

    /** @throws NotProvisionedException if this machine has never been provisioned */
    public Headers headers(long dsId) {
        final long[] routing = new long[1];
        final byte[][] both = nativeHeaders(live(), dsId, routing);
        return new Headers(both[0], both[1], routing[0]);
    }

    /**
     * Turn Apple's {@code spim} into the {@code cpim} to send back.
     *
     * @param sessionOut receives the session id {@link #provisioningFinish} needs
     */
    public byte[] provisioningBegin(long dsId, byte[] spim, int[] sessionOut) {
        return nativeProvisioningBegin(live(), dsId, spim, sessionOut);
    }

    public void provisioningFinish(long dsId, int session, byte[] persistentTokenMetadata,
                                   byte[] trustKey, long routing) {
        nativeProvisioningFinish(live(), dsId, session, persistentTokenMetadata, trustKey, routing);
    }

    @Override
    public void close() {
        if (this.handle != 0) {
            nativeClose(this.handle);
            this.handle = 0;
        }
    }

    private long live() {
        if (this.handle == 0) {
            throw new IllegalStateException("the emulated Mac has been closed");
        }
        return this.handle;
    }

    private static native long nativeOpen(byte[] image, long dispatch);

    private static native void nativeClose(long handle);

    private static native void nativeSetHostUuid(long handle, byte[] uuid);

    private static native void nativeSetFrozenTime(long handle, long secs);

    private static native void nativeStorePut(long handle, String name, byte[] data);

    private static native Object[] nativeStoreFiles(long handle);

    private static native byte[][] nativeHeaders(long handle, long dsId, long[] routingOut);

    private static native byte[] nativeProvisioningBegin(
            long handle, long dsId, byte[] spim, int[] sessionOut);

    private static native void nativeProvisioningFinish(
            long handle, long dsId, int session, byte[] ptm, byte[] tk, long routing);
}
