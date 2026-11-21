package dev.wander.android.opentagviewer.ui.importing;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import dev.wander.android.opentagviewer.R;

/**
 * The one "Import" entry, and what it can import.
 *
 * <p>The map's menu and My Devices each had three separate import items, written twice: the
 * exporter's zip, an OpenHaystack key file, and Google Find Hub. One entry and one chooser keep
 * the menus short, and both screens use this class so they cannot drift apart.
 *
 * <p><b>Rows, not a plain list.</b> Bare text items in a dialog read as a paragraph rather than
 * as three things to tap, so each choice is drawn like Settings' rows: an icon, a title, and a
 * line saying what it is.
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

    /** In this order, which is also the order of {@link #CHOICES}. */
    static final int EXPORTER_ZIP = 0;
    static final int OPENHAYSTACK_KEYS = 1;
    static final int GOOGLE_FIND_HUB = 2;

    private static final class Choice {
        final int icon;
        final int title;
        final int subtitle;

        Choice(final int icon, final int title, final int subtitle) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    private static final Choice[] CHOICES = {
            new Choice(R.drawable.opentagviewer_icon_themed, R.string.import_choice_exporter,
                    R.string.import_choice_exporter_subtitle),
            new Choice(R.drawable.key_24px, R.string.import_choice_openhaystack,
                    R.string.import_choice_openhaystack_subtitle),
            new Choice(R.drawable.host_24px, R.string.import_choice_google,
                    R.string.import_choice_google_subtitle),
    };

    public static AlertDialog show(final Context context, final Choices choices) {
        final ArrayAdapter<Choice> rows = new ArrayAdapter<>(
                context, R.layout.import_chooser_item, CHOICES) {
            @NonNull
            @Override
            public View getView(final int position, final View convertView,
                                @NonNull final ViewGroup parent) {
                final View row = convertView != null ? convertView
                        : LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.import_chooser_item, parent, false);
                final Choice choice = CHOICES[position];
                ((ImageView) row.findViewById(R.id.import_choice_icon))
                        .setImageResource(choice.icon);
                ((TextView) row.findViewById(R.id.import_choice_title)).setText(choice.title);
                ((TextView) row.findViewById(R.id.import_choice_subtitle))
                        .setText(choice.subtitle);
                return row;
            }
        };

        return new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.do_import)
                .setAdapter(rows, (dialog, which) -> {
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
