package dev.wander.android.opentagviewer.util.rx;

import android.util.Log;

import io.reactivex.rxjava3.exceptions.UndeliverableException;
import io.reactivex.rxjava3.functions.Consumer;

/**
 * What happens to an error RxJava has nobody to give to.
 *
 * <p>RxJava's default is to hand it to the thread's uncaught-exception handler, which on Android
 * kills the process. The common way to reach that here is a screen that went away while its
 * work was still running: a theme or language change rebuilds {@code MapsActivity}, the old one
 * disposes its subscriptions, and its location fetch, already queued on {@code PythonLock},
 * then fails against an account the new screen has just closed. Nobody is listening, nothing
 * was lost, and the new screen fetches for itself. That crashed the app on 2026-11-21.
 *
 * <p><b>Only {@link UndeliverableException} is swallowed.</b> That is the wrapper RxJava uses for
 * exactly this case, an error with no subscriber left. Anything else reaching the handler is a
 * bug, such as an exception thrown inside an {@code onError} consumer, and still crashes, so it
 * gets reported rather than hidden.
 */
public final class UndeliverableErrors {
    private static final String TAG = UndeliverableErrors.class.getSimpleName();

    private UndeliverableErrors() {
    }

    /** For {@code RxJavaPlugins.setErrorHandler}. */
    public static Consumer<Throwable> handler() {
        return UndeliverableErrors::handle;
    }

    static void handle(final Throwable error) {
        if (error instanceof UndeliverableException) {
            Log.w(TAG, "An error arrived after whoever asked had gone; ignoring it",
                    error.getCause() != null ? error.getCause() : error);
            return;
        }

        final Thread thread = Thread.currentThread();
        final Thread.UncaughtExceptionHandler crash = thread.getUncaughtExceptionHandler();
        if (crash != null) {
            crash.uncaughtException(thread, error);
        }
    }
}
