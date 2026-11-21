package dev.wander.android.opentagviewer;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import dev.wander.android.opentagviewer.databinding.ActivityIcloudLinkBinding;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Settings -> Apple iCloud link: reading the account again, and unlinking it.
 *
 * <p>These were two loose rows under the account card in Settings, beside Google Find Hub's
 * row. @parawanderer asked for one entry per account source instead, each opening its own page.
 * Settings opens this one only once the account is linked; before that its row starts the link
 * directly, because there is nothing here to manage yet.
 *
 * <p><b>It passes an import back.</b> The map rebuilds when Settings reports that tags were
 * imported from the account (see {@code SettingsActivity.importedFromAccount}), and the fetch
 * now starts here, one screen further away. So this returns
 * {@link FetchFromICloudActivity#RESULT_IMPORTED} to whoever opened it, or the map would never
 * hear of an import made from this page.
 */
public class ICloudLinkActivity extends AppCompatActivity {
    private static final String TAG = ICloudLinkActivity.class.getSimpleName();

    private ActivityIcloudLinkBinding binding;

    /** Reading whether the account is linked, so leaving does not land on dead views. */
    private Disposable membershipLookup;

    /** Forgetting the membership. Held for the same reason: it reports back on the main thread. */
    private Disposable unlinking;

    private final ActivityResultLauncher<Intent> fetchFromICloudLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            (ActivityResult result) -> {
                final Intent data = result.getData();
                if (data != null
                        && data.getBooleanExtra(FetchFromICloudActivity.RESULT_IMPORTED, false)) {
                    // Kept for the rest of this page's life: a second fetch that imports nothing
                    // must not take back the first one's import.
                    this.setResult(RESULT_OK,
                            new Intent().putExtra(FetchFromICloudActivity.RESULT_IMPORTED, true));
                }
                this.sayWhetherTheAccountIsLinked();
            });

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_icloud_link);
        WindowPaddingUtil.insetForSystemBars(this.binding.getRoot());
        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        this.binding.setHandleClickBack(this::finish);
        // The same screen either way: it links when there is no membership and re-reads when
        // there is one. Only the button's wording changes.
        this.binding.icloudLinkPrimaryButton.setOnClickListener(v ->
                this.fetchFromICloudLauncher.launch(new Intent(this, FetchFromICloudActivity.class)));
        this.binding.icloudLinkUnlinkButton.setOnClickListener(v -> this.onClickUnlinkAccount());

        this.sayWhetherTheAccountIsLinked();
    }

    /**
     * Say whether this app has already joined the account's keychain, and offer what fits.
     *
     * <p>Drawn as unlinked first and corrected when the store answers: reading it is a decryption
     * on a background thread, and a card that changes its mind a moment later is better than one
     * that starts empty. Unlink is hidden rather than disabled while there is nothing to undo.
     */
    private void sayWhetherTheAccountIsLinked() {
        this.showLinked(false);

        this.membershipLookup = this.memberships().get()
                .firstOrError()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        held -> this.showLinked(held.isPresent()),
                        error -> Log.w(TAG, "Could not read whether the account is linked;"
                                + " leaving the page describing the unlinked case", error));
    }

    private void showLinked(final boolean linked) {
        this.binding.icloudLinkStatusIcon.setImageResource(
                linked ? R.drawable.check_circle_24px : R.drawable.link_off_24px);
        this.binding.icloudLinkStatus.setText(
                linked ? R.string.icloud_link_status_linked : R.string.icloud_link_status_unlinked);
        this.binding.icloudLinkStatusDetail.setText(linked
                ? R.string.icloud_link_status_linked_detail
                : R.string.icloud_link_status_unlinked_detail);
        this.binding.icloudLinkPrimaryButton.setText(
                linked ? R.string.icloud_link_check_button : R.string.icloud_link_connect_button);
        this.binding.icloudLinkUnlinkButton.setVisibility(linked ? View.VISIBLE : View.GONE);
    }

    private KeychainMembershipRepository memberships() {
        return new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(this.getApplicationContext()),
                new AppCryptographyUtil());
    }

    /**
     * Unlink, once the user has been told what that does and, more importantly, what it does not.
     *
     * <p><b>Nothing here can leave the account's trust circle</b>, so the peer this app joined as
     * goes on existing. This forgets the keys for it, which is what stops the background read.
     * The entry in the user's Apple device list stays until they remove it there, and the dialog
     * says so. Confirmed rather than immediate because linking again needs the Apple device
     * passcode.
     */
    private void onClickUnlinkAccount() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.icloud_unlink_confirm_title)
                .setMessage(R.string.icloud_unlink_confirm_message)
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(R.string.icloud_unlink_confirm_button,
                        (dialog, which) -> this.unlinkTheAccount())
                .show();
    }

    private void unlinkTheAccount() {
        this.unlinking = this.memberships().forget()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> {
                            Log.i(TAG, "The keychain membership has been forgotten");
                            this.showLinked(false);
                            Toast.makeText(this, R.string.icloud_unlink_done,
                                    Toast.LENGTH_LONG).show();
                        },
                        error -> {
                            // **Left linked, and said so.** Reporting an unlink that did not
                            // happen would leave the background read running against an account
                            // the user believes it has let go of.
                            Log.e(TAG, "Could not forget the keychain membership", error);
                            Toast.makeText(this, R.string.icloud_unlink_failed,
                                    Toast.LENGTH_LONG).show();
                        });
    }

    @Override
    protected void onDestroy() {
        if (this.membershipLookup != null && !this.membershipLookup.isDisposed()) {
            this.membershipLookup.dispose();
        }
        // Disposed, but the write is not cancelled by it: forget() has already been handed off,
        // and dropping the subscription only stops the toast reaching a screen that has gone.
        if (this.unlinking != null && !this.unlinking.isDisposed()) {
            this.unlinking.dispose();
        }
        super.onDestroy();
    }
}
