package dev.wander.android.opentagviewer.anisette;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;

/**
 * Loads Apple's library in a process of its own, so that if it kills that process, it kills
 * nothing else.
 *
 * <p>Declared in the manifest with {@code android:process=":adiprobe"}. See
 * {@link TryItElsewhereFirst} for why, and {@link AppleLibraryProbe} for the side that binds it.
 *
 * <p><b>It runs exactly the calls the app's own process will make</b> -
 * {@link LocalAnisette#openAndInitialise} - so a pass here means something about the load that
 * follows. Only the provisioning directory and the ADI identifier differ: both are throwaway, so
 * this process writes nothing the real identity depends on.
 *
 * <p><b>The process is killed once the app lets go of it.</b> Apple's library cannot be unloaded,
 * and a cached process holding it is memory the app is using for nothing.
 */
public final class AppleLibraryProbeService extends Service {
    private static final String TAG = "AppleLibraryProbe";

    /** The process name after the package, as declared in the manifest. */
    public static final String PROCESS_SUFFIX = ":adiprobe";

    static final int LOAD = 1;
    static final int SURVIVED = 2;

    static final String EXTRA_LIBRARY_DIR = "libraryDir";

    /** How the load went, for the log. Null when it returned. */
    static final String EXTRA_ERROR = "error";

    /**
     * <b>Tests only.</b> Die by the same signal #232's phones die by, instead of loading anything.
     *
     * <p>Here rather than in a test double because the thing under test is the real process
     * boundary: that a native death in this process is seen by the app and does not take the app
     * with it. Nothing outside the app can send it - the service is not exported.
     */
    static final String EXTRA_DIE_INSTEAD = "dieInstead";

    /**
     * 16 lowercase hex characters, the shape ADI accepts, and nobody's identity. The provisioning
     * directory this is paired with is thrown away too.
     */
    private static final String THROWAWAY_ADI_ID = "0000000000000000";

    @Override
    public IBinder onBind(final Intent intent) {
        return new Messenger(new Handler(Looper.getMainLooper(), this::handle)).getBinder();
    }

    private boolean handle(final Message message) {
        if (message.what != LOAD) {
            return false;
        }

        final Bundle request = message.getData();
        final Messenger replyTo = message.replyTo;

        // Off the main thread: dlopen of a 5 MB library is not instant, and a service's main thread
        // is watched for ANRs like anyone else's.
        new Thread(() -> {
            if (request.getBoolean(EXTRA_DIE_INSTEAD)) {
                dieLikeTheCrash();
            }

            final String error = this.load(new File(request.getString(EXTRA_LIBRARY_DIR)));

            final Message reply = Message.obtain(null, SURVIVED);
            final Bundle data = new Bundle();
            data.putString(EXTRA_ERROR, error);
            reply.setData(data);
            try {
                replyTo.send(reply);
            } catch (final RemoteException e) {
                Log.w(TAG, "The app stopped waiting before the load finished", e);
            }
        }, "AppleLibraryProbe").start();

        return true;
    }

    /** @return null if the load returned, otherwise why it threw - which is not a crash */
    private String load(final File libraryDir) {
        final File provisioningDir = new File(this.getCacheDir(), "anisette-probe/provisioning");
        try {
            LocalAnisette.openAndInitialise(libraryDir, provisioningDir, THROWAWAY_ADI_ID);
            Log.i(TAG, "Apple's library loaded in a separate process without crashing it");
            return null;
        } catch (final Throwable e) {
            Log.i(TAG, "Apple's library did not load, but did not crash the process either", e);
            return String.valueOf(e);
        }
    }

    /**
     * Sends this process the signal #232's phones died by, and <b>never returns</b>.
     *
     * <p><b>Never returning is the part that matters.</b> {@code kill} delivers the signal to the
     * process, which hands it to whichever thread it likes - on the emulator, the main one - and the
     * system then spends a moment writing a crash report before the process is gone. The first
     * version returned from here, so this thread went on to load, and replied "survived" 11ms after
     * the fatal signal and before the death. A real crash is in the loading thread itself and can
     * send nothing, so this thread does not either.
     *
     * <p>If the process is somehow still here after ten seconds, it is killed outright: still a
     * death the app has to notice, rather than a test that waits out its timeout.
     */
    private static void dieLikeTheCrash() {
        try {
            Os.kill(Os.getpid(), OsConstants.SIGBUS);
        } catch (final ErrnoException e) {
            Log.w(TAG, "Could not send SIGBUS; killing the process instead", e);
            Process.killProcess(Process.myPid());
        }

        sleepThrough(10_000);
        Log.w(TAG, "Still alive ten seconds after SIGBUS; killing the process outright");
        Process.killProcess(Process.myPid());
        while (true) {
            sleepThrough(10_000);
        }
    }

    /** Sleeps the whole duration: this thread must not get as far as replying. */
    private static void sleepThrough(final long millis) {
        final long deadline = System.currentTimeMillis() + millis;
        long left;
        while ((left = deadline - System.currentTimeMillis()) > 0) {
            try {
                Thread.sleep(left);
            } catch (final InterruptedException ignored) {
                // Keep sleeping.
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Unbound, so nobody is waiting on this process - and it holds a library that cannot be
        // unloaded. Leaving it cached would only keep that memory until the system noticed.
        Process.killProcess(Process.myPid());
    }
}
