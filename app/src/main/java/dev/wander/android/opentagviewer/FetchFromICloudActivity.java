package dev.wander.android.opentagviewer;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.text.format.DateFormat;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import dev.wander.android.opentagviewer.anisette.AdiDeviceIdentity;
import dev.wander.android.opentagviewer.anisette.LocalAnisette;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.model.UserAuthData;
import dev.wander.android.opentagviewer.db.repo.BeaconRepository;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.icloud.EscrowPasscode;
import dev.wander.android.opentagviewer.python.icloud.ICloudAccessory;
import dev.wander.android.opentagviewer.python.icloud.ICloudException;
import dev.wander.android.opentagviewer.python.icloud.ICloudFailure;
import dev.wander.android.opentagviewer.ui.login.SignInAgain;
import dev.wander.android.opentagviewer.python.icloud.ICloudFetch;
import dev.wander.android.opentagviewer.python.icloud.ICloudService;
import dev.wander.android.opentagviewer.python.icloud.KeychainMembership;
import dev.wander.android.opentagviewer.python.PythonLock;
import dev.wander.android.opentagviewer.python.icloud.RecoverableDevice;
import dev.wander.android.opentagviewer.ui.CodeChipSpan;
import dev.wander.android.opentagviewer.ui.RecoverableDeviceIcon;
import dev.wander.android.opentagviewer.ui.login.StepTransition;
import dev.wander.android.opentagviewer.ui.login.StepTransition.Direction;
import dev.wander.android.opentagviewer.util.android.PropertiesUtil;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.util.android.WebLink;
import io.reactivex.rxjava3.schedulers.Schedulers;
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

    /**
     * Where the user can see the device list this app is now in.
     *
     * <p>The root, not a deep link into the devices section. Apple has moved this page twice -
     * it was {@code appleid.apple.com} - and a dead deep link on a screen telling somebody to go
     * and look is worse than one extra click.
     */
    private static final String APPLE_ACCOUNT_URL = "https://account.apple.com";

    /** The same place, as it is printed on screen - no scheme, because nobody reads one. */
    private static final String APPLE_ACCOUNT_DOMAIN = "account.apple.com";

    /**
     * The mutually exclusive steps, listed once.
     *
     * <p>So showing one is "show this" rather than every caller remembering to hide the other
     * five - which is the kind of thing that leaves two stacked on top of each other.
     */
    private static final int[] STEPS = {
            R.id.icloud_loading_container,
            R.id.icloud_device_container,
            R.id.icloud_passcode_container,
            R.id.icloud_no_tags_container,
            R.id.icloud_retry_container,
            R.id.icloud_results_container,
            R.id.icloud_registered_container,
    };

    /** Set when the screen should leave and let the caller open the file picker instead. */
    public static final String RESULT_WANTS_FILE_IMPORT = "wantsFileImport";

    private ICloudService icloud;

    private List<RecoverableDevice> devices = List.of();

    private RecoverableDevice chosenDevice;

    /**
     * Whether a passcode is currently being tried against Apple.
     *
     * <p>The one state this screen refuses to be left from - see {@link #onBackWithin}.
     */
    private boolean anUnlockIsInFlight;

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

    private KeychainMembershipRepository membershipRepo;

    /**
     * Whether this app was already a member of the keychain when this screen opened.
     *
     * <p><b>What separates connecting an account from re-reading one.</b> The two run almost the
     * same flow, and only the first registers anything with Apple - so only the first has
     * anything to explain about a device appearing in somebody's list.
     */
    private boolean wasAlreadyLinked;

    /**
     * The one button, at the bottom, relabelled per step.
     *
     * <p>It was four - one at the bottom of whatever content each step happened to have - so it
     * moved up and down the screen as the flow went on. One that never moves is a great deal
     * calmer, and it cannot end up repeating the heading the way the passcode step's did.
     */
    private Button primaryButton;

    /**
     * Back, in the footer, shown only where there is a step to go back to.
     *
     * <p>The system back already did this, but system back is not an affordance: nothing on the
     * passcode step said the device list was still there, so picking the wrong device out of two
     * looked like a decision you could not take back.
     */
    private Button backButton;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.activity_fetch_from_icloud);
        this.beaconRepo = new BeaconRepository(
                OpenTagViewerDatabase.getInstance(this.getApplicationContext()));
        this.membershipRepo = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(this.getApplicationContext()),
                new AppCryptographyUtil());
        // **The root, not the scroll area.** The buttons on this screen - back, and the primary
        // one that says Unlock - sit *outside* icloud_scroll, anchored to the bottom of the
        // activity, so padding the scroll view moved the text and left them exactly where they
        // were: under the navigation bar. That is the screenshot in the bug report.
        WindowPaddingUtil.insetForSystemBars(this.findViewById(R.id.icloud_root));

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        this.primaryButton = this.findViewById(R.id.icloud_primary_button);
        this.backButton = this.findViewById(R.id.icloud_back_button);
        // The same route the system back takes, so the two cannot disagree.
        this.backButton.setOnClickListener(v -> this.onBackWithin());
        this.findViewById(R.id.icloud_no_tags_wiki_link)
                .setOnClickListener(v -> this.openExportGuide());

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
            // **Only an unlock is worth trapping somebody for.** Swallowing every back press
            // while the loading step was up meant that a screen waiting on PythonLock - which a
            // first import holds for minutes, walking key indices for tags with no alignment -
            // could not be left at all. Neither the in-app arrow nor the device's own back
            // button did anything, and the wait looked like a hang.
            //
            // An unlock attempt is different: it is already talking to Apple, and attempts are
            // probably a limited resource on their end, so abandoning one mid-flight risks
            // spending it for nothing. Everything else here - opening the session, listing
            // recovery options, importing - is safe to walk away from, and onDestroy closes the
            // session behind us.
            if (this.anUnlockIsInFlight) {
                Log.d(TAG, "Back pressed during an unlock attempt; ignoring");
                return;
            }

            Log.d(TAG, "Back pressed while waiting; leaving rather than trapping the user");
            this.finish();
            return;
        }

        if (this.isShowing(R.id.icloud_passcode_container) && this.devices.size() > 1) {
            // Only worth going back to when there was a choice. With one device the list is a
            // single button and returning to it is a dead end that looks like a bug.
            this.showDevices(this.devices, Direction.BACK);
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
        // **Not "looking for a device", because at this point that is not what is happening -
        // and for most people it never will be.** Whether a device is needed at all is decided
        // by the membership check further down: an app that has already joined resumes as the
        // member it is and never sees the device list. Saying otherwise up front tells somebody
        // who just linked their account that the app is hunting for hardware to unlock with,
        // when the thing it is actually waiting for is its turn to talk to Python.
        //
        // askForADevice() sets that message, at the point it becomes true.
        this.showWaiting(PythonLock.isBusy()
                ? R.string.icloud_loading_waiting_for_location_check
                : R.string.icloud_loading_opening_account);

        if (this.icloud != null) {
            this.icloud.close();
        }

        this.icloud = AppDependencies.icloud();

        if (this.icloud == null) {
            // Same recovery as a session that has expired, and handled by whoever launched this
            // rather than by showing a dead screen.
            //
            // **Deliberately does not name a cause.** It used to say "no signed-in account",
            // which is only one of the two ways this is null - the other is a session that could
            // not be opened - and when that happened the log confidently blamed the sign-in while
            // the real error sat two lines above it. Whatever failed has already logged why.
            Log.e(TAG, "No usable iCloud session, so the flow cannot start."
                    + " Either nobody is signed in, or opening the session failed - see above.");
            this.finish();
            return;
        }

        // **The passcode is asked for once, ever.** If this app already joined, it reads as the
        // member it is; only a first run, or a membership the account no longer honours, reaches
        // the device list at all.
        var async = this.icloud.open()
                .andThen(this.membershipRepo.get().firstOrError())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::continueWith, this::showFailure);
    }

    private void continueWith(final Optional<KeychainMembership> membership) {
        if (membership.isEmpty()) {
            this.askForADevice();
            return;
        }

        // Held before anything else runs: from here on the flow is identical to a first
        // connection, and afterwards there is no way to tell which one this was.
        this.wasAlreadyLinked = true;

        this.showWaiting(R.string.icloud_loading_importing);

        var async = this.icloud.resume(membership.get().getPeerJson())
                .andThen(this.icloud.fetch())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::importEverything, this::onStoredMembershipFailed);
    }

    /**
     * The stored membership stopped working, so fall back to asking.
     *
     * <p><b>Not an error to show.</b> The peer may simply have been removed from the account -
     * which is how somebody revokes this app - and the way forward is a passcode and a fresh
     * join, which is precisely the flow a first run takes. Anything else is a real failure.
     */
    private void onStoredMembershipFailed(final Throwable error) {
        final ICloudFailure failure = error instanceof ICloudException
                ? ((ICloudException) error).getFailure() : ICloudFailure.UNKNOWN;

        if (failure != ICloudFailure.MEMBERSHIP_UNUSABLE) {
            this.showFailure(error);
            return;
        }

        Log.w(TAG, "The stored membership no longer reads the account; asking again");
        // Forgotten, or every later run retries keys the account has stopped honouring.
        var async = this.membershipRepo.forget()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::askForADevice,
                        forgetFailed -> {
                            Log.e(TAG, "Could not forget the membership", forgetFailed);
                            this.askForADevice();
                        });
    }

    private void askForADevice() {
        this.showWaiting(R.string.icloud_loading_looking_for_devices);

        var async = this.icloud.recoveryOptions()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(devices -> this.showDevices(devices, Direction.FORWARD),
                        this::showFailure);
    }

    private void showDevices(
            final List<RecoverableDevice> recoverable, final Direction direction) {
        this.devices = recoverable;

        final LinearLayout list = this.findViewById(R.id.icloud_device_list);
        list.removeAllViews();

        for (final RecoverableDevice device : recoverable) {
            final View tile = this.getLayoutInflater()
                    .inflate(R.layout.icloud_device_tile, list, false);

            this.fillTile(tile, device);
            tile.setOnClickListener(v -> this.chooseDevice(device));
            list.addView(tile);
        }

        this.showOnly(R.id.icloud_device_container, R.string.icloud_unlock_title,
                direction);
    }

    private void chooseDevice(final RecoverableDevice device) {
        this.chosenDevice = device;
        this.attempt = 1;

        this.fillTile(this.findViewById(R.id.icloud_passcode_device), device);
        ((TextInputEditText) this.findViewById(R.id.icloud_passcode_input)).setText("");
        this.findViewById(R.id.icloud_passcode_error_container).setVisibility(GONE);

        this.updateAttemptCounter();
        this.showOnly(R.id.icloud_passcode_container, R.string.icloud_unlock_title, Direction.FORWARD);
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

        this.showWaiting(R.string.icloud_loading_unlocking);

        // Unlock, join, store, then read - in that order, and the store is not optional.
        //
        // **By the time the join returns, a peer exists on the user's account** whether or not
        // anything after it succeeds, and the keys that came back are the only copy of the means
        // to use it. So the membership is written before the fetch, and a failure to write stops
        // the flow rather than being logged past: carrying on would leave them with a peer this
        // app can neither use nor clean up, and nothing on screen to say so.
        // Held from here until the chain settles, whichever way it goes - this is the one
        // stretch the user is deliberately not allowed to walk out of. See onBackWithin.
        this.anUnlockIsInFlight = true;

        var async = this.icloud.unlock(this.chosenDevice.getSerial(), passcode)
                .andThen(this.icloud.join(EscrowPasscode.generate()))
                .flatMapCompletable(this.membershipRepo::store)
                .andThen(this.icloud.fetch())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> this.anUnlockIsInFlight = false)
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
            this.showOnly(R.id.icloud_retry_container, R.string.icloud_service_unsure_title, Direction.FORWARD);
            ((TextView) this.findViewById(R.id.icloud_retry_body))
                    .setText(R.string.icloud_passcode_rejected);
            return;
        }

        this.findViewById(R.id.icloud_passcode_error_container).setVisibility(VISIBLE);
        this.updateAttemptCounter();
        this.showOnly(R.id.icloud_passcode_container, R.string.icloud_unlock_title, Direction.NONE);
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

        this.setLoadingText(R.string.icloud_loading_importing);

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
            this.showOnly(R.id.icloud_no_tags_container, R.string.icloud_no_tags_title, Direction.FORWARD);
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
        this.showOnly(R.id.icloud_results_container, R.string.icloud_results_title, Direction.FORWARD);
    }

    /**
     * What this app now is on the user's Apple account, and why to leave it alone.
     *
     * <p><b>A replica of the row, not a description of one.</b> Apple synthesises the entry from
     * the claimed model and ignores the name the app sends, so what the user will scroll past is
     * titled {@code MacBookPro} - not "OpenTagViewer". The tile is built from the same
     * {@link AdiDeviceIdentity.Hardware} the app registers under, because a screen that described
     * a different machine than the one on the account would send somebody hunting for a row that
     * is not there, and possibly deleting one that is.
     *
     * <p><b>Shown when a connection registers a device, and not when one is merely re-read.</b>
     * The entries do accumulate - each fresh install adds another, all with this same title and
     * model - so somebody who saw this a year ago has since forgotten which row it was, and a
     * fresh install genuinely needs telling again. Re-reading a linked account adds nothing to
     * the list, so the page would be describing something that did not happen.
     */
    private void showWhatWeRegisteredAs() {
        final AdiDeviceIdentity.Hardware hardware = LocalAnisette.profileToShow(this);

        this.<TextView>findViewById(R.id.icloud_registered_device_name)
                .setText(hardware.deviceListName());

        // The model and OS are transcriptions of what Apple prints - it prints "macOS 13.1"
        // whatever language the account is in - so only the bracketing is localised.
        this.<TextView>findViewById(R.id.icloud_registered_device_model)
                .setText(this.getString(R.string.icloud_registered_model_and_os,
                        hardware.marketingName(),
                        hardware.osName() + " " + hardware.osVersion()));

        this.<TextView>findViewById(R.id.icloud_registered_device_serial).setText(
                TextUtils.expandTemplate(
                        this.getText(R.string.icloud_registered_serial_label),
                        this.serialAsCode()));

        // The icon follows the claim, or the tile says Mac beside a picture of a phone.
        this.<ImageView>findViewById(R.id.icloud_registered_icon).setImageResource(
                hardware == AdiDeviceIdentity.Hardware.IPHONE
                        ? R.drawable.smartphone_24px : R.drawable.laptop_24px);

        // The same chip in the prose. It is the value the sentence is about, and it reads as a
        // typo in body text - "0PENTAGVIEWR" has a zero for an O and no vowel in VIEWR.
        // Read from the resource rather than from the view: the view may already hold an expanded
        // copy from a previous visit to this step, and expanding twice leaves the ^1 gone and the
        // chip applied to nothing.
        this.<TextView>findViewById(R.id.icloud_registered_body).setText(
                TextUtils.expandTemplate(
                        this.getText(R.string.icloud_registered_body), this.serialAsCode()));

        this.fillInTheAccountName();
        this.setUpTheAccountLink();

        this.showOnly(R.id.icloud_registered_container, R.string.icloud_registered_title,
                Direction.FORWARD);
    }

    /** The serial, as a chip, ready to drop into a sentence or a label. */
    private CharSequence serialAsCode() {
        return CodeChipSpan.applyTo(
                AdiDeviceIdentity.APP_SERIAL, AdiDeviceIdentity.APP_SERIAL, this.codeChip());
    }

    private CodeChipSpan codeChip() {
        return new CodeChipSpan(
                this.getResources().getDisplayMetrics().density,
                this.colour(com.google.android.material.R.attr.colorSurfaceContainerHighest),
                this.colour(com.google.android.material.R.attr.colorOnSurface));
    }

    /**
     * "See your devices at <u>account.apple.com</u>", with only the address looking tappable.
     *
     * <p>The address is inserted rather than translated - it is a domain - and it carries the
     * three marks that say "link" without an icon: the accent colour, an underline and a heavier
     * weight. The words around it stay body text, so the line reads as a sentence that contains a
     * link rather than as a button whose label happens to be a sentence.
     */
    private void setUpTheAccountLink() {
        final SpannableString address = new SpannableString(APPLE_ACCOUNT_DOMAIN);
        address.setSpan(new ForegroundColorSpan(this.colour(
                        com.google.android.material.R.attr.colorPrimary)),
                0, address.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        address.setSpan(new UnderlineSpan(),
                0, address.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        address.setSpan(new StyleSpan(Typeface.BOLD),
                0, address.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        final TextView link = this.findViewById(R.id.icloud_registered_link);
        link.setText(TextUtils.expandTemplate(
                this.getText(R.string.icloud_registered_link), address));
        link.setOnClickListener(v -> WebLink.open(this, APPLE_ACCOUNT_URL));
    }

    private int colour(final int attr) {
        final TypedValue value = new TypedValue();
        this.getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    /**
     * The account the entry is on, named if the session carries it.
     *
     * <p><b>Hidden rather than blank when it does not.</b> A stored session need not carry an
     * account block - Settings already handles that case rather than crashing on it - and
     * "%1$s lists this app" with an empty {@code %1$s} reads as a bug. The tile below says the
     * same thing without it; the email only makes it concrete for somebody with more than one
     * Apple account.
     */
    private void fillInTheAccountName() {
        final TextView lead = this.findViewById(R.id.icloud_registered_lead);
        lead.setVisibility(GONE);

        var async = new UserAuthRepository(
                UserAuthDataStore.getInstance(this.getApplicationContext()),
                new AppCryptographyUtil())
                .getUserAuth()
                .filter(Optional::isPresent)
                .map(auth -> auth.get().getUser())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        user -> {
                            // Both halves can be absent on a session that deserialised without an
                            // account block - the same case Settings guards, for the same reason.
                            final UserAuthData.UserAccountInfo info = user.getAccount() == null
                                    ? null : user.getAccount().getInfo();
                            final String email = info == null ? null : info.getAccountName();

                            if (email == null || email.isBlank()) {
                                return;
                            }
                            lead.setText(this.getString(R.string.icloud_registered_lead, email));
                            lead.setVisibility(VISIBLE);
                        },
                        error -> Log.w(TAG, "No account name for the device note", error));
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
                this.showOnly(R.id.icloud_no_tags_container, R.string.icloud_no_tags_title, Direction.FORWARD);
                break;

            case NOT_SIGNED_IN:
                Log.e(TAG, "The account is not usable, so this screen has nothing to do");
                this.finish();
                break;

            case CREDENTIALS_REJECTED:
                // **Not the retry screen, which is where this used to land.** Apple refused
                // the stored password, and a keychain session needs a fresh token rather than the
                // one held - so "worth trying again later" is false and every attempt fails alike.
                // The stored blob goes with it - keeping one that cannot work means the next
                // screen to use it meets the same wall with no idea why.
                Log.w(TAG, "Apple refused the stored credentials; signing out so they can be"
                        + " established again");
                SignInAgain.from(this);
                break;

            case SERVICE_UNSURE:
            default:
                // Everything unrecognised lands here on purpose: "try again later" is the safe
                // thing to say about a failure whose cause is not established, and it is a long
                // way better than telling somebody they own no tags.
                this.showOnly(R.id.icloud_retry_container, R.string.icloud_service_unsure_title, Direction.FORWARD);
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

    /**
     * Open the wiki page describing how to make an export.
     *
     * <p>The same page the device list links to, opened the same way: read from
     * {@code app.properties} rather than written here, so there is one URL to change.
     *
     * <p>Worth having on this screen in particular. The person reading it cannot produce a
     * bundle themselves - somebody else has to - so a link they can forward is the difference
     * between being told what they need and being able to ask for it.
     */
    private void openExportGuide() {
        final var properties = PropertiesUtil.getProperties(this.getAssets(), "app.properties");
        if (properties == null) {
            Log.w(TAG, "Could not read app.properties; no export guide link to open");
            return;
        }

        final String url = properties.getProperty("exportWikiPage");
        if (url == null || url.isBlank()) {
            Log.w(TAG, "No exportWikiPage configured in app.properties");
            return;
        }

        WebLink.open(this, url);
    }

    private void leaveForFileImport() {
        final android.content.Intent data = new android.content.Intent();
        data.putExtra(RESULT_WANTS_FILE_IMPORT, true);
        this.setResult(RESULT_OK, data);
        this.finish();
    }

    /**
     * Show the spinner, saying what is being waited for.
     *
     * <p><b>Its own heading, not the next step's.</b> Every wait used to borrow "Unlock your
     * Apple keychain", so the screen looked like the device list failing to populate rather than
     * like work happening - which is exactly how it read to somebody watching.
     */
    private void showWaiting(final int captionResId) {
        this.showOnly(R.id.icloud_loading_container, R.string.icloud_loading_title,
                Direction.NONE);
        this.setLoadingText(captionResId);
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
    /**
     * The quiet second line of a device tile: what it is, its serial, when the record was made.
     *
     * <p>The model is only worth showing when the user named the device - otherwise the name
     * already <i>is</i> the model class and repeating it says nothing.
     *
     * <p><b>"added", never "last used".</b> This is when the escrow record was created. A record
     * made three months ago says nothing about whether that phone was used this morning, and
     * telling somebody otherwise sends them looking for the wrong device.
     */
    /** Put a device into a tile - the same one whether it is being chosen or already was. */
    private void fillTile(final View tile, final RecoverableDevice device) {
        ((ImageView) tile.findViewById(R.id.icloud_device_icon))
                .setImageResource(RecoverableDeviceIcon.forDevice(device));
        ((TextView) tile.findViewById(R.id.icloud_device_name)).setText(device.displayName());
        ((TextView) tile.findViewById(R.id.icloud_device_badges)).setText(this.badgesFor(device));
    }

    private String badgesFor(final RecoverableDevice device) {
        final List<String> parts = new ArrayList<>();

        if (device.hasUserGivenName() && !device.getModelClass().isBlank()) {
            parts.add(device.getModelClass());
        }
        if (device.getSerial() != null && !device.getSerial().isBlank()) {
            parts.add(device.getSerial());
        }
        if (device.getEscrowedAtMs() > 0) {
            // Medium, so the month is a word. The short format is numeric and ambiguous -
            // "3/12/24" is March in one country and December in another, on a screen whose whole
            // job is helping somebody recognise which of their devices this is.
            parts.add(this.getString(R.string.icloud_device_added_on,
                    DateFormat.getMediumDateFormat(this)
                            .format(new Date(device.getEscrowedAtMs()))));
        }

        return String.join("  ·  ", parts);
    }

    /** Put the one button at the bottom to work for whichever step is showing. */
    private void setPrimaryButton(final int textResId, final Runnable action) {
        this.primaryButton.setVisibility(VISIBLE);
        this.primaryButton.setText(textResId);
        this.primaryButton.setOnClickListener(v -> action.run());
    }

    private void hidePrimaryButton() {
        this.primaryButton.setVisibility(GONE);
        this.primaryButton.setOnClickListener(null);
    }

    private void showOnly(final int stepId, final int titleResId, final Direction direction) {
        View outgoing = null;
        for (final int candidate : STEPS) {
            if (candidate == stepId) {
                continue;
            }
            final View step = this.findViewById(candidate);
            if (outgoing == null && step.getVisibility() == VISIBLE) {
                outgoing = step;
                continue;
            }
            // Already off screen, or a second visible step, which should not happen. Hidden
            // without ceremony either way: only one thing can animate out, and a step left
            // half-faded by an earlier swap would come back that way.
            StepTransition.swap(step, null, Direction.NONE);
        }

        StepTransition.swap(outgoing, this.findViewById(stepId), direction);

        // The heading names the step, so it travels with it. Set first, so nothing has to wait
        // for the animation to read what it says.
        final TextView title = this.findViewById(R.id.icloud_step_title);
        title.setText(titleResId);
        StepTransition.enter(title, direction);

        // Only where there is an earlier step: on the passcode step with a choice of devices.
        // Anywhere else back leaves the screen, and a button that closes the screen is not what
        // somebody expects from an arrow pointing left.
        this.backButton.setVisibility(
                stepId == R.id.icloud_passcode_container && this.devices.size() > 1
                        ? VISIBLE : GONE);

        if (stepId == R.id.icloud_passcode_container) {
            this.setPrimaryButton(R.string.icloud_unlock_action, this::submitPasscode);
        } else if (stepId == R.id.icloud_results_container) {
            // **The device note is for the connection that created the device, and nothing
            // else.** It explains a row that has just appeared in somebody's Apple account, why
            // it is there and why deleting it breaks their session - which is worth a screen
            // once. Re-reading a linked account registers nothing and shows the same page again
            // to somebody who has already read it, and a page that turns up when nothing has
            // happened is one people learn to tap past, including the time it matters.
            if (this.wasAlreadyLinked) {
                this.setPrimaryButton(R.string.icloud_results_done, this::finish);
            } else {
                this.setPrimaryButton(R.string.icloud_results_next, this::showWhatWeRegisteredAs);
            }
        } else if (stepId == R.id.icloud_registered_container) {
            this.setPrimaryButton(R.string.icloud_results_done, this::finish);
        } else if (stepId == R.id.icloud_no_tags_container) {
            this.setPrimaryButton(R.string.icloud_import_from_file, this::leaveForFileImport);
        } else if (stepId == R.id.icloud_retry_container) {
            this.setPrimaryButton(R.string.icloud_try_again, this::start);
        } else {
            // The device list and the spinner have nothing to press: on the list you choose a
            // tile, and during a call there is nothing to do but wait.
            this.hidePrimaryButton();
        }
    }
}
