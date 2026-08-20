package dev.wander.android.opentagviewer;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

import dev.wander.android.opentagviewer.db.repo.BeaconRepository;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.icloud.ICloudAccessory;
import dev.wander.android.opentagviewer.python.icloud.ICloudException;
import dev.wander.android.opentagviewer.python.icloud.ICloudFailure;
import dev.wander.android.opentagviewer.python.icloud.ICloudFetch;
import dev.wander.android.opentagviewer.python.icloud.ICloudService;
import dev.wander.android.opentagviewer.python.icloud.RecoverableDevice;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;

/**
 * Reading the tags on the signed-in Apple account, instead of importing a zip.
 *
 * <p>The flow is four calls with a person answering something between them - open, list what the
 * keychain can be recovered from, unlock with a device passcode, fetch - and this is the screen
 * around them. It talks only to {@link ICloudService}, so every state it has to handle can be
 * produced in a test; the real implementation needs an Apple account in conditions nobody can
 * arrange on demand, and the states worth getting right are precisely the ones a working account
 * will never be in.
 *
 * <p><b>The session is closed in {@code onDestroy}</b>, in a finally-shaped way: two of those
 * calls hold sockets, and an abandoned session leaks them for the life of the process.
 */
public class FetchFromICloudActivity extends AppCompatActivity {
    private static final String TAG = FetchFromICloudActivity.class.getSimpleName();

    /** Set when the screen should leave and let the caller open the file picker instead. */
    public static final String RESULT_WANTS_FILE_IMPORT = "wantsFileImport";

    private ICloudService icloud;

    private List<RecoverableDevice> devices = List.of();

    private RecoverableDevice chosenDevice;

    /**
     * Which attempt the next press will be, starting at 1.
     *
     * <p>Capped at {@link ICloudService#MAX_UNLOCK_ATTEMPTS}, and the cap is respected here
     * rather than in Python because this is where the button is. Attempts are probably a limited
     * resource on Apple's end and what this service allows is not established, so spending them
     * is deliberate.
     */
    private int attempt = 1;

    /** Whether anything was written, so the caller knows to redraw its list. */
    private boolean importedSomething = false;

    private BeaconRepository beaconRepo;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.activity_fetch_from_icloud);
        this.beaconRepo = new BeaconRepository(
                OpenTagViewerDatabase.getInstance(this.getApplicationContext()));
        WindowPaddingUtil.insertUITopPadding(this.findViewById(R.id.icloud_scroll));

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        this.findViewById(R.id.icloud_passcode_submit)
                .setOnClickListener(v -> this.submitPasscode());
        this.findViewById(R.id.icloud_retry_button)
                .setOnClickListener(v -> this.start());
        this.findViewById(R.id.icloud_no_tags_import_button)
                .setOnClickListener(v -> this.leaveForFileImport());
        this.findViewById(R.id.icloud_results_done_button)
                .setOnClickListener(v -> this.finish());

        this.getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackWithin();
            }
        });

        this.start();
    }

    /**
     * Back, meaning "the step before this one" where there is one.
     *
     * <p><b>Not simply closing the screen.</b> From the passcode step the step before it is the
     * device list, and a back press that abandoned the whole errand instead would make choosing
     * the wrong device out of two an expensive mistake - it costs the sign-in, the wait, and
     * finding the button again.
     *
     * <p>Swallowed entirely while a call is in flight. There is nothing to go back to mid-call,
     * and leaving then would strand a keychain unlock that is already talking to Apple.
     */
    private void onBackWithin() {
        if (this.isShowing(R.id.icloud_loading_container)) {
            Log.d(TAG, "Back pressed while a call was in flight; ignoring");
            return;
        }

        if (this.isShowing(R.id.icloud_passcode_container) && this.devices.size() > 1) {
            // Only worth going back to when there was a choice. With one device the list is a
            // single button and returning to it is a dead end that looks like a bug.
            this.showDevices(this.devices);
            return;
        }

        this.finish();
    }

    private boolean isShowing(final int stepId) {
        return this.findViewById(stepId).getVisibility() == VISIBLE;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (this.icloud != null) {
            this.icloud.close();
            this.icloud = null;
        }
    }

    /** Open a session and ask what the keychain can be recovered from. */
    private void start() {
        this.showOnly(R.id.icloud_loading_container, R.string.icloud_unlock_title);
        this.setLoadingText(R.string.icloud_fetch_my_tags);

        if (this.icloud != null) {
            this.icloud.close();
        }

        this.icloud = AppDependencies.icloud();

        if (this.icloud == null) {
            // No usable signed-in account. Same recovery as a session that has expired, and
            // handled by whoever launched this rather than by showing a dead screen.
            Log.e(TAG, "No signed-in account, so the iCloud flow cannot start");
            this.finish();
            return;
        }

        var async = this.icloud.open()
                .andThen(this.icloud.recoveryOptions())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::showDevices, this::showFailure);
    }

    private void showDevices(final List<RecoverableDevice> recoverable) {
        this.devices = recoverable;

        final LinearLayout list = this.findViewById(R.id.icloud_device_list);
        list.removeAllViews();

        for (final RecoverableDevice device : recoverable) {
            final View row = this.getLayoutInflater()
                    .inflate(R.layout.icloud_device_button, list, false);

            final Button button = row.findViewById(R.id.icloud_device_button);
            // FindMy.py's own description, shown as-is. It is the only thing telling two of
            // somebody's iPhones apart, and this app knows nothing about escrow records that
            // the library does not.
            button.setText(device.getDescription());
            button.setOnClickListener(v -> this.chooseDevice(device));

            list.addView(row);
        }

        this.showOnly(R.id.icloud_device_container, R.string.icloud_unlock_title);
    }

    private void chooseDevice(final RecoverableDevice device) {
        this.chosenDevice = device;
        this.attempt = 1;

        ((TextView) this.findViewById(R.id.icloud_passcode_device))
                .setText(device.getDescription());
        ((TextInputEditText) this.findViewById(R.id.icloud_passcode_input)).setText("");
        this.findViewById(R.id.icloud_passcode_error_container).setVisibility(GONE);

        this.updateAttemptCounter();
        this.showOnly(R.id.icloud_passcode_container, R.string.icloud_unlock_title);
    }

    private void updateAttemptCounter() {
        final TextView counter = this.findViewById(R.id.icloud_attempts_text);
        counter.setText(this.getString(
                R.string.icloud_attempt_x_of_y, this.attempt, ICloudService.MAX_UNLOCK_ATTEMPTS));
        // Hidden on the first go: "Attempt 1 of 3" before anything has been tried reads as a
        // warning, and there is nothing to warn about yet.
        counter.setVisibility(this.attempt > 1 ? VISIBLE : GONE);
    }

    private void submitPasscode() {
        final TextInputEditText input = this.findViewById(R.id.icloud_passcode_input);
        final String passcode = input.getText() == null ? "" : input.getText().toString();

        if (passcode.isEmpty()) {
            return;
        }

        this.showOnly(R.id.icloud_loading_container, R.string.icloud_unlock_title);
        this.setLoadingText(R.string.icloud_unlock_title);

        var async = this.icloud.unlock(this.chosenDevice.getSerial(), passcode)
                .andThen(this.icloud.fetch())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::importEverything, this::onUnlockFailed);
    }

    /**
     * A rejected passcode, which is <b>not proof it was wrong</b>.
     *
     * <p>FindMy.py's first advice is to try the same one again, because the exchange has been
     * seen to fail intermittently and then succeed. The copy says that; do not reword it into
     * "incorrect passcode".
     */
    private void onUnlockFailed(final Throwable error) {
        final ICloudFailure failure = error instanceof ICloudException
                ? ((ICloudException) error).getFailure() : ICloudFailure.UNKNOWN;

        if (failure != ICloudFailure.PASSCODE_REJECTED) {
            this.showFailure(error);
            return;
        }

        this.attempt++;

        if (this.attempt > ICloudService.MAX_UNLOCK_ATTEMPTS) {
            Log.w(TAG, "Out of unlock attempts for " + this.chosenDevice.getSerial());
            this.showOnly(R.id.icloud_retry_container, R.string.icloud_service_unsure_title);
            ((TextView) this.findViewById(R.id.icloud_retry_body))
                    .setText(R.string.icloud_passcode_rejected);
            return;
        }

        this.findViewById(R.id.icloud_passcode_error_container).setVisibility(VISIBLE);
        this.updateAttemptCounter();
        this.showOnly(R.id.icloud_passcode_container, R.string.icloud_unlock_title);
    }

    /**
     * Take everything the account holds, write it, and then show what was taken.
     *
     * <p><b>Everything, with nothing to choose.</b> Importing from the account is all of it; the
     * screen that follows is an overview of what arrived, not a picker. Choosing a subset is what
     * exporting is for, and that lives on the device list behind a long press.
     */
    private void importEverything(final ICloudFetch fetched) {
        if (fetched.isEmpty()) {
            this.showResults(fetched);
            return;
        }

        this.setLoadingText(R.string.icloud_fetch_my_tags);

        final List<String> wanted = new ArrayList<>();
        for (final ICloudAccessory accessory : fetched.getAccessories()) {
            wanted.add(accessory.getBeaconId());
        }

        var async = this.icloud.records(wanted)
                .flatMap(this.beaconRepo::refreshAccountBeacons)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(held -> {
                    Log.i(TAG, "Holding " + held.size() + " beacons for the account");
                    this.importedSomething = true;
                    this.showResults(fetched);
                }, this::showFailure);
    }

    private void showResults(final ICloudFetch fetched) {
        if (fetched.isEmpty()) {
            // An account with a Mac on it but no tags. Same advice as having nothing to recover
            // from, reached a step later.
            this.showOnly(R.id.icloud_no_tags_container, R.string.icloud_no_tags_title);
            return;
        }

        ((TextView) this.findViewById(R.id.icloud_results_found)).setText(
                this.getString(R.string.icloud_found_x_tags, fetched.getAccessories().size()));

        final TextView skipped = this.findViewById(R.id.icloud_results_skipped);
        // Named rather than dropped quietly: "fewer tags than expected" and "some of those were
        // never tags" look identical from outside, and the second is the common one.
        skipped.setVisibility(fetched.getSkipped().isEmpty() ? GONE : VISIBLE);
        skipped.setText(this.getString(
                R.string.icloud_x_were_not_tags, fetched.getSkipped().size()));

        final LinearLayout list = this.findViewById(R.id.icloud_results_list);
        list.removeAllViews();

        for (final ICloudAccessory accessory : fetched.getAccessories()) {
            final View row = this.getLayoutInflater()
                    .inflate(R.layout.icloud_found_accessory, list, false);

            ((TextView) row.findViewById(R.id.icloud_found_label)).setText(accessory.getLabel());

            final TextView details = row.findViewById(R.id.icloud_found_details);
            // What an accessory with no name has instead of one: what kind of thing it is, its
            // serial, when it was paired. "unnamed" three times over is not a list to choose
            // from.
            details.setText(accessory.getDetails());
            details.setVisibility(accessory.getDetails().isEmpty() ? GONE : VISIBLE);

            list.addView(row);
        }

        // **Not `icloud_found_x_tags`**, which is a format string with a `%1$d` in it - used as
        // a heading it renders the placeholder literally, which is what shipped in the first
        // screenshot of this screen.
        this.showOnly(R.id.icloud_results_container, R.string.icloud_results_title);
    }

    /** Whatever went wrong, on the screen written for it. */
    private void showFailure(final Throwable error) {
        final ICloudFailure failure = error instanceof ICloudException
                ? ((ICloudException) error).getFailure() : ICloudFailure.UNKNOWN;
        final String detail = error instanceof ICloudException
                ? ((ICloudException) error).getDetail() : String.valueOf(error.getMessage());

        Log.w(TAG, "iCloud flow stopped: " + failure + " - " + detail);

        switch (failure) {
            case NOTHING_TO_RECOVER_FROM:
                // Final. This account has nothing that can ever unlock its keychain, so the
                // import path is the answer rather than a retry.
                this.showOnly(R.id.icloud_no_tags_container, R.string.icloud_no_tags_title);
                break;

            case NOT_SIGNED_IN:
                Log.e(TAG, "The account is not usable, so this screen has nothing to do");
                this.finish();
                break;

            case SERVICE_UNSURE:
            default:
                // Everything unrecognised lands here on purpose: "try again later" is the safe
                // thing to say about a failure whose cause is not established, and it is a long
                // way better than telling somebody they own no tags.
                this.showOnly(R.id.icloud_retry_container, R.string.icloud_service_unsure_title);
                ((TextView) this.findViewById(R.id.icloud_retry_body)).setText(
                        failure == ICloudFailure.SERVICE_UNSURE
                                ? this.getString(R.string.icloud_service_unsure_body)
                                : detail);
                break;
        }
    }

    /** Whether this screen brought anything in, so the device list knows to redraw. */
    public static final String RESULT_IMPORTED = "importedFromAccount";

    @Override
    public void finish() {
        if (this.importedSomething) {
            final android.content.Intent data = new android.content.Intent();
            data.putExtra(RESULT_IMPORTED, true);
            this.setResult(RESULT_OK, data);
        }
        super.finish();
    }

    private void leaveForFileImport() {
        final android.content.Intent data = new android.content.Intent();
        data.putExtra(RESULT_WANTS_FILE_IMPORT, true);
        this.setResult(RESULT_OK, data);
        this.finish();
    }

    private void setLoadingText(final int stringResId) {
        ((TextView) this.findViewById(R.id.icloud_loading_text)).setText(stringResId);
    }

    /**
     * Show one step and hide the rest.
     *
     * <p>Listed once so showing a step is "show this one" rather than every caller remembering
     * to hide each of the others - the mistake that leaves two steps stacked on each other.
     */
    private void showOnly(final int stepId, final int titleResId) {
        for (final int candidate : new int[] {
                R.id.icloud_loading_container,
                R.id.icloud_device_container,
                R.id.icloud_passcode_container,
                R.id.icloud_no_tags_container,
                R.id.icloud_retry_container,
                R.id.icloud_results_container,
        }) {
            this.findViewById(candidate).setVisibility(candidate == stepId ? VISIBLE : GONE);
        }

        ((TextView) this.findViewById(R.id.icloud_step_title)).setText(titleResId);
    }
}
