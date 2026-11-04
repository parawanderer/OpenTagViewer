package dev.wander.android.opentagviewer.anisette;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * The emulated Mac's CoreADI, as {@link EmulatedMacAnisette} uses it: whatever runs it.
 *
 * <p><b>Two implementations, and the app process only ever holds one of them.</b>
 * {@link MacCoreAdi} is the JNI face of {@code libmacadi.so}, and it is loaded only in the
 * {@code :macadi} process, by {@link MacAdiService}. The app talks to it through
 * {@link RemoteMacAdi}, over binder. So a native crash in Unicorn or in Apple's code kills that
 * process and not the app (AGENTS.md rule 4, #232), and the GPL-2.0 emulator never shares a
 * process with the Apache-2.0 libraries or the map SDKs (docs/emulated-mac-emulator-choice.md).
 *
 * <p><b>An interface so the logic above it can be tested on the JVM</b> (rule 13): the state
 * machine in {@link EmulatedMacAnisette} and the requests in {@link MacProvisioning} need neither
 * the emulator nor a device to check.
 *
 * <p>The semantics are {@link MacCoreAdi}'s; see it for what each call does.
 */
interface MacAdi extends Closeable {

    /** Apple's "no account" pseudo-DSID, which machine-level Anisette is always asked for. */
    long ANONYMOUS_DS_ID = -2L;

    /** The library's own answer that this machine has never been provisioned. */
    final class NotProvisionedException extends IllegalStateException {
        public NotProvisionedException(String message) {
            super(message);
        }
    }

    /**
     * The process running the emulator died during a call. Not something to retry in this
     * process, and recorded so the next launch does not walk into it again.
     */
    final class EmulatorDiedException extends IllegalStateException {
        public EmulatorDiedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** One Anisette header set. */
    final class Headers {
        /** {@code X-Apple-I-MD-M}, raw. */
        public final byte[] machineId;
        /** {@code X-Apple-I-MD}, raw. */
        public final byte[] oneTimePassword;
        /** {@code X-Apple-I-MD-RINFO}. */
        public final long routing;

        public Headers(byte[] machineId, byte[] oneTimePassword, long routing) {
            this.machineId = machineId;
            this.oneTimePassword = oneTimePassword;
            this.routing = routing;
        }
    }

    /** Starts an emulator over a library already checked against {@link MacCoreAdi#BUILD_SHA256}. */
    interface Opener {
        MacAdi open(File library) throws IOException;
    }

    void setHostUuid(byte[] uuid);

    void storePut(String name, byte[] contents);

    /** The library's provisioning state, by file name. Persist it after every call. */
    Map<String, byte[]> storeFiles();

    /** @throws NotProvisionedException if this machine has never been provisioned */
    Headers headers(long dsId);

    /**
     * Turn Apple's {@code spim} into the {@code cpim} to send back.
     *
     * @param sessionOut receives the session id {@link #provisioningFinish} needs
     */
    byte[] provisioningBegin(long dsId, byte[] spim, int[] sessionOut);

    void provisioningFinish(long dsId, int session, byte[] persistentTokenMetadata,
                            byte[] trustKey, long routing);

    /**
     * Whether whatever runs the emulator has gone away between calls, so this one is useless and
     * a new one should be opened. Not a fault: see {@link RemoteMacAdi}.
     */
    default boolean isGone() {
        return false;
    }

    @Override
    void close();
}
