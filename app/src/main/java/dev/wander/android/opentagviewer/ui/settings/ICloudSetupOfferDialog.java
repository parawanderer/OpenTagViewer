package dev.wander.android.opentagviewer.ui.settings;

import android.app.Activity;
import android.app.Dialog;
import android.util.Log;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;

/**
 * The one-time offer to read tags straight out of the user's iCloud account.
 *
 * <p><b>It is the feature that removes the Mac, and it is invisible.</b> Everything else about
 * this app works from a zip somebody exported on a Mac once. Connecting the account instead means
 * tags arrive on their own, new ones appear without another export, and renaming one writes back
 * - and all of that lives behind a Settings item that somebody who has just signed in has no
 * reason to open. So it is worth saying once, at the moment it would help.
 *
 * <p><b>Asked of exactly two groups, by the same condition.</b> Someone setting the app up for
 * the first time and someone updating from an older version both have no account connected and
 * no record of being asked - see {@link UserSettings#shouldOfferICloud(boolean, boolean)}. Nobody
 * else is bothered.
 *
 * <p><b>Once, and dismissing counts as an answer.</b> The flag is written when the dialog is
 * shown rather than when a button is pressed, so closing it, swiping it away or having the
 * activity torn down mid-dialog all mean the same thing: that was the one time. Somebody who is
 * happy importing zips should never see this again, and a prompt that returns is a prompt people
 * learn to close without reading.
 *
 * <p>Modelled on {@link AnisetteUpgradeDialog}, deliberately - it is the same shape of problem,
 * and two one-time offers behaving differently would be worse than either.
 */
public final class ICloudSetupOfferDialog {

    private static final String TAG = "ICloudSetupOffer";

    private ICloudSetupOfferDialog() {
    }

    /**
     * Ask, if there is anything to ask about.
     *
     * <p>The decision lives here rather than at the call site for the same reason it does on the
     * Anisette offer: the conditions are subtle enough that a second caller would get them
     * slightly wrong, and wrong means either nagging or never asking.
     *
     * @param settings         the current settings, <b>updated in place</b> before the decision
     *                         is delivered, so the caller only has to persist them.
     * @param hasLinkedAccount whether an iCloud keychain membership is already held.
     * @param onDecision       called exactly once - true if they want to set it up now. Either
     *                         way the settings need saving: false still records that the offer
     *                         was made, and without that write the prompt returns forever.
     * @return the dialog, or null when there was nothing to offer.
     */
    public static Dialog offerIfDue(
            final Activity activity,
            final UserSettings settings,
            final boolean hasLinkedAccount,
            final Consumer<Boolean> onDecision) {

        if (!settings.shouldOfferICloud(hasLinkedAccount, true)) {
            return null;
        }

        // Marked before anything is shown. Whatever happens next, this was their one time.
        settings.setIcloudOfferMade(true);

        Log.i(TAG, "offering to connect an iCloud account, for the first and only time");

        // A dismiss follows a button press as well as a cancel, so without this the decision
        // arrives twice for every answer - saving settings twice, and racing the save against
        // the activity this starts.
        final AtomicBoolean delivered = new AtomicBoolean(false);
        final Consumer<Boolean> deliverOnce = accepted -> {
            if (delivered.compareAndSet(false, true)) {
                onDecision.accept(accepted);
            }
        };

        return new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.icloud_offer_title)
                .setMessage(R.string.icloud_offer_message)
                .setPositiveButton(R.string.icloud_offer_set_up_now,
                        (dialog, which) -> deliverOnce.accept(true))
                .setNegativeButton(R.string.icloud_offer_not_now,
                        (dialog, which) -> deliverOnce.accept(false))
                // Dismissing without answering is still an answer, and still has to be recorded
                // or the flag is never written.
                .setOnDismissListener(dialog -> deliverOnce.accept(false))
                .show();
    }
}
