package dev.wander.android.opentagviewer.python;

import android.content.Context;
import android.util.Log;

import com.chaquo.python.Python;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Gives the Python side somewhere to write a measurement that outlives a logcat buffer.
 *
 * <p><b>Why a file at all.</b> The alignment drift the fetch path reports is only worth
 * something as a series over weeks: one reading says nothing, and the question it answers - does
 * a tag that is merely out of contact stay where the extrapolation says it is - is what decides
 * how wide a search has to be. Logcat on a busy phone holds minutes, so relying on it would mean
 * asking somebody to leave a phone plugged into a computer for a fortnight.
 *
 * <p>The app's external files directory, because that one can be pulled off a device with adb
 * without root and without a debuggable build - which a release build is not. Nothing private
 * goes in it: an index and a difference of two indices.
 *
 * <p><b>Attached lazily, and never on its own account.</b> Reaching Python starts an interpreter,
 * which was measured at eleven to twelve seconds on a device. Doing that so a diagnostic can
 * introduce itself would be a bad trade, so this only ever runs from somewhere that is about to
 * start Python anyway, on a background thread, once per process.
 */
public final class PythonDiagnostics {
    private static final String TAG = PythonDiagnostics.class.getSimpleName();
    private static final String MODULE_MAIN = "main";

    private static final AtomicBoolean attached = new AtomicBoolean(false);

    private PythonDiagnostics() {}

    /**
     * Tells Python where to append diagnostics, the first time it is called in this process.
     *
     * <p>Silent on failure. Losing a diagnostic is not worth telling anybody about, and this
     * must never be the reason something else did not happen.
     */
    public static void attach(final Context context) {
        if (!attached.compareAndSet(false, true)) {
            return;
        }

        final File directory = context.getApplicationContext().getExternalFilesDir(null);
        if (directory == null) {
            // No external storage mounted. Python keeps printing to logcat, which is what it did
            // before there was a file at all.
            Log.d(TAG, "No external files directory; diagnostics stay in logcat");
            return;
        }

        Schedulers.io().scheduleDirect(() -> {
            try {
                Python.getInstance().getModule(MODULE_MAIN)
                        .callAttr("setDiagnosticsPath", directory.getAbsolutePath());
                Log.i(TAG, "Diagnostics will be appended to " + directory + "/diagnostics.log");
            } catch (final Exception couldNotAttach) {
                Log.d(TAG, "Could not point Python at a diagnostics file", couldNotAttach);
            }
        });
    }
}
