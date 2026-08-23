package dev.wander.android.opentagviewer.ui.importing;

import android.app.Activity;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import dev.wander.android.opentagviewer.R;

/**
 * "Your tags are here. Where they are is not, yet."
 *
 * <p><b>The message that replaces a two-year-old lie.</b> What used to appear at this moment was
 * a toast saying the import had failed and to restart the app - for an import that had committed
 * its tags to the database and could be seen in the device list. Three reporters across issues
 * <a href="https://github.com/parawanderer/OpenTagViewer/issues/19">#19</a> and
 * <a href="https://github.com/parawanderer/OpenTagViewer/issues/26">#26</a> resolved that
 * "import error" by changing their Anisette server, which tells you both that the message named
 * the wrong phase and what the real cause usually is.
 *
 * <p>A dialog rather than a toast because the first thing a person does here is look at the
 * screen to see whether their tags arrived, and a toast is gone by then. It is also the only
 * place the exception is ever put in front of them: the app knew which failure it was and
 * replaced it with a sentence about importing, which is why none of those reports can be
 * attributed to a cause.
 *
 * <p><b>No report button.</b> This fails when a public Anisette server is down or a phone is on a
 * train, and the fetch retries by itself every minute - so it is weather, and asking for a bug
 * report about weather fills a tracker with reports nobody can act on. The bug page belongs to
 * the half of the import that reads the zip.
 */
public final class ImportedButNotLocatedDialog {

    private ImportedButNotLocatedDialog() {}

    /**
     * @param cause        the failure in the words it arrived in. Shown, not hidden in the log:
     *                     it is the difference between a report that names a cause and 25
     *                     comments of guessing.
     * @param onSettings   opens settings, where the Anisette choice lives
     * @return the dialog, so a test can ask whether it is still showing. Asking Espresso to
     *         prove a view is absent means {@code inRoot(isDialog())} against a screen with no
     *         dialog, and its root picker retries for seconds before admitting there isn't one.
     */
    public static AlertDialog show(
            final Activity activity,
            final String cause,
            final Runnable onSettings) {

        return new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.imported_but_not_located_title)
                .setMessage(activity.getString(
                        R.string.imported_but_not_located_body, cause))
                .setNegativeButton(R.string.ok, null)
                .setPositiveButton(R.string.imported_but_not_located_open_settings,
                        (dialog, which) -> onSettings.run())
                .show();
    }
}
