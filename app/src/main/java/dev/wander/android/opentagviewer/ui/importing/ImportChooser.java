package dev.wander.android.opentagviewer.ui.importing;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import dev.wander.android.opentagviewer.R;

/**
 * The one "Import" entry, and what it can import.
 *
 * <p>The map's menu and My Devices each had three separate import items, written twice: the
 * exporter's zip, an OpenHaystack key file, and Google Find Hub. One entry and one chooser keep
 * the menus short, and both screens use this class so they cannot drift apart.
 */
public final class ImportChooser {

    /** What each choice starts. The screens already had these; this only routes to them. */
    public interface Choices {
        void exporterZip();

        void openHaystackKeys();

        void googleFindHub();
    }

    private ImportChooser() {
    }

    /** In this order, which is also the order of {@link #labels}. */
    static final int EXPORTER_ZIP = 0;
    static final int OPENHAYSTACK_KEYS = 1;
    static final int GOOGLE_FIND_HUB = 2;

    static CharSequence[] labels(final Context context) {
        return new CharSequence[] {
                context.getString(R.string.import_choice_exporter),
                context.getString(R.string.import_choice_openhaystack),
                context.getString(R.string.import_choice_google),
        };
    }

    public static AlertDialog show(final Context context, final Choices choices) {
        return new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.do_import)
                .setItems(labels(context), (dialog, which) -> {
                    switch (which) {
                        case EXPORTER_ZIP:
                            choices.exporterZip();
                            break;
                        case OPENHAYSTACK_KEYS:
                            choices.openHaystackKeys();
                            break;
                        case GOOGLE_FIND_HUB:
                            choices.googleFindHub();
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
