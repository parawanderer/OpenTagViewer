package dev.wander.android.opentagviewer.anisette;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The emulated Mac as the app process sees it: {@link MacAdiService}, in {@code :macadi}, over
 * binder. Nothing here loads native code.
 *
 * <p><b>A death is reported, not suffered.</b> If the other process dies during a call - a fault
 * in Unicorn or in Apple's emulated code - that call throws {@link MacAdi.EmulatorDiedException}
 * and the app carries on. One that dies between calls (the system reclaiming memory, say) is not
 * evidence of a fault: the next call throws a plain {@link IllegalStateException} and
 * {@link #isGone} says so, so the caller opens a new one rather than writing the Mac off.
 *
 * <p><b>Unbound the moment the other process dies.</b> A bound service whose process died is
 * restarted by the system while the binding stands, which here would mean faulting again.
 *
 * <p><b>Blocks.</b> Never call it on the main thread: the binding's callbacks arrive there, so
 * waiting on it would wait for itself until the timeout.
 */
final class RemoteMacAdi implements MacAdi {
    private static final String TAG = "RemoteMacAdi";

    /** How long to wait for the other process to start. Generous: an old phone is slow at it. */
    static final long CONNECT_TIMEOUT_SECONDS = 20;

    private final Context context;
    private final ServiceConnection connection;
    private final IBinder binder;
    private volatile boolean gone;
    private volatile boolean unbound;

    private RemoteMacAdi(final Context context, final ServiceConnection connection,
                         final IBinder binder) {
        this.context = context;
        this.connection = connection;
        this.binder = binder;
    }

    /** {@link EmulatedMacAnisette}'s opener in the app. */
    static MacAdi.Opener opener(final Context context) {
        return library -> open(context.getApplicationContext(), library);
    }

    /** Start {@code :macadi} if it is not running, connect, and load {@code library} there. */
    static RemoteMacAdi open(final Context context, final File library) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("the emulated Mac cannot be reached from the main thread");
        }

        final AtomicReference<IBinder> connected = new AtomicReference<>();
        final AtomicReference<RemoteMacAdi> self = new AtomicReference<>();
        final CountDownLatch ready = new CountDownLatch(1);

        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(final ComponentName name, final IBinder service) {
                connected.set(service);
                ready.countDown();
            }

            @Override
            public void onServiceDisconnected(final ComponentName name) {
                final RemoteMacAdi opened = self.get();
                if (opened != null) {
                    opened.onDeath();
                }
            }

            @Override
            public void onBindingDied(final ComponentName name) {
                onServiceDisconnected(name);
            }

            @Override
            public void onNullBinding(final ComponentName name) {
                ready.countDown();
            }
        };

        final boolean bound;
        try {
            bound = context.bindService(new Intent(context, MacAdiService.class), connection,
                    Context.BIND_AUTO_CREATE);
        } catch (final RuntimeException e) {
            throw new IOException("could not start the emulated Mac's process", e);
        }
        if (!bound) {
            throw new IOException("could not start the emulated Mac's process");
        }

        try {
            if (!ready.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS) || connected.get() == null) {
                unbindQuietly(context, connection);
                throw new IOException("the emulated Mac's process did not start within "
                        + CONNECT_TIMEOUT_SECONDS + "s");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            unbindQuietly(context, connection);
            throw new IOException("interrupted while starting the emulated Mac's process", e);
        }

        final RemoteMacAdi opened = new RemoteMacAdi(context, connection, connected.get());
        self.set(opened);
        try {
            opened.binder.linkToDeath(opened::onDeath, 0);
        } catch (final RemoteException alreadyDead) {
            opened.onDeath();
            throw new MacAdi.EmulatorDiedException(
                    "the emulated Mac's process died as it started", alreadyDead);
        }

        Parcel reply = null;
        try {
            reply = opened.call(MacAdiService.OPEN, data -> data.writeString(library.getAbsolutePath()));
        } catch (final RuntimeException e) {
            if (!(e instanceof MacAdi.EmulatorDiedException)) {
                opened.close();
            }
            throw e;
        } finally {
            if (reply != null) {
                reply.recycle();
            }
        }
        return opened;
    }

    /** Whether the other process has gone, so this connection is useless. */
    @Override
    public boolean isGone() {
        return this.gone;
    }

    private void onDeath() {
        this.gone = true;
        Log.w(TAG, "the emulated Mac's process has gone");
        unbindOnce();
    }

    private interface Writer {
        void write(Parcel data);
    }

    /** One call; the reply is positioned after the status. The caller recycles it. */
    private Parcel call(final int code, final Writer writer) {
        if (this.gone) {
            throw new IllegalStateException("the emulated Mac's process has gone");
        }
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        boolean handedOver = false;
        try {
            data.writeInterfaceToken(MacAdiService.DESCRIPTOR);
            writer.write(data);
            try {
                this.binder.transact(code, data, reply, 0);
            } catch (final DeadObjectException e) {
                onDeath();
                throw new MacAdi.EmulatorDiedException(
                        "the emulated Mac's process died during a call", e);
            } catch (final RemoteException e) {
                throw new IllegalStateException("could not reach the emulated Mac", e);
            }

            final int status = reply.readInt();
            if (status == MacAdiService.NOT_PROVISIONED) {
                throw new MacAdi.NotProvisionedException(reply.readString());
            }
            if (status != MacAdiService.OK) {
                throw new IllegalStateException(reply.readString());
            }
            handedOver = true;
            return reply;
        } finally {
            data.recycle();
            if (!handedOver) {
                reply.recycle();
            }
        }
    }

    private void simple(final int code, final Writer writer) {
        call(code, writer).recycle();
    }

    @Override
    public void setHostUuid(final byte[] uuid) {
        simple(MacAdiService.SET_HOST_UUID, data -> data.writeByteArray(uuid));
    }

    @Override
    public void storePut(final String name, final byte[] contents) {
        simple(MacAdiService.STORE_PUT, data -> {
            data.writeString(name);
            data.writeByteArray(contents);
        });
    }

    @Override
    public Map<String, byte[]> storeFiles() {
        final Parcel reply = call(MacAdiService.STORE_FILES, data -> { });
        try {
            final int count = reply.readInt();
            final Map<String, byte[]> out = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                out.put(reply.readString(), reply.createByteArray());
            }
            return out;
        } finally {
            reply.recycle();
        }
    }

    @Override
    public Headers headers(final long dsId) {
        final Parcel reply = call(MacAdiService.HEADERS, data -> data.writeLong(dsId));
        try {
            return new Headers(reply.createByteArray(), reply.createByteArray(), reply.readLong());
        } finally {
            reply.recycle();
        }
    }

    @Override
    public byte[] provisioningBegin(final long dsId, final byte[] spim, final int[] sessionOut) {
        final Parcel reply = call(MacAdiService.PROVISIONING_BEGIN, data -> {
            data.writeLong(dsId);
            data.writeByteArray(spim);
        });
        try {
            final byte[] cpim = reply.createByteArray();
            sessionOut[0] = reply.readInt();
            return cpim;
        } finally {
            reply.recycle();
        }
    }

    @Override
    public void provisioningFinish(final long dsId, final int session,
                                   final byte[] persistentTokenMetadata, final byte[] trustKey,
                                   final long routing) {
        simple(MacAdiService.PROVISIONING_FINISH, data -> {
            data.writeLong(dsId);
            data.writeInt(session);
            data.writeByteArray(persistentTokenMetadata);
            data.writeByteArray(trustKey);
            data.writeLong(routing);
        });
    }

    /** <b>Tests only.</b> See {@link MacAdiService#DIE_FOR_A_TEST}. */
    void dieForATest() {
        simple(MacAdiService.DIE_FOR_A_TEST, data -> { });
    }

    @Override
    public void close() {
        if (!this.gone) {
            try {
                simple(MacAdiService.CLOSE, data -> { });
            } catch (final RuntimeException e) {
                Log.w(TAG, "could not close the emulated Mac cleanly", e);
            }
        }
        unbindOnce();
    }

    private synchronized void unbindOnce() {
        if (!this.unbound) {
            this.unbound = true;
            unbindQuietly(this.context, this.connection);
        }
    }

    private static void unbindQuietly(final Context context, final ServiceConnection connection) {
        try {
            context.unbindService(connection);
        } catch (final IllegalArgumentException alreadyGone) {
            // Nothing left to release.
        }
    }
}
