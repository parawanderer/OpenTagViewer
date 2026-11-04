package dev.wander.android.opentagviewer.anisette;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.util.Map;

/**
 * The emulated Mac, in a process of its own: {@code libmacadi.so} is loaded here and nowhere else.
 *
 * <p>Declared with {@code android:process=":macadi"}. Two reasons, either sufficient:
 *
 * <ul>
 *   <li><b>A native crash here is not the app's.</b> Unicorn and Apple's emulated code can fault
 *       in ways no {@code catch} sees - #232 made the app unopenable that way (AGENTS.md rule 4).
 *       If this process dies, the call in flight fails with {@link android.os.DeadObjectException}
 *       in the app, {@link EmulatedMacAnisette} records it and falls back to a remote server, and
 *       the sign-in screen stays up.</li>
 *   <li><b>The GPL code is a separate program.</b> Unicorn is GPL-2.0; this process links it, and
 *       the app process, with the Apache-2.0 libraries and the map SDKs, never does. They talk
 *       over binder. See docs/emulated-mac-emulator-choice.md and {@code NOTICE.md}.</li>
 * </ul>
 *
 * <p>The protocol is a handful of transaction codes on a plain {@link Binder}, so it needs no AIDL
 * build step. {@link RemoteMacAdi} is the other end. One emulator per process; the app holds one
 * connection at a time ({@link EmulatedMacAnisette}'s state is static), so a second {@link #OPEN}
 * replaces the first.
 *
 * <p>The library is passed as a path, not as bytes: it is 3.4 MB and a binder transaction is
 * capped at about 1 MB. It is checked against {@link MacCoreAdi#BUILD_SHA256} again here, because
 * the dispatcher offset is only meaningful for those exact bytes.
 */
public final class MacAdiService extends Service {
    private static final String TAG = "MacAdiService";

    /** The process name after the package, as declared in the manifest. */
    public static final String PROCESS_SUFFIX = ":macadi";

    static final String DESCRIPTOR = "dev.wander.android.opentagviewer.anisette.MacAdi";

    static final int OPEN = IBinder.FIRST_CALL_TRANSACTION;
    static final int SET_HOST_UUID = OPEN + 1;
    static final int STORE_PUT = OPEN + 2;
    static final int STORE_FILES = OPEN + 3;
    static final int HEADERS = OPEN + 4;
    static final int PROVISIONING_BEGIN = OPEN + 5;
    static final int PROVISIONING_FINISH = OPEN + 6;
    static final int CLOSE = OPEN + 7;

    /**
     * <b>Tests only.</b> Die by a signal, mid-call, as a fault in the emulator would. Here and not
     * in a fake because the thing under test is the real process boundary. Nothing outside the app
     * can send it: the service is not exported.
     */
    static final int DIE_FOR_A_TEST = OPEN + 99;

    static final int OK = 0;
    static final int NOT_PROVISIONED = 1;
    static final int FAILED = 2;

    private final Object lock = new Object();
    private MacCoreAdi adi;

    @Override
    public IBinder onBind(final Intent intent) {
        return new Binder() {
            @Override
            protected boolean onTransact(final int code, final Parcel data, final Parcel reply,
                                         final int flags) {
                if (code == INTERFACE_TRANSACTION) {
                    reply.writeString(DESCRIPTOR);
                    return true;
                }
                if (code < OPEN || code > DIE_FOR_A_TEST) {
                    return false;
                }
                data.enforceInterface(DESCRIPTOR);
                synchronized (MacAdiService.this.lock) {
                    try {
                        handle(code, data, reply);
                    } catch (final MacAdi.NotProvisionedException e) {
                        reply.setDataPosition(0);
                        reply.setDataSize(0);
                        reply.writeInt(NOT_PROVISIONED);
                        reply.writeString(String.valueOf(e.getMessage()));
                    } catch (final Exception | LinkageError e) {
                        Log.w(TAG, "the emulated Mac refused a call", e);
                        reply.setDataPosition(0);
                        reply.setDataSize(0);
                        reply.writeInt(FAILED);
                        reply.writeString(e.getMessage() == null ? e.toString() : e.getMessage());
                    }
                }
                return true;
            }
        };
    }

    private void handle(final int code, final Parcel data, final Parcel reply) throws Exception {
        switch (code) {
            case OPEN: {
                final byte[] image = MacCoreAdiFetcher.readFile(new File(data.readString()));
                final String sha = MacCoreAdiFetcher.sha256(image);
                if (!MacCoreAdi.BUILD_SHA256.equals(sha)) {
                    throw new IllegalStateException("not the pinned CoreADI build: " + sha);
                }
                if (this.adi != null) {
                    this.adi.close();
                    this.adi = null;
                }
                this.adi = new MacCoreAdi(image);
                reply.writeInt(OK);
                return;
            }
            case SET_HOST_UUID:
                open().setHostUuid(data.createByteArray());
                reply.writeInt(OK);
                return;
            case STORE_PUT:
                open().storePut(data.readString(), data.createByteArray());
                reply.writeInt(OK);
                return;
            case STORE_FILES: {
                final Map<String, byte[]> files = open().storeFiles();
                reply.writeInt(OK);
                reply.writeInt(files.size());
                for (final Map.Entry<String, byte[]> file : files.entrySet()) {
                    reply.writeString(file.getKey());
                    reply.writeByteArray(file.getValue());
                }
                return;
            }
            case HEADERS: {
                final MacAdi.Headers headers = open().headers(data.readLong());
                reply.writeInt(OK);
                reply.writeByteArray(headers.machineId);
                reply.writeByteArray(headers.oneTimePassword);
                reply.writeLong(headers.routing);
                return;
            }
            case PROVISIONING_BEGIN: {
                final long dsId = data.readLong();
                final byte[] spim = data.createByteArray();
                final int[] session = new int[1];
                final byte[] cpim = open().provisioningBegin(dsId, spim, session);
                reply.writeInt(OK);
                reply.writeByteArray(cpim);
                reply.writeInt(session[0]);
                return;
            }
            case PROVISIONING_FINISH:
                open().provisioningFinish(data.readLong(), data.readInt(), data.createByteArray(),
                        data.createByteArray(), data.readLong());
                reply.writeInt(OK);
                return;
            case CLOSE:
                if (this.adi != null) {
                    this.adi.close();
                    this.adi = null;
                }
                reply.writeInt(OK);
                return;
            case DIE_FOR_A_TEST:
                dieLikeACrash();
                return;
            default:
                throw new IllegalStateException("unknown call " + code);
        }
    }

    private MacCoreAdi open() {
        if (this.adi == null) {
            throw new IllegalStateException("the emulated Mac has not been opened");
        }
        return this.adi;
    }

    /** SIGSEGV, as a fault in Unicorn or Apple's code would be, and never returns. */
    private static void dieLikeACrash() {
        try {
            Os.kill(Os.getpid(), OsConstants.SIGSEGV);
        } catch (final ErrnoException e) {
            Process.killProcess(Process.myPid());
        }
        // The signal can land on another thread; this one must not get as far as replying.
        while (true) {
            try {
                Thread.sleep(10_000);
            } catch (final InterruptedException ignored) {
                // Keep waiting to die.
            }
            Process.killProcess(Process.myPid());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Nobody is bound, and the emulator is megabytes the system would keep cached for nothing.
        Process.killProcess(Process.myPid());
    }
}
