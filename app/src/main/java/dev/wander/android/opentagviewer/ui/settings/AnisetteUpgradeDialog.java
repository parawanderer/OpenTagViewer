package dev.wander.android.opentagviewer.ui.settings;

import android.app.Activity;
import android.app.Dialog;
import android.util.Log;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;

/**
 * The one-time offer to stop signing in through somebody else's server.
 *
 * <p>People updating from an earlier version are deliberately left on their Anisette server:
 * their session is bound to the machine identity that server presented, so moving them without
 * asking would put every existing user through two-factor authentication on update, for a
 * reason none of them could see. But local is the better position - nothing about signing in
 * leaves the device, and no outage elsewhere can stop it - so it is worth asking once.
 *
 * <p><b>Once is the operative word.</b> The offer is marked as made when the dialog appears,
 * not when it is answered, so dismissing it does not bring it back on the next launch. A
 * prompt that returns is a prompt people learn to dismiss without reading, and this one costs
 * a sign-in to accept.
 */
public final class AnisetteUpgradeDialog {

    private static final String TAG = "AnisetteUpgradeDialog";

    private AnisetteUpgradeDialog() {}

    /**
     * Ask, if there is anything to ask about.
     *
     * <p>Deciding here rather than at each call site: the conditions are subtle enough
     * ({@link UserSettings#shouldOfferLocalAnisette(boolean)}) that a second caller would get
     * them slightly wrong, and getting them wrong means either nagging or never asking.
     *
     * @param settings   the current settings, updated in place before the decision is
     *                   delivered, so the caller only has to persist them
     * @param onDecision called exactly once, with true if the user wants to switch now. Either
     *                   way the settings need saving - false still records that the offer was
     *                   made. True additionally means taking them back to signing in, and
     *                   only <em>after</em> the save has landed, or they sign in again and
     *                   arrive back on their old server.
     * @return the dialog, or null when there was nothing to offer
     */
    public static Dialog offerIfDue(
            final Activity activity,
            final UserSettings settings,
            final Consumer<Boolean> onDecision) {

        if (!settings.shouldOfferLocalAnisette(true)) {
            return null;
        }

        // Marked before anything is shown. Whatever happens next - answered, dismissed, or the
        // activity torn down mid-dialog - this was their one time being asked.
        settings.setAnisetteUpgradeOffered(true);

        Log.i(TAG, "offering the move to local Anisette, for the first and only time");

        // Dismissal follows a button press as well as a cancel, so without this the decision
        // would be delivered twice for every answer - saving settings twice, and in the accept
        // case racing a save against the sign-out it triggers.
        final AtomicBoolean delivered = new AtomicBoolean(false);
        final Consumer<Boolean> deliverOnce = accepted -> {
            if (delivered.compareAndSet(false, true)) {
                onDecision.accept(accepted);
            }
        };

        return new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.anisette_upgrade_title)
                .setMessage(R.string.anisette_upgrade_message)
                .setPositiveButton(R.string.anisette_upgrade_switch, (dialog, which) -> {
                    settings.setAnisetteMode(UserSettings.ANISETTE_LOCAL);
                    deliverOnce.accept(true);
                })
                .setNegativeButton(R.string.anisette_upgrade_later, (dialog, which) -> {
                    // Said once, where they are, rather than left to be rediscovered.
                    // Declining now is not the same as never wanting it, and this is the only
                    // mention of it they will get.
                    Toast.makeText(activity, R.string.anisette_upgrade_later_hint,
                            Toast.LENGTH_LONG).show();
                    deliverOnce.accept(false);
                })
                // Dismissing without answering is a "not now" that still has to be recorded,
                // or the flag is never written and the prompt returns forever.
                .setOnDismissListener(dialog -> deliverOnce.accept(false))
                .show();
    }
}
