package dev.wander.android.opentagviewer.python;

import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

/**
 * {@link LogRedactor} over {@code exporter.redact}, the same module the desktop wizard's
 * Save logs button runs.
 *
 * <p><b>Blocking, and needs a started interpreter.</b> Never call it on the main thread.
 */
public class ChaquopyLogRedactor implements LogRedactor {
    private static final String TAG = ChaquopyLogRedactor.class.getSimpleName();

    private static final String MODULE = "exporter.redact";

    @Override
    public Redacted redact(final String log) {
        if (log == null) {
            return null;
        }

        try {
            final PyObject module = Python.getInstance().getModule(MODULE);

            // redact() hands back (text, Counter); summarise() turns the second into a sentence.
            final PyObject result = module.callAttr("redact", log);
            final PyObject cleaned = result.asList().get(0);
            final PyObject counts = result.asList().get(1);

            return new Redacted(
                    cleaned.toString(),
                    module.callAttr("summarise", counts).toString());
        } catch (final Exception e) {
            // **Null, not the log.** The caller is about to hand this to somebody who will attach
            // it to a public issue. A redactor that could not run is a reason to withhold the
            // file, never a reason to send the unredacted one - see LogRedactor#redact.
            Log.w(TAG, "Could not redact the log, so it will not be offered", e);
            return null;
        }
    }
}
