package dev.wander.android.opentagviewer.anisette;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts {@link AppleLibraryProbeService} in its own process, asks it to load Apple's library, and
 * waits to find out whether that process survived.
 *
 * <p><b>Blocks.</b> Call it from a background thread, never the main one: the binding's callbacks
 * arrive on the main thread, so waiting there would wait for itself until the timeout.
 */
final class AppleLibraryProbe implements TryItElsewhereFirst.Probe {
    private static final String TAG = "AppleLibraryProbe";

    /**
     * How long to wait for a reply.
     *
     * <p>Generous, because the slow case is the one that matters. Starting a process takes a second
     * or two on an old phone, and a process that crashes is not reported dead until the system has
     * finished writing its crash report, which takes several more. A load that works takes well
     * under a second.
     */
    static final long TIMEOUT_SECONDS = 20;

    private final Context context;
    private final File libraryDir;
    private final boolean dieInstead;

    AppleLibraryProbe(final Context context, final File libraryDir) {
        this(context, libraryDir, false);
    }

    /** @param dieInstead tests only - see {@link AppleLibraryProbeService#EXTRA_DIE_INSTEAD} */
    AppleLibraryProbe(final Context context, final File libraryDir, final boolean dieInstead) {
        this.context = context.getApplicationContext();
        this.libraryDir = libraryDir;
        this.dieInstead = dieInstead;
    }

    @Override
    public TryItElsewhereFirst.Outcome run() {
        final AtomicReference<TryItElsewhereFirst.Outcome> outcome = new AtomicReference<>();
        final CountDownLatch answered = new CountDownLatch(1);

        final HandlerThread replies = new HandlerThread("AppleLibraryProbeReplies");
        replies.start();

        final Messenger replyTo = new Messenger(new Handler(replies.getLooper(), message -> {
            if (message.what == AppleLibraryProbeService.SURVIVED) {
                final String error = message.getData().getString(AppleLibraryProbeService.EXTRA_ERROR);
                if (error != null) {
                    Log.i(TAG, "The separate process survived loading Apple's library, which threw: "
                            + error);
                }
                settle(outcome, answered, TryItElsewhereFirst.Outcome.SURVIVED);
            }
            return true;
        }));

        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(final ComponentName name, final IBinder binder) {
                // Before sending: a process that dies between the two would otherwise go unseen
                // until the timeout.
                try {
                    binder.linkToDeath(() -> settle(outcome, answered,
                            TryItElsewhereFirst.Outcome.DIED), 0);
                } catch (final RemoteException alreadyDead) {
                    settle(outcome, answered, TryItElsewhereFirst.Outcome.DIED);
                    return;
                }

                final Message request = Message.obtain(null, AppleLibraryProbeService.LOAD);
                final Bundle data = new Bundle();
                data.putString(AppleLibraryProbeService.EXTRA_LIBRARY_DIR,
                        libraryDir.getAbsolutePath());
                data.putBoolean(AppleLibraryProbeService.EXTRA_DIE_INSTEAD, dieInstead);
                request.setData(data);
                request.replyTo = replyTo;

                try {
                    new Messenger(binder).send(request);
                } catch (final RemoteException dead) {
                    settle(outcome, answered, TryItElsewhereFirst.Outcome.DIED);
                }
            }

            @Override
            public void onServiceDisconnected(final ComponentName name) {
                // Only ever a process death, for a service this app never stops itself.
                settle(outcome, answered, TryItElsewhereFirst.Outcome.DIED);
            }

            @Override
            public void onBindingDied(final ComponentName name) {
                settle(outcome, answered, TryItElsewhereFirst.Outcome.DIED);
            }

            @Override
            public void onNullBinding(final ComponentName name) {
                settle(outcome, answered, TryItElsewhereFirst.Outcome.COULD_NOT_START);
            }
        };

        boolean bound = false;
        try {
            bound = this.context.bindService(
                    new Intent(this.context, AppleLibraryProbeService.class),
                    connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                Log.w(TAG, "Could not start the separate process to load Apple's library in");
                return TryItElsewhereFirst.Outcome.COULD_NOT_START;
            }

            if (!answered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "The separate process loading Apple's library did not answer within "
                        + TIMEOUT_SECONDS + "s");
                return TryItElsewhereFirst.Outcome.NO_ANSWER;
            }
            return outcome.get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return TryItElsewhereFirst.Outcome.NO_ANSWER;
        } catch (final RuntimeException e) {
            Log.w(TAG, "Could not start the separate process to load Apple's library in", e);
            return TryItElsewhereFirst.Outcome.COULD_NOT_START;
        } finally {
            // **Promptly, and on every path.** A bound service whose process died is restarted by
            // the system while the binding stands - which here means crashing it again, and a
            // second crash within a minute is what earns a "keeps stopping" dialog.
            if (bound) {
                try {
                    this.context.unbindService(connection);
                } catch (final IllegalArgumentException alreadyGone) {
                    // Nothing left to release.
                }
            }
            replies.quitSafely();
        }
    }

    private static void settle(final AtomicReference<TryItElsewhereFirst.Outcome> outcome,
                               final CountDownLatch answered,
                               final TryItElsewhereFirst.Outcome value) {
        // First answer wins. Unbinding afterwards kills the process on purpose, and that death
        // must not overwrite the reply that came before it.
        if (outcome.compareAndSet(null, value)) {
            answered.countDown();
        }
    }
}
