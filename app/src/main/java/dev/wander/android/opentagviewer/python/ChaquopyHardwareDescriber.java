package dev.wander.android.opentagviewer.python;

import android.util.Log;

import com.chaquo.python.Python;

/**
 * The real describer: calls {@code main.py:identifyHardware} and
 * {@code main.py:whereToLookUpHardware}, which delegate to
 * {@code opentagviewer_export.hardware} - the same module the desktop exporter uses.
 *
 * <p>The Python runtime is resolved lazily per call rather than held as a field, so constructing
 * this does not require Chaquopy to have started.
 *
 * <p><b>Both calls are blocking and start an interpreter.</b> Never call them on the main thread;
 * the screen that uses this does so on an Rx scheduler and renders what it already knows first.
 */
public class ChaquopyHardwareDescriber implements HardwareDescriber {
    private static final String TAG = ChaquopyHardwareDescriber.class.getSimpleName();
    private static final String MODULE_MAIN = "main";

    @Override
    public String describe(final String plistXml) {
        return call("identifyHardware", plistXml);
    }

    @Override
    public String whereToLookUp(final String plistXml) {
        return call("whereToLookUpHardware", plistXml);
    }

    /**
     * <p>A null or empty plist short-circuits rather than crossing the bridge. A self-generated
     * tag has no plist at all, and the Python side would only decode the empty string and return
     * None anyway - so this saves starting an interpreter to be told what is already known.
     */
    private static String call(final String function, final String plistXml) {
        if (plistXml == null || plistXml.isEmpty()) {
            return null;
        }

        try {
            final var module = Python.getInstance().getModule(MODULE_MAIN);
            final var described = module.callAttr(function, plistXml);
            return described == null ? null : described.toString();
        } catch (final Exception e) {
            // Either Python has not started, or the record is not one the heuristic can read.
            // Neither is worth failing a screen over: the caller keeps what it already had.
            Log.w(TAG, function + " failed", e);
            return null;
        }
    }
}
