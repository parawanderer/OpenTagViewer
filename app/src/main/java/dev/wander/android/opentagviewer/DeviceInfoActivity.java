package dev.wander.android.opentagviewer;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.View.inflate;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;

import static dev.wander.android.opentagviewer.util.android.TextChangedWatcherFactory.justWatchOnChanged;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.util.Pair;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.databinding.DataBindingUtil;
import androidx.emoji2.emojipicker.EmojiPickerView;
import androidx.emoji2.emojipicker.EmojiViewItem;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.ble.BlePermissions;
import dev.wander.android.opentagviewer.ble.BleSoundTriggerPhase;
import dev.wander.android.opentagviewer.ble.BleSoundTriggerResult;
import dev.wander.android.opentagviewer.ble.BleSoundTriggerUpdate;
import dev.wander.android.opentagviewer.ble.NearbyTagLabel;
import dev.wander.android.opentagviewer.ble.NearbyTagSighting;
import dev.wander.android.opentagviewer.ble.NearbyTagSightings;
import dev.wander.android.opentagviewer.ble.NearbyTagWatcher;
import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.data.model.UserMapCameraPosition;
import dev.wander.android.opentagviewer.databinding.ActivityDeviceInfoBinding;
import dev.wander.android.opentagviewer.db.datastore.UserCacheDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.BeaconRepository;
import dev.wander.android.opentagviewer.db.repo.UserDataRepository;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.BeaconData;
import dev.wander.android.opentagviewer.db.repo.model.LastSightingData;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.UserBeaconOptions;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.util.android.CachedPhoneLocation;
import dev.wander.android.opentagviewer.util.android.FusedPhoneLocation;
import dev.wander.android.opentagviewer.util.android.PropertiesUtil;
import dev.wander.android.opentagviewer.util.android.WebLink;
import dev.wander.android.opentagviewer.util.parse.BatteryLevelDescription;
import dev.wander.android.opentagviewer.util.parse.BeaconDataParser;
import dev.wander.android.opentagviewer.util.parse.LocationReportFields;
import dev.wander.android.opentagviewer.util.rx.WideScanBackoff;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.ui.BeaconIcon;
import dev.wander.android.opentagviewer.python.HardwareDescriber;
import dev.wander.android.opentagviewer.python.icloud.AccessoryRenamer;
import dev.wander.android.opentagviewer.python.icloud.ICloudFailures;
import dev.wander.android.opentagviewer.ui.login.SignInAgain;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.annotations.NonNull;

public class DeviceInfoActivity extends AppCompatActivity
        implements ActivityCompat.OnRequestPermissionsResultCallback {
    private static final String TAG = DeviceInfoActivity.class.getSimpleName();

    private static final int PERMISSION_REQUEST_PLAY_SOUND_NEARBY = 1001;

    /**
     * How often the "Last seen" row is redrawn while the tag is quiet.
     *
     * <p>Matched to the coarsest unit {@code DateUtils} is asked for here, a minute: redrawing
     * faster changes nothing on screen, and redrawing slower would leave the row a minute behind
     * for someone watching it.
     */
    private static final long LAST_SEEN_REFRESH_MS = TimeUnit.MINUTES.toMillis(1);

    private static final double DEFAULT_LONGITUDE = 0d;
    private static final double DEFAULT_LATITUDE = 0d;
    private static final float DEFAULT_ZOOM = 16.0f;

    private String beaconId;
    private UserSettingsRepository userSettingsRepo;
    private UserDataRepository userDataRepository;
    private BeaconRepository beaconRepo;
    private BeaconData beaconData;
    private BeaconInformation beaconInformation;
    /** Null for a tag read from the Apple account - nothing was ever exported or imported. */
    private @Nullable Import importData;
    private UserSettings userSettings;
    private EmojiPickerView emojiPickerView;
    private Button currentIconButton;
    private ActivityDeviceInfoBinding binding;

    /**
     * The in-flight call to the shared heuristic, so it can be cancelled.
     *
     * <p>It hops back to the main thread to set a label. If the screen is gone by then, that is
     * an update to a binding whose views are detached - held here so {@link #onDestroy()} can
     * stop it rather than letting it land wherever it lands.
     */
    private Disposable hardwareLookup;

    /**
     * Whether this is one of the owner's own devices, once the shared heuristic has said.
     *
     * <p><b>Null until it answers, and null is not false.</b> Renaming writes to the account only
     * when this is definitely false, so an unanswered question leaves the screen on the cautious
     * road - a local nickname, which changes nothing anybody else can see.
     */
    private Boolean isOwnDevice;

    /** The in-flight write to the account, so leaving the screen does not land on dead views. */
    private Disposable accountRename;

    /** The in-flight BLE scan/GATT trigger, so leaving the screen stops it rather than
     * leaving a scan running or a result landing on dead views. */
    private Disposable playSoundNearby;

    /** Reused so each new status (searching/connecting/sending/result) replaces the last one
     * on screen instead of queuing behind it - see {@link #showPlaySoundStatus}. */
    private Toast playSoundStatusToast;

    /**
     * Listens for this one tag while the screen is open, to show a battery reading taken off the
     * tag itself rather than out of the iCloud record.
     *
     * <p><b>Its own scan rather than one handed over from the map.</b> Opening this screen
     * pauses {@code MapsActivity}, which stops that scan, so a sighting passed across would be
     * stale on arrival - and this screen can also be reached without the map having run at all.
     */
    private Disposable nearbyWatchDisposable;

    /**
     * Hides the live battery row once its last sighting is too old to stand behind - reset by
     * every new sighting, so the row only ages out when the tag has genuinely gone quiet.
     *
     * <p>Without this the row never aged at all: once a tag had been heard, "read from the tag
     * just now" stayed on screen for hours after the tag left earshot - exactly the staleness
     * the row exists to be free of. Same clock as the map card's badge:
     * {@link NearbyTagSightings#FRESH_FOR_MS}.
     */
    private Disposable liveBatteryExpiry;

    /** The pending retry after the scan died mid-session - see {@link #onNearbyWatchEnded}. */
    private Disposable nearbyWatchRetryDisposable;

    /** The in-flight read of the stored sighting - see {@link #showWhatWasHeardOverBluetooth}. */
    private Disposable lastSightingLookup;

    /**
     * Redraws the "Last seen" row while the tag is quiet, so its age keeps up with the clock.
     *
     * <p>Only runs in that state. While the tag is audible the row reads "just now" and every
     * advertisement rewrites it anyway; once there is nothing stored the row is not on screen.
     */
    private Disposable lastSeenTicker;

    /** The one place a Bluetooth sighting is persisted - see {@link AccessorySightingPersister}. */
    private AccessorySightingPersister sightingPersister;

    private boolean hasNameChanges = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        var format = DateFormat.getBestDateTimePattern(Locale.getDefault(), "hh:mm:ss, dd MMM yyyy");
        var timestampFormat = new SimpleDateFormat(format, Locale.getDefault());

        this.beaconId = getIntent().getStringExtra("beaconId");
        Log.d(TAG, "Showing device info view for beaconId=" + this.beaconId);

        this.userSettingsRepo = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.getApplicationContext()));

        this.userDataRepository = new UserDataRepository(
                UserCacheDataStore.getInstance(getApplicationContext())
        );

        this.userSettings = this.userSettingsRepo.getUserSettings();

        this.beaconRepo = new BeaconRepository(
                OpenTagViewerDatabase.getInstance(getApplicationContext()));
        this.sightingPersister = new AccessorySightingPersister(this.beaconRepo);

        this.beaconData = this.beaconRepo.getById(this.beaconId).blockingFirst();
        this.beaconInformation = BeaconDataParser.parse(List.of(this.beaconData)).get(0);

        // **Null for a tag read from the Apple account**, which was never exported and never
        // imported - there is no bundle behind it and so no `Import` row. Fetching one anyway
        // unboxes a null `importId` and crashes this screen in onCreate, which is what tapping
        // an account tag used to do.
        final Long importId = this.beaconData.getOwnedBeaconInfo().importId;
        this.importData = importId == null
                ? null
                : this.beaconRepo.getImportById(importId).blockingFirst().orElse(null);

        binding = DataBindingUtil.setContentView(this, R.layout.activity_device_info);
        WindowPaddingUtil.insertUITopPadding(binding.getRoot());

        binding.setHandleClickBack(this::handleEndActivity);
        binding.setHandleClickMenu(this::handleClickMenu);
        binding.setClickItemHandler(() -> Log.d(TAG, "Some device info item was clicked"));

        binding.setPageTitle(this.getDeviceNameForTitle());
        binding.setDeviceName(this.beaconInformation.getName());
        binding.setOnClickDeviceName(this::handleEditDeviceName);
        binding.setOnClickDeviceEmoji(this::handleEditDeviceEmoji);

        // **Two independent questions, deliberately not one.** "Exported by", "Exported at" and
        // "Imported at" all read from an `Import` row, so they are shown when there is one. The
        // source row says the tag was read from the account, so it is shown when it was. Folding
        // them into one branch reads fine and is wrong: a file-imported row whose import record
        // has gone would then be labelled as coming from the Apple account, which is a claim
        // about where somebody's data came from, made on the strength of a missing join.
        if (this.importData == null) {
            findViewById(R.id.device_settings_exported_by).setVisibility(View.GONE);
            findViewById(R.id.device_settings_exported_with).setVisibility(View.GONE);
            findViewById(R.id.device_settings_exported_at).setVisibility(View.GONE);
            findViewById(R.id.device_settings_imported_at).setVisibility(View.GONE);
        } else {
            binding.setExportedAt(timestampFormat.format(new Date(this.importData.exportedAt)));
            binding.setImportedAt(timestampFormat.format(new Date(this.importData.importedAt)));
            binding.setExportedBy(this.importData.sourceUser);

            // **Per tag, which the Information screen cannot be.** That one lists every producer
            // on the install; this one says which produced *this* tag, and when a report is about
            // one tag misbehaving that is the fact that decides what to expect of it. The row
            // this sits next to already read from the same `Import`, so the value was here and
            // simply unused.
            //
            // Said rather than hidden when there is no value: an export predating `via:` is
            // itself information - it dates the bundle - and a missing row would read as this
            // screen having a gap.
            binding.setExportedWith(
                    this.importData.exportedVia == null || this.importData.exportedVia.isBlank()
                            ? this.getString(R.string.exported_with_something_unrecorded)
                            : this.importData.exportedVia);
        }

        if (this.beaconInformation.isFromAccount()) {
            binding.setSource(this.getString(R.string.source_your_apple_account));
        } else {
            findViewById(R.id.device_settings_source).setVisibility(View.GONE);
        }

        this.describeHowItIsBeingLookedFor(timestampFormat);
        this.showTheOriginalNameOnlyWhereItMeansSomething();

        // What is known without asking anything, drawn immediately. The shared heuristic can
        // improve on it, but it costs a Python interpreter, so this screen must be readable
        // before that answers rather than flashing "Unknown" and correcting itself.
        binding.setDeviceType(this.knownDeviceType());
        this.describeHardwareInTheBackground();

        // debug info
        binding.setDeviceNameOriginal(this.beaconInformation.getOriginalName());
        binding.setDeviceEmojiOriginal(this.beaconInformation.getOriginalEmoji());
        binding.setBeaconId(this.beaconInformation.getBeaconId());
        binding.setNamingRecordId(this.beaconInformation.getNamingRecordId());

        binding.setNamingRecordCreationTime(
                Optional.ofNullable(this.beaconInformation.getNamingRecordCreationTime())
                         .map(d -> timestampFormat.format(new Date(d)))
                        .orElse("?"));
        binding.setNamingRecordModificationTime(
                Optional.ofNullable(this.beaconInformation.getNamingRecordModifiedTime())
                        .map(d -> timestampFormat.format(new Date(d)))
                        .orElse("?"));
        binding.setNamingRecordModifiedBy(
                Optional.ofNullable(this.beaconInformation.getNamingRecordModifiedByDevice())
                        .orElse("?"));

        // The number with its meaning beside it. The number stays first because that is what a
        // bug report should quote and what every other source discusses - see
        // BatteryLevelDescription for how much the labels are worth, and why nothing outside
        // this debug panel reads any of it.
        // **With the caveat attached, not left to the reader.** Apple's own devices are what
        // update this field as they pass the accessory, so a tag imported from a zip carries
        // whatever was true when the export was made and never changes it - possibly years ago.
        // A number with no note beside it reads as current, and "Full" on a tag that has been
        // flat since last spring is worse than showing nothing.
        //
        // Said for every tag rather than only for imported ones: somebody reading an account
        // tag's row learns the rule at the moment it is relevant, which is what makes the
        // imported case legible when they meet it.
        binding.setBatteryLevel(BatteryLevelDescription.describe(
                        this, this.beaconInformation.getBatteryLevel())
                + "\n" + this.getString(R.string.battery_level_icloud_only));
        binding.setDeviceModel(this.beaconInformation.getModel());
        binding.setPairingDate(this.beaconInformation.getPairingDate());
        binding.setProductId(this.beaconInformation.getProductId() + "");
        binding.setSystemVersion(this.beaconInformation.getSystemVersion());
        binding.setVendorId(this.beaconInformation.getVendorId() + "");

        LinearLayout debugData = this.findViewById(R.id.device_debug_info);
        if (this.userSettings.getEnableDebugData() == Boolean.TRUE) {
            debugData.setVisibility(VISIBLE);
        } else {
            debugData.setVisibility(GONE);
        }

        currentIconButton = this.findViewById(R.id.pick_icon_button);
        this.visualiseDeviceEmoji();

        var longClickToClipboardFields = List.of(
                // always visible info:
                R.id.device_settings_exported_by,
                R.id.device_settings_exported_at,
                R.id.device_settings_imported_at,
                R.id.device_settings_device_type,
                // debug info:
                R.id.settings_debug_device_name_original,
                R.id.settings_debug_device_emoji_original,
                R.id.settings_debug_beacon_id,
                R.id.settings_debug_naming_record_id,
                R.id.settings_debug_naming_record_create_time,
                R.id.settings_debug_naming_record_modify_time,
                R.id.settings_debug_naming_record_modified_by,
                R.id.settings_debug_naming_record_battery_level,
                R.id.settings_debug_naming_record_device_model,
                R.id.settings_debug_naming_record_pairing_date,
                R.id.settings_debug_naming_record_product_id,
                R.id.settings_debug_naming_record_system_version,
                R.id.settings_debug_naming_record_vendor_id
        );

        ClipboardManager clipboard = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);

        longClickToClipboardFields.forEach(id -> {
            View container = this.findViewById(id);
            TextView title = container.findViewById(R.id.settings_clickable_item_title);
            TextView content = container.findViewById(R.id.settings_clickable_item_content);

            container.setOnLongClickListener(v -> {
                Log.d(TAG, "Long clicked element: " + title.getText());

                final String fieldTitle = title.getText().toString();
                final String fieldContent = content.getText().toString();

                ClipData clip = ClipData.newPlainText(fieldTitle, fieldContent);
                clipboard.setPrimaryClip(clip);
                return true;
            });
        });

        this.emojiPickerView = this.findViewById(R.id.emoji_picker);
        emojiPickerView.setOnEmojiPickedListener(this::handleEmojiIsPicked);

        ConstraintLayout emojiPicker = this.findViewById(R.id.emoji_picker_layout);
        emojiPicker.setOnClickListener((view) -> this.hideEmojiMenu());

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        this.getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleEndActivity();
            }
        });
    }

    private void handleEndActivity() {
        if (this.hasNameChanges) {
            Intent data = new Intent();
            data.putExtra("deviceWasChanged", this.beaconId);
            setResult(RESULT_OK, data);
        }

        this.finish();
    }

    private void visualiseDeviceEmoji() {
        if (this.beaconInformation.isEmojiFilled()) {
            currentIconButton.setText(this.beaconInformation.getEmoji());
            ((MaterialButton)currentIconButton).setIcon(null);
        } else {
            currentIconButton.setText(null);
            BeaconIcon.applyTo((MaterialButton) currentIconButton, this.beaconInformation);
        }
    }

    private void handleEditDeviceName() {
        View view = inflate(this, R.layout.edit_device_name_dialog, null);
        TextInputEditText textInput = view.findViewById(R.id.device_name_input);
        textInput.setText(this.beaconInformation.getName());

        var builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.device_name)
                .setIcon(R.drawable.edit_24px)
                .setView(view)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    Log.d(TAG, "Clicked to confirm device name change to: " + textInput.getText());
                    this.saveUpdatedDeviceName(Objects.requireNonNull(textInput.getText()).toString());
                }).setNegativeButton(R.string.cancel, null);

        var dialog = builder.show();

        Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);

        textInput.addTextChangedListener(justWatchOnChanged((s, start, before, count) -> {
            // disable positive button when input is empty
            final String userNameInput = s.toString();
            positiveButton.setEnabled(!userNameInput.isBlank());
        }));
    }

    /**
     * Whether renaming this tag changes the account or only this app.
     *
     * <p><b>An accessory read from iCloud keeps its name in one place</b> - the naming record -
     * so renaming it there is the whole rename, and it shows up in Find My on the owner's own
     * devices. Everything else gets a nickname: one of the owner's own devices takes its name
     * from several places and writing this record would leave Find My disagreeing with the
     * device, and a tag imported from a file was never on this account to begin with.
     *
     * <p><b>Both halves have to be true, and neither is guessed.</b> {@code isOwnDevice} is
     * answered by the shared heuristic across the bridge, and is null until it does answer -
     * which is treated as "not an accessory", because the cautious mistake is a local nickname
     * and the other one writes to somebody's account.
     */
    private boolean renamingWritesToTheAccount() {
        return this.beaconInformation.isFromAccount() && Boolean.FALSE.equals(this.isOwnDevice);
    }

    private void saveUpdatedDeviceName(final String newDeviceName) {
        final String oldDeviceName = this.beaconInformation.getName();

        if (oldDeviceName.equals(newDeviceName)) return; // nothing to do, no change

        if (this.renamingWritesToTheAccount()) {
            this.writeToTheAccount(newDeviceName, "", () -> {
                this.binding.setDeviceName(this.beaconInformation.getName());
                this.binding.setPageTitle(this.getDeviceNameForTitle());
            });
            return;
        }

        this.beaconInformation.setUserOverrideName(newDeviceName);
        // save changes...
        // Built rather than constructed positionally, per the entity convention - and so that
        // uiOrder is left unset. Unset means "not supplied", and the repository fills it in
        // from what is stored, which is what stops a rename from unarranging the tag.
        var async = this.beaconRepo.storeUserBeaconOptions(UserBeaconOptions.builder()
                .beaconId(this.beaconId)
                .lastUpdate(System.currentTimeMillis())
                .uiName(this.beaconInformation.getUserOverrideName())
                .uiEmoji(this.beaconInformation.getUserOverrideEmoji())
                .build()
        ).observeOn(AndroidSchedulers.mainThread())
        .subscribe(() -> {
                Log.d(TAG, "Successfully updated UI-facing device name for beaconId=" + this.beaconId);
                this.hasNameChanges = true;
                this.binding.setDeviceName(this.beaconInformation.getName());
                binding.setPageTitle(this.getDeviceNameForTitle());
            },
            error -> Log.e(TAG, "Error occurred while trying to update user-facing device name for beaconId=" + this.beaconId, error));
    }

    /**
     * Write the change to iCloud, then to the stored record, then redraw.
     *
     * <p><b>In that order, and nothing local happens first.</b> A rename that failed on the
     * network and still changed the screen would be the app telling the user something about
     * their account that is not true - so a failure says so and leaves everything exactly as it
     * was, rather than quietly demoting itself to a nickname.
     *
     * <p>The stored naming record is edited rather than covered with a nickname, because a
     * nickname wins at display time forever: the next rename made on the owner's iPhone would
     * arrive and be hidden behind it.
     *
     * @param name    the new name, or empty when only the emoji is changing.
     * @param emoji   the new emoji, or empty when only the name is changing.
     * @param redraw  what to run on the main thread once the change is real.
     */
    private void writeToTheAccount(final String name, final String emoji, final Runnable redraw) {
        this.showRenameInProgress(true);

        final AccessoryRenamer renamer = new AccessoryRenamer(new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(this.getApplicationContext()),
                new AppCryptographyUtil()));

        this.accountRename = renamer
                .rename(this.beaconId, this.beaconInformation.getOwnedBeaconPlistRaw(),
                        name, emoji)
                .andThen(this.beaconRepo.renameStoredAccessory(
                        this.beaconId,
                        name.isEmpty() ? null : name,
                        emoji.isEmpty() ? null : emoji))
                .andThen(this.beaconRepo.getById(this.beaconId).firstOrError())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        reread -> {
                            this.beaconData = reread;
                            // Rebuilt from the record that was just written, so what is on screen
                            // is what the account holds - not a value this screen remembered
                            // sending.
                            this.beaconInformation = BeaconDataParser.parse(List.of(reread)).get(0);
                            this.hasNameChanges = true;
                            this.showRenameInProgress(false);
                            redraw.run();
                        },
                        error -> {
                            if (ICloudFailures.meansSignInAgain(error)) {
                                // **The same wall as the iCloud list and the background read.**
                                // A rename writes to the account, so a refused password stops it
                                // for the same reason - and "renaming failed" would send somebody
                                // to try a different name for a session that cannot write at all.
                                Log.w(TAG, "Apple refused the stored credentials during a rename;"
                                        + " asking for a fresh sign-in", error);
                                SignInAgain.from(this);
                                return;
                            }

                            Log.e(TAG, "Could not rename " + this.beaconId + " in iCloud", error);
                            this.showRenameInProgress(false);
                            this.sayTheRenameDidNotHappen();
                        });
    }

    private void showRenameInProgress(final boolean busy) {
        this.findViewById(R.id.device_rename_progress).setVisibility(busy ? VISIBLE : GONE);
    }

    private void sayTheRenameDidNotHappen() {
        new MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
                .setTitle(R.string.rename_failed_title)
                .setIcon(R.drawable.warning_24px)
                .setMessage(R.string.rename_failed_message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void handleEditDeviceEmoji() {
        ConstraintLayout emojiPicker = this.findViewById(R.id.emoji_picker_layout);
        emojiPicker.setVisibility(VISIBLE);
        emojiPicker.setClickable(false); // temp

        FrameLayout inner = emojiPicker.findViewById(R.id.emoji_picker_container);

        float pixels = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                480f,
                this.getResources().getDisplayMetrics()
        );

        inner.animate()
                .translationY(-pixels)
                .withEndAction(() -> {
                    Log.d(TAG, "Emoji menu was shown!");
                    emojiPicker.setClickable(true); // undo
                })
                .start();
    }

    private void handleEmojiIsPicked(EmojiViewItem emojiViewItem) {
        final String newEmoji = emojiViewItem.getEmoji();
        Log.d(TAG, "New emoji was picked: " + newEmoji);
        this.hideEmojiMenu();

        final String oldEmoji = this.beaconInformation.getEmoji();
        if (oldEmoji != null && oldEmoji.equals(newEmoji)) return; // nothing to do, no change

        if (this.renamingWritesToTheAccount()) {
            this.writeToTheAccount("", newEmoji, () -> {
                this.visualiseDeviceEmoji();
                this.binding.setPageTitle(this.getDeviceNameForTitle());
            });
            return;
        }

        this.beaconInformation.setUserOverrideEmoji(newEmoji);

        // save changes
        // Built rather than constructed positionally, per the entity convention - and so that
        // uiOrder is left unset. Unset means "not supplied", and the repository fills it in
        // from what is stored, which is what stops a rename from unarranging the tag.
        var async = this.beaconRepo.storeUserBeaconOptions(UserBeaconOptions.builder()
                .beaconId(this.beaconId)
                .lastUpdate(System.currentTimeMillis())
                .uiName(this.beaconInformation.getUserOverrideName())
                .uiEmoji(this.beaconInformation.getUserOverrideEmoji())
                .build()
        ).observeOn(AndroidSchedulers.mainThread())
        .subscribe(() -> {
                    Log.d(TAG, "Successfully updated UI-facing device emoji for beaconId=" + this.beaconId);
                    this.hasNameChanges = true;
                    this.visualiseDeviceEmoji();
                    binding.setPageTitle(this.getDeviceNameForTitle());
                },
                error -> Log.e(TAG, "Error occurred while trying to update user-facing device emoji for beaconId=" + this.beaconId, error));
    }

    private void hideEmojiMenu() {
        final ConstraintLayout emojiPicker = this.findViewById(R.id.emoji_picker_layout);
        final FrameLayout inner = emojiPicker.findViewById(R.id.emoji_picker_container);
        emojiPicker.setClickable(false); // temp

        float pixels = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                480f,
                this.getResources().getDisplayMetrics()
        );

        inner.animate()
                .translationY(pixels)
                .withEndAction(() -> {
                    Log.d(TAG, "Emoji menu was hidden. Now hiding outer container for it.");
                    emojiPicker.setClickable(false); // undo
                    emojiPicker.setVisibility(GONE);
                })
                .start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.startWatchingForThisTag();
        this.showWhatWasHeardOverBluetooth();
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.stopWatchingForThisTag();
    }

    /**
     * Listen for this tag while the screen is open, to fill in the live battery row.
     *
     * <p>Silent when it cannot run - no permission, Bluetooth off, or an accessory JSON that has
     * not been backfilled all simply leave the row hidden, which is what it looks like when the
     * tag is out of earshot anyway.
     */
    private void startWatchingForThisTag() {
        this.stopWatchingForThisTag();

        final String accessoryJson = this.beaconData.getOwnedBeaconInfo().accessoryJson;
        if (accessoryJson == null || accessoryJson.isEmpty()) {
            return;
        }

        this.nearbyWatchDisposable = new NearbyTagWatcher(
                AppDependencies.accessoryMacResolver(), this.sightingPersister::onSighting)
                .watch(this.getApplicationContext(), Map.of(this.beaconId, accessoryJson))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::showLiveSighting,
                        error -> Log.w(TAG, "Nearby watch ended for beaconId=" + this.beaconId, error),
                        this::onNearbyWatchEnded);
    }

    /**
     * The scan died mid-session (Bluetooth off, or the platform refused the scan) rather than
     * being stopped - disposal skips onComplete. Retried every 30 seconds so the live battery
     * row comes back on its own when the radio does; see {@code MapsActivity}'s twin for the
     * budget reasoning.
     */
    private void onNearbyWatchEnded() {
        Log.i(TAG, "Nearby watch ended mid-session for beaconId=" + this.beaconId
                + "; retrying in 30s");
        this.nearbyWatchRetryDisposable = Observable
                .timer(30, TimeUnit.SECONDS, AndroidSchedulers.mainThread())
                .subscribe(tick -> this.startWatchingForThisTag());
    }

    private void stopWatchingForThisTag() {
        if (this.nearbyWatchDisposable != null && !this.nearbyWatchDisposable.isDisposed()) {
            this.nearbyWatchDisposable.dispose();
        }
        this.nearbyWatchDisposable = null;
        if (this.liveBatteryExpiry != null && !this.liveBatteryExpiry.isDisposed()) {
            this.liveBatteryExpiry.dispose();
        }
        this.liveBatteryExpiry = null;
        if (this.lastSightingLookup != null && !this.lastSightingLookup.isDisposed()) {
            this.lastSightingLookup.dispose();
        }
        this.lastSightingLookup = null;
        if (this.lastSeenTicker != null && !this.lastSeenTicker.isDisposed()) {
            this.lastSeenTicker.dispose();
        }
        this.lastSeenTicker = null;
        if (this.nearbyWatchRetryDisposable != null
                && !this.nearbyWatchRetryDisposable.isDisposed()) {
            this.nearbyWatchRetryDisposable.dispose();
        }
        this.nearbyWatchRetryDisposable = null;
    }

    /**
     * Shows what the tag is saying right now: heard just now, this strong, this much battery.
     *
     * <p>Never falls back to the iCloud value for the battery row, and never merges with it. The
     * whole reason this section is outside the record's own fields is that it says where its
     * numbers came from; quietly filling one of them from the other source would defeat that.
     * The debug row keeps the record's value, with its caveat.
     */
    private void showLiveSighting(final NearbyTagSighting sighting) {
        // A stored reading on its way back from the database would land on top of this one and
        // relabel a tag we can hear right now as last heard some minutes ago. The live value
        // always wins, so the read that was going to contradict it is dropped rather than raced.
        if (this.lastSightingLookup != null && !this.lastSightingLookup.isDisposed()) {
            this.lastSightingLookup.dispose();
        }
        if (this.lastSeenTicker != null && !this.lastSeenTicker.isDisposed()) {
            this.lastSeenTicker.dispose();
        }

        this.binding.setBleLastSeen(this.getString(R.string.seen_just_now));
        this.binding.setBleSignalStrength(NearbyTagLabel.signalStrengthBars(sighting.getRssi()));
        this.binding.setBleBatteryLevel(
                this.getString(NearbyTagLabel.shortBatteryLabel(sighting.getBatteryLevel())));
        this.showStatusByteForDebugging(sighting.getStatusByte());

        this.showBluetoothSection(true);

        // Every sighting restarts the expiry, so the section only stops claiming to be current
        // once the tag has been quiet for the whole window - see the field doc.
        if (this.liveBatteryExpiry != null && !this.liveBatteryExpiry.isDisposed()) {
            this.liveBatteryExpiry.dispose();
        }
        this.liveBatteryExpiry = Observable
                .timer(NearbyTagSightings.FRESH_FOR_MS, TimeUnit.MILLISECONDS,
                        AndroidSchedulers.mainThread())
                .subscribe(tick -> this.showWhatWasHeardOverBluetooth());
    }

    /**
     * Falls back to what the tag last said, with its age, once it has gone quiet.
     *
     * <p><b>What the section says when the tag is out of earshot.</b> The live reading expires
     * because a tag carried away stops being here - see {@link NearbyTagSightings} - but the
     * battery it reported on the way out is still the best answer anybody has, and for a user
     * with no Apple device it is the only one: the record's own field is updated by Apple's
     * devices and stays at "not yet reported" forever otherwise. So the claim is weakened rather
     * than withdrawn, from "this is the level" to "this is the level when it was last heard".
     *
     * <p><b>The signal row goes, the battery row stays.</b> That split is the point of splitting
     * them. A battery level from an hour ago is still roughly the battery level; a signal
     * strength from an hour ago describes a distance to a tag that is no longer there, and there
     * is no wording that makes it useful. So it is withdrawn rather than dated.
     *
     * <p>The age on "Last seen" is not decoration either. Without it this is the same trap as the
     * debug panel's iCloud value: a battery word with no date reads as current, and "full" from a
     * tag last heard in March is worse than an empty row.
     *
     * <p>Hides the whole section when there is nothing stored, which is a tag this phone has
     * never heard - a new install, or one whose tags have only ever been seen over the network.
     */
    private void showWhatWasHeardOverBluetooth() {
        if (this.lastSightingLookup != null && !this.lastSightingLookup.isDisposed()) {
            this.lastSightingLookup.dispose();
        }

        this.lastSightingLookup = this.beaconRepo.getLastSighting(this.beaconId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(stored -> {
                    if (stored.isEmpty()) {
                        this.showBluetoothSection(false);
                        return;
                    }

                    final LastSightingData sighting = stored.get();
                    this.binding.setBleBatteryLevel(this.getString(
                            NearbyTagLabel.shortBatteryLabel(sighting.getBatteryLevel())));
                    this.showStatusByteForDebugging(sighting.getStatusByte());
                    this.showAgeOfLastSighting(sighting.getHeardAtMs());
                    this.showBluetoothSection(true, false);

                    // "3 minutes ago" is only true for a minute. Nothing else on this screen
                    // redraws while it sits open, so without a tick the row would freeze at
                    // whatever it said when the tag went quiet and keep saying it for as long as
                    // somebody watched - which is precisely the staleness this row exists to
                    // report rather than commit.
                    this.lastSeenTicker = Observable
                            .interval(LAST_SEEN_REFRESH_MS, LAST_SEEN_REFRESH_MS,
                                    TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
                            .subscribe(tick -> this.showAgeOfLastSighting(
                                    sighting.getHeardAtMs()));
                }, error -> Log.w(TAG, "Could not read the last sighting for beaconId="
                        + this.beaconId, error));
    }

    /**
     * Puts the raw status byte the battery reading came out of into the debug panel.
     *
     * <p><b>To be measured against, not read as a battery level.</b> The section above decodes
     * bits 6-7 of this byte per Apple's Table 5-5, which is right for an MFi accessory and wrong
     * for an AirTag: {@link LocationReportFields} records one advertising {@code 0x90}, which
     * fails that table's marker and reserved bits, and notes that decoding it anyway reads "low"
     * for a tag whose own record says full. Every accessory this feature was built against is
     * third-party, so the reading has never been checked against hardware that does not follow
     * the specification.
     *
     * <p>Rendered by {@link LocationReportFields#status}, deliberately: it is the same rendering
     * a network report's copy of this byte gets, so the two can be compared directly, and it
     * appends a Table 5-5 reading only to a byte that actually conforms - which is the question
     * this row exists to answer.
     */
    private void showStatusByteForDebugging(final int statusByte) {
        this.binding.setBleStatusByte(LocationReportFields.status(statusByte));
        this.findViewById(R.id.settings_debug_ble_status_byte).setVisibility(VISIBLE);
    }

    /**
     * Writes the "Last seen" row, e.g. "3 minutes ago", from a wall-clock timestamp.
     *
     * <p><b>Under a minute it says "just now" rather than what the formatter returns</b>, which
     * is "0 minutes ago". That is the reading for the first half-minute after a tag goes quiet -
     * the live window is thirty seconds - so it is not a rare corner, it is what everybody sees
     * on the way from hearing the tag to not hearing it. "0 minutes ago" is also not really
     * English. Asking the formatter for second resolution instead would say "43 seconds ago",
     * which is a precision this row cannot keep: it redraws once a minute.
     *
     * <p>The same words as the live row, and that is honest - the tag really was heard just now.
     * What separates the two states on screen is the signal row, which is there only while the
     * reading is live.
     */
    private void showAgeOfLastSighting(final long heardAtMs) {
        final long ageMs = System.currentTimeMillis() - heardAtMs;

        this.binding.setBleLastSeen(ageMs < DateUtils.MINUTE_IN_MILLIS
                ? this.getString(R.string.seen_just_now)
                : DateUtils.getRelativeTimeSpanString(
                        heardAtMs, System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS).toString());
    }

    /** The section with every row, for a tag being heard right now. */
    private void showBluetoothSection(final boolean visible) {
        this.showBluetoothSection(visible, visible);
    }

    /**
     * Shows or hides the "Over Bluetooth" section - its divider, its heading and its rows
     * together, so it never appears as a heading with nothing under it.
     *
     * @param signalToo whether the signal row is among them. False once the tag has gone quiet:
     *                  see {@link #showWhatWasHeardOverBluetooth} for why that one row is
     *                  withdrawn while the others are merely dated.
     */
    private void showBluetoothSection(final boolean visible, final boolean signalToo) {
        final int visibility = visible ? VISIBLE : GONE;

        this.findViewById(R.id.device_ble_divider).setVisibility(visibility);
        this.findViewById(R.id.device_ble_header).setVisibility(visibility);
        this.findViewById(R.id.device_settings_ble_last_seen).setVisibility(visibility);
        this.findViewById(R.id.device_settings_ble_battery).setVisibility(visibility);
        this.findViewById(R.id.device_settings_ble_signal)
                .setVisibility(signalToo ? VISIBLE : GONE);
    }

    @Override
    protected void onDestroy() {
        this.stopWatchingForThisTag();
        if (this.hardwareLookup != null && !this.hardwareLookup.isDisposed()) {
            this.hardwareLookup.dispose();
        }
        // **Disposed, but the write is not cancelled** - it is already on its way to Apple, and
        // there is no undoing that from here. What this stops is the result landing on views
        // that have gone.
        if (this.accountRename != null && !this.accountRename.isDisposed()) {
            this.accountRename.dispose();
        }
        // Here disposing does cancel the underlying work - see BleGattSoundTrigger.trigger's
        // cancellable, which closes the GATT connection rather than leaving it dangling.
        if (this.playSoundNearby != null && !this.playSoundNearby.isDisposed()) {
            this.playSoundNearby.dispose();
        }
        super.onDestroy();
    }

    /**
     * Ask to play this accessory's sound directly over Bluetooth, without going through Apple's
     * Find My network - see {@code dev.wander.android.opentagviewer.ble}. Only reachable while
     * the accessory is close enough to answer a BLE scan, unlike the network-based search this
     * screen otherwise relies on.
     */
    private void onClickPlaySoundNearby() {
        if (!BlePermissions.granted(this)) {
            Log.d(TAG, "Requesting BLE permission(s) before playing sound nearby");
            ActivityCompat.requestPermissions(
                    this, BlePermissions.required(), PERMISSION_REQUEST_PLAY_SOUND_NEARBY);
            return;
        }
        this.startPlaySoundNearby();
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode, @androidx.annotation.NonNull final String[] permissions,
            @androidx.annotation.NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_PLAY_SOUND_NEARBY) return;

        // Re-checked against the same BlePermissions.granted this action gates on elsewhere,
        // rather than reading grantResults directly - one place decides what "enough" means,
        // matching the reasoning in BlePermissions' own class doc.
        if (BlePermissions.granted(this)) {
            Log.i(TAG, "BLE permission granted; playing sound nearby for beaconId=" + this.beaconId);
            this.startPlaySoundNearby();
        } else {
            Log.i(TAG, "BLE permission refused; not playing sound nearby for beaconId=" + this.beaconId);
            Toast.makeText(this, R.string.play_sound_permission_denied, LENGTH_LONG).show();
        }
    }

    private void startPlaySoundNearby() {
        final String accessoryJson = this.beaconData.getOwnedBeaconInfo().accessoryJson;

        this.showPlaySoundStatus(R.string.play_sound_searching, LENGTH_SHORT);

        if (this.playSoundNearby != null && !this.playSoundNearby.isDisposed()) {
            this.playSoundNearby.dispose();
        }

        this.playSoundNearby = AppDependencies.accessorySoundTrigger()
                .playSound(this.getApplicationContext(), accessoryJson)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handlePlaySoundUpdate,
                        error -> {
                            // AccessorySoundTrigger's contract is to never error a failure onto
                            // this path - see its interface doc - so reaching here means a bug
                            // in that contract, not an ordinary "not found" or "no permission".
                            Log.e(TAG, "Unexpected error playing sound for beaconId="
                                    + this.beaconId, error);
                            this.showPlaySoundStatus(R.string.play_sound_failed, LENGTH_LONG);
                        });
    }

    /**
     * One item of the play-sound stream: a progress phase (shown and replaced, see
     * {@link #showPlaySoundStatus}) or the terminal outcome.
     */
    private void handlePlaySoundUpdate(final BleSoundTriggerUpdate update) {
        this.sightingPersister.keepWhatTheSightingProved(this.beaconId, update);

        if (update.getPhase() != BleSoundTriggerPhase.DONE) {
            this.showPlaySoundStatus(phaseMessageRes(update.getPhase()), LENGTH_SHORT);
            return;
        }
        this.showPlaySoundResult(update.getResult());
    }

    private static int phaseMessageRes(final BleSoundTriggerPhase phase) {
        switch (phase) {
            case CONNECTING:
                return R.string.play_sound_connecting;
            case TRIGGERING:
                return R.string.play_sound_sending;
            case SCANNING:
            default:
                return R.string.play_sound_searching;
        }
    }

    private void showPlaySoundResult(final BleSoundTriggerResult result) {
        Log.d(TAG, "Play sound result for beaconId=" + this.beaconId + ": " + result.getStatus()
                + (result.getMessage() == null ? "" : " (" + result.getMessage() + ")"));

        final int messageRes;
        switch (result.getStatus()) {
            case SUCCESS:
                messageRes = R.string.play_sound_success;
                break;
            case NOT_NEARBY:
                messageRes = R.string.play_sound_not_nearby;
                break;
            case NO_SOUND_SERVICE:
                messageRes = R.string.play_sound_no_sound_service;
                break;
            case NO_CANDIDATE_MACS:
                messageRes = R.string.play_sound_no_candidate_macs;
                break;
            case MISSING_PERMISSION:
                messageRes = R.string.play_sound_permission_denied;
                break;
            case FAILED:
            default:
                messageRes = R.string.play_sound_failed;
                break;
        }
        this.showPlaySoundStatus(messageRes, LENGTH_LONG);
    }

    /**
     * Cancels whichever status toast is on screen and shows the next one immediately, rather
     * than queuing behind it. Plain sequential {@code Toast.makeText(...).show()} calls queue
     * with a fixed display duration each, so "searching" would sit on screen for its whole
     * duration even after "connecting" was already true - reading as stuck, not as progress.
     */
    private void showPlaySoundStatus(final int messageRes, final int duration) {
        if (this.playSoundStatusToast != null) {
            this.playSoundStatusToast.cancel();
        }
        this.playSoundStatusToast = Toast.makeText(this, messageRes, duration);
        this.playSoundStatusToast.show();
    }

    /**
     * The best description available without asking Python.
     *
     * <p>A self-generated tag is checked first, and not as another guess: the other two read a
     * plist field and it has no plist at all, so without this it falls through both and reports
     * "Unknown" - the one answer that is definitely wrong, since it is the kind of tag the app
     * knows the most about.
     */
    private String knownDeviceType() {
        if (this.beaconInformation.isCustomAccessory()) {
            return this.getString(R.string.custom_tag);
        }
        if (this.beaconInformation.isIpad()) {
            return this.getString(R.string.ipad);
        }
        if (this.beaconInformation.isAirTag()) {
            return this.getString(R.string.airtag);
        }
        return this.getString(R.string.unknown);
    }

    /**
     * Ask the shared heuristic what this actually is, and improve the label if it knows.
     *
     * <p><b>Why bother, when {@link #knownDeviceType()} already answered.</b> That answer is the
     * older, narrower version of the same question: it recognises an AirTag and an iPad and
     * nothing else, so a pair of AirPods, a Tile or a Chipolo all arrive as "Unknown". The shared
     * heuristic names them, knows which AirPod it is, and falls back to the vendor and product
     * ids with somewhere to look them up. It lives in {@code opentagviewer_export/hardware.py}
     * and the desktop exporter uses the same module - see AGENTS.md rule on not porting the
     * table, because the vendor list grows and two copies means one goes stale.
     *
     * <p><b>Off the main thread, and only ever an improvement.</b> The call starts a Python
     * interpreter and parses a plist. A null answer means nothing recognised the record, and
     * then the label already on screen stands - a wrong name is believed, where a hex number
     * gets looked up.
     */
    private void describeHardwareInTheBackground() {
        final String plist = this.beaconInformation.getOwnedBeaconPlistRaw();
        if (plist == null || plist.isEmpty()) {
            // A self-generated tag, which describes itself and has no plist to read.
            return;
        }

        final HardwareDescriber describer = AppDependencies.hardwareDescriber();

        // Both answers in one crossing. Each call starts a Python interpreter and parses the
        // same plist, and the second question is only ever asked about the first one's failure.
        this.hardwareLookup = Observable
                .fromCallable(() -> Pair.create(
                        Optional.ofNullable(describer.describe(plist)),
                        Pair.create(
                                Optional.ofNullable(describer.whereToLookUp(plist)),
                                Optional.ofNullable(describer.isOwnDevice(plist)))))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        answers -> {
                            answers.first.ifPresent(this.binding::setDeviceType);
                            if (answers.second.first.isPresent()) {
                                this.offerToLookTheVendorUp();
                            }
                            // Left null when the heuristic could not say, which keeps renaming
                            // local. See the field.
                            this.isOwnDevice = answers.second.second.orElse(null);

                            // Re-run now the answer is in: whether "original" means anything
                            // depends on it, and it arrives after the screen is drawn.
                            this.showTheOriginalNameOnlyWhereItMeansSomething();
                        },
                        error -> Log.w(TAG, "Could not describe this accessory; "
                                + "keeping the label already shown", error));
    }

    /**
     * Say what the hex on the Type row is, and offer to settle it.
     *
     * <p>Shown only when nothing recognised the accessory, which is when Type reads something
     * like {@code vendor 0x0ABC product 0x1234}. That is a real registry value rather than a
     * failure, and somebody holding the thing can settle what it is in under a minute - but only
     * if they are told the number means something.
     *
     * <p><b>Python decides whether there is anything to look up; this writes the sentence.</b>
     * {@code where_to_look_up} returns one already, and it is deliberately not used: it is
     * English, composed in a module the desktop exporter shares, and this app ships in ten
     * languages. Splitting it this way keeps the judgement in the one place that has the vendor
     * table and the wording in the one place that gets translated.
     */
    private void offerToLookTheVendorUp() {
        final TextView hint = this.findViewById(R.id.device_type_lookup_hint);

        hint.setText(this.getString(R.string.vendor_lookup_hint,
                String.format(Locale.ROOT, "0x%04X", this.beaconInformation.getVendorId())));
        hint.setVisibility(VISIBLE);
        hint.setOnClickListener(view -> this.openBluetoothRegistry());
    }

    private void openBluetoothRegistry() {
        final var properties = PropertiesUtil.getProperties(this.getAssets(), "app.properties");
        if (properties == null) {
            Log.w(TAG, "Could not read app.properties; no registry link to open");
            return;
        }

        final String url = properties.getProperty("bluetoothSigAssignedNumbers");
        if (url == null || url.isBlank()) {
            Log.w(TAG, "No bluetoothSigAssignedNumbers configured in app.properties");
            return;
        }

        WebLink.open(this, url);
    }

    private String getDeviceNameForTitle() {
        if (this.beaconInformation.isEmojiFilled()) {
            return String.format("%s %s", this.beaconInformation.getEmoji(), this.beaconInformation.getName());
        }
        return this.beaconInformation.getName();
    }

    private void handleClickMenu() {
        Log.d(TAG, "Device more button clicked");

        ImageButton button = findViewById(R.id.page_menu_button);

        var popupMenu = new PopupMenu(this, button);
        popupMenu.getMenuInflater().inflate(R.menu.device_info_menu, popupMenu.getMenu());

        popupMenu.setOnMenuItemClickListener(menuItem -> {
            Log.d(TAG, "Device menu option " + menuItem.getTitle() + " was selected");

            if (menuItem.getItemId() == R.id.device_location_history) {
                this.redirectToDeviceHistory();
            } else if (menuItem.getItemId() == R.id.device_play_sound_nearby) {
                this.onClickPlaySoundNearby();
            } else if (menuItem.getItemId() == R.id.device_delete) {
                this.onClickDeviceDelete();
            }

            return true;
        });

        popupMenu.show();
    }

    private void redirectToDeviceHistory() {
        Log.d(TAG, "Going to send to the history page for beaconId=" + beaconId);

        final Intent viewHistoryIntent = new Intent(this, HistoryViewActivity.class);
        viewHistoryIntent.putExtra("beaconId", beaconId);

        viewHistoryIntent.putExtra("lon", DEFAULT_LONGITUDE);
        viewHistoryIntent.putExtra("lat", DEFAULT_LATITUDE);
        viewHistoryIntent.putExtra("zoom", DEFAULT_ZOOM);

        var async = this.userDataRepository.getLastCameraPosition()
            .take(1)
            .subscribe(pos -> {
                viewHistoryIntent.putExtra("lon", pos.map(UserMapCameraPosition::getLon).orElse(DEFAULT_LONGITUDE));
                viewHistoryIntent.putExtra("lat", pos.map(UserMapCameraPosition::getLat).orElse(DEFAULT_LATITUDE));
                viewHistoryIntent.putExtra("zoom", pos.map(UserMapCameraPosition::getZoom).orElse(DEFAULT_ZOOM));
                startActivity(viewHistoryIntent);
            }, error -> {
                Log.w(TAG, "Error retrieving stored last camera position!", error);
                startActivity(viewHistoryIntent);
            });
    }

    /**
     * Say when this tag was last looked for, and how hard the app is still trying.
     *
     * <p><b>Otherwise "no last location known" is the whole story</b>, and it covers three
     * different situations that need different reactions: a tag nobody has walked past today, a
     * tag being asked about less and less because it keeps answering nothing, and a tag the app
     * has given up on. Only the last of those is worth a person's attention, and only it has
     * anything they can do about it.
     *
     * <p>The notice is for everybody; the three rows below it are behind the debug switch,
     * because "3 fruitless searches, next attempt in 4h" is a sentence for whoever is diagnosing
     * a bug report rather than for the person who just wants their keys.
     */
    private void describeHowItIsBeingLookedFor(final SimpleDateFormat timestamps) {
        final boolean ignored = this.beaconInformation.isIgnored();

        this.findViewById(R.id.device_ignored_notice).setVisibility(ignored ? VISIBLE : GONE);
        if (ignored) {
            this.findViewById(R.id.device_ignored_retry)
                    .setOnClickListener(view -> this.lookForItAgainNow());
        }

        final Long lastScan = this.beaconInformation.getLastScanAt();
        this.binding.setLastScanAttempt(lastScan == null
                ? this.getString(R.string.debug_never)
                : timestamps.format(new Date(lastScan)));

        final Long newest = this.beaconRepo.newestReportTimeFor(this.beaconId).blockingFirst()
                .orElse(null);
        this.binding.setLastResultAt(newest == null
                ? this.getString(R.string.debug_never)
                : timestamps.format(new Date(newest)));

        this.binding.setBackoffState(this.describeBackoff(timestamps));
    }

    private String describeBackoff(final SimpleDateFormat timestamps) {
        final Long ignoredAt = this.beaconInformation.getIgnoredAt();
        if (ignoredAt != null) {
            return this.getString(
                    R.string.debug_backoff_ignored, timestamps.format(new Date(ignoredAt)));
        }

        final int scans = this.beaconInformation.getFruitlessScans();
        if (scans <= 0) {
            return this.getString(R.string.debug_backoff_normal);
        }

        final Long lastScan = this.beaconInformation.getLastScanAt();
        final long dueAt = (lastScan == null ? System.currentTimeMillis() : lastScan)
                + WideScanBackoff.waitMillisAfter(scans);

        return this.getString(R.string.debug_backoff_waiting, scans,
                DateUtils.getRelativeTimeSpanString(
                        dueAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
    }

    /**
     * Hand the request back to the map, which owns fetching.
     *
     * <p><b>Deliberately the manual path.</b> That one is not subject to the backoff at all, so a
     * tag the app had stopped asking about is asked about immediately - which is the entire point
     * of the button. Anything found clears the flag as an ordinary consequence of a successful
     * search, rather than through a second code path that could disagree with the first.
     */
    private void lookForItAgainNow() {
        final Intent data = new Intent();
        data.putExtra(RETRY_IGNORED_BEACON, this.beaconId);
        this.setResult(RESULT_OK, data);
        this.finish();
    }

    /** Asks whoever launched this screen to search for the named tag right now. */
    public static final String RETRY_IGNORED_BEACON = "retryIgnoredBeacon";

    /**
     * Hide "original name" and "original emoji" for a tag whose name is not a nickname.
     *
     * <p><b>"Original" is only a coherent idea where something is layered over it.</b> A tag
     * imported from a file, or one of the owner's own devices, keeps the name Apple gave it and
     * shows a local nickname on top - so the two are different things and both are worth seeing.
     * An accessory read from iCloud has no such split: renaming it writes to the account, so the
     * name on screen <i>is</i> the name, and a row labelled "original" showing the same string
     * invites the reader to hunt for a difference that cannot exist.
     *
     * <p>Deliberately the same predicate that decides where a rename goes. The two questions are
     * one question - "is this a name we can actually change" - and answering it twice is how they
     * end up disagreeing.
     *
     * <p>Called again once the heuristic answers, because it decides this and arrives after the
     * screen is drawn.
     */
    private void showTheOriginalNameOnlyWhereItMeansSomething() {
        final int visibility = this.renamingWritesToTheAccount() ? GONE : VISIBLE;

        this.findViewById(R.id.settings_debug_device_name_original).setVisibility(visibility);
        this.findViewById(R.id.settings_debug_device_emoji_original).setVisibility(visibility);
    }

    private void onClickDeviceDelete() {
        // A tag read from the Apple account is a cache of what Apple holds, so marking it
        // removed here would undo itself at the next refresh - the row is written back with
        // `is_removed = 0` and the tag reappears with no explanation. Removing it for real is
        // done in Find My. See MyDevicesListActivity#confirmRemoveSelection, which says the
        // same thing for a selection.
        if (this.beaconInformation.isFromAccount()) {
            new MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
                    .setTitle(R.string.cannot_remove_account_tag_title)
                    .setIcon(R.drawable.help_center_24px)
                    .setMessage(R.string.cannot_remove_account_tag_message)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }

        var dialog = new MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
                .setTitle(R.string.remove_device)
                .setIcon(R.drawable.delete_24px)
                .setMessage(R.string.are_you_sure_you_want_to_remove_this_device_once_removed_it_will_need_to_be_reimported_to_get_it_back)
                .setPositiveButton(R.string.confirm, (dialog1, which) -> {
                    Log.d(TAG, "Clicked to confirm device deletion. Now proceeding to delete (actually hide) device...");
                    this.handleDeviceRemoval();
                }).setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void handleDeviceRemoval() {
        final String beaconId = this.beaconId;
        var async = this.beaconRepo.markBeaconAsRemoved(beaconId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    Intent data = new Intent();
                    data.putExtra("deviceWasRemoved", beaconId);
                    setResult(RESULT_OK, data);
                    this.finish();
                }, error -> {
                    Log.e(TAG, "Failure marking beacon as removed!", error);
                    Toast.makeText(this.getApplicationContext(), "Error occurred while trying to delete the beacon!", LENGTH_LONG).show();
                });
    }
}