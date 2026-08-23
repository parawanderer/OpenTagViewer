package dev.wander.android.opentagviewer.ui.mydevices;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.util.parse.BundlePasscode;

/**
 * The code a freshly written bundle was locked with.
 *
 * <p><b>Shown once, because it exists once.</b> Nothing keeps it - the zip holds only what AES
 * needs to verify it, the log does not have it, and the app forgets it when this closes. A bundle
 * whose code was never read is a bundle nobody can ever open, so this is a dialog somebody has to
 * dismiss rather than a toast they can miss while looking at the share sheet.
 *
 * <p>The wizard's equivalent says the same things for the same reasons - see {@code
 * _show_the_code} in {@code wizard.py}. Two programs, one message, because the recipient's
 * experience is identical either way.
 */
public final class ExportedBundleDialog {

    private ExportedBundleDialog() {}

    /**
     * @param passcode the undelimited code. Displayed grouped; copied grouped, because the import
     *                 dialog folds hyphens straight back out and grouped is what a person can
     *                 read aloud without losing their place.
     */
    public static AlertDialog show(final Activity activity, final String passcode) {
        final View view = activity.getLayoutInflater()
                .inflate(R.layout.exported_bundle_dialog, null);

        final EditText shown = view.findViewById(R.id.exported_bundle_code);
        shown.setText(BundlePasscode.format(passcode));
        // Read-only without being disabled, so it can still be selected by hand.
        shown.setKeyListener(null);

        return new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.exported_tags_title)
                .setView(view)
                .setNeutralButton(R.string.exported_tags_copy_code, null)
                .setPositiveButton(R.string.ok, null)
                // **Not cancellable.** Everywhere else a dialog closing by accident costs a tap;
                // here it costs the only copy of the code, and the file has already been written.
                .setCancelable(false)
                .show();
    }

    /**
     * Wire the copy button after showing, so pressing it does not dismiss the dialog.
     *
     * <p>A neutral button with a listener closes the dialog when tapped, which for Copy is
     * exactly wrong: it takes the code off the screen at the moment somebody is checking they
     * got it. Reaching for the button afterwards is the documented way round that.
     */
    public static void wireCopy(final AlertDialog dialog, final String passcode) {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            final ClipboardManager clipboard =
                    dialog.getContext().getSystemService(ClipboardManager.class);
            if (clipboard == null) {
                return;
            }

            clipboard.setPrimaryClip(
                    ClipData.newPlainText("OpenTagViewer code", BundlePasscode.format(passcode)));

            // Android 13 shows its own confirmation, and a toast on top of it reads as a bug.
            // Below that there is nothing, and silence after a tap looks like a dead button.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(dialog.getContext(), R.string.exported_tags_code_copied,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }
}
