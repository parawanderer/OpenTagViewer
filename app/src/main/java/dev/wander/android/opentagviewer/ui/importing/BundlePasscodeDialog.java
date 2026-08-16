package dev.wander.android.opentagviewer.ui.importing;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.function.Consumer;

import dev.wander.android.opentagviewer.R;

/**
 * Asks for the code a bundle was locked with.
 *
 * <p>Reached when the importer reports {@code LOCKED}, which it decides from the zip's own
 * headers before reading any content - so this is a question rather than a failure, and the
 * ordinary path for a bundle made by a current exporter. The exporter locks by default.
 *
 * <p>Shown again with an error rather than replaced by a toast when the code is wrong: the user
 * is mid-task with the code in front of them, and closing the dialog to tell them so would make
 * them start over.
 */
public final class BundlePasscodeDialog {

    private BundlePasscodeDialog() {}

    /**
     * @param wrongPreviousAttempt true when this is a retry after a code that did not work, which
     *                             is the difference between "enter the code" and "that was not it"
     * @param onCode               the normalised code, called only if the user confirms. A
     *                             cancelled dialog calls nothing, so the import is simply
     *                             abandoned.
     */
    public static void show(
            final Activity activity,
            final boolean wrongPreviousAttempt,
            final Consumer<String> onCode) {

        final View view = activity.getLayoutInflater()
                .inflate(R.layout.bundle_passcode_dialog, null);

        final TextInputEditText first = view.findViewById(R.id.bundle_passcode_group_1);
        final TextInputEditText second = view.findViewById(R.id.bundle_passcode_group_2);
        final TextInputEditText third = view.findViewById(R.id.bundle_passcode_group_3);
        final TextView error = view.findViewById(R.id.bundle_passcode_error);

        if (wrongPreviousAttempt) {
            error.setText(R.string.import_failed_wrong_passcode);
            error.setVisibility(View.VISIBLE);
        }

        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.bundle_locked_title)
                .setView(view)
                .setPositiveButton(R.string.bundle_unlock, null)
                .setNegativeButton(android.R.string.cancel, (d, which) -> d.dismiss())
                .create();

        final BundlePasscodeInputManager input = new BundlePasscodeInputManager(
                first, second, third,
                code -> {
                    final View unlock = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    if (unlock != null) {
                        // Twelve characters or nothing: a partial code cannot open anything, and
                        // an enabled button that always fails teaches the user nothing.
                        unlock.setEnabled(code.length() == PASSCODE_LENGTH);
                    }
                    if (!code.isEmpty()) {
                        error.setVisibility(View.GONE);
                    }
                });

        dialog.setOnShowListener(shown -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(input.isComplete());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (!input.isComplete()) {
                    return;
                }
                hideKeyboard(activity, first);
                dialog.dismiss();
                onCode.accept(input.currentCode());
            });

            first.requestFocus();
        });

        dialog.show();
    }

    /** Twelve, from the exporter's {@code PASSCODE_LENGTH}. */
    private static final int PASSCODE_LENGTH = 12;

    private static void hideKeyboard(final Activity activity, final View from) {
        final InputMethodManager imm =
                (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(from.getWindowToken(), 0);
        }
    }
}
