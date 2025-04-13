package dev.wander.android.opentagviewer;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.View.inflate;
import static android.widget.Toast.LENGTH_LONG;

import static dev.wander.android.opentagviewer.util.android.TextChangedWatcherFactory.justWatchOnChanged;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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
import java.util.Objects;
import java.util.Optional;

import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.data.model.UserMapCameraPosition;
import dev.wander.android.opentagviewer.databinding.ActivityDeviceInfoBinding;
import dev.wander.android.opentagviewer.db.datastore.UserCacheDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.BeaconRepository;
import dev.wander.android.opentagviewer.db.repo.UserDataRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.BeaconData;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.UserBeaconOptions;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.util.parse.BeaconDataParser;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.annotations.NonNull;

public class DeviceInfoActivity extends AppCompatActivity {
    private static final String TAG = DeviceInfoActivity.class.getSimpleName();

    private static final double DEFAULT_LONGITUDE = 0d;
    private static final double DEFAULT_LATITUDE = 0d;
    private static final float DEFAULT_ZOOM = 16.0f;

    private String beaconId;
    private UserSettingsRepository userSettingsRepo;
    private UserDataRepository userDataRepository;
    private BeaconRepository beaconRepo;
    private BeaconData beaconData;
    private BeaconInformation beaconInformation;
    private @NonNull Import importData;
    private UserSettings userSettings;
    private EmojiPickerView emojiPickerView;
    private Button currentIconButton;
    private ActivityDeviceInfoBinding binding;

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

        this.beaconData = this.beaconRepo.getById(this.beaconId).blockingFirst();
        this.beaconInformation = BeaconDataParser.parse(List.of(this.beaconData)).get(0);
        this.importData = this.beaconRepo.getImportById(this.beaconData.getOwnedBeaconInfo().importId).blockingFirst().orElseThrow();

        binding = DataBindingUtil.setContentView(this, R.layout.activity_device_info);
        WindowPaddingUtil.insertUITopPadding(binding.getRoot());

        binding.setHandleClickBack(this::handleEndActivity);
        binding.setHandleClickMenu(this::handleClickMenu);
        binding.setClickItemHandler(() -> Log.d(TAG, "Some device info item was clicked"));

        binding.setPageTitle(this.getDeviceNameForTitle());
        binding.setDeviceName(this.beaconInformation.getName());
        binding.setOnClickDeviceName(this::handleEditDeviceName);
        binding.setOnClickDeviceEmoji(this::handleEditDeviceEmoji);

        binding.setExportedAt(timestampFormat.format(new Date(this.importData.exportedAt)));
        binding.setImportedAt(timestampFormat.format(new Date(this.importData.importedAt)));
        binding.setExportedBy(this.importData.sourceUser);

        binding.setDeviceType(this.beaconInformation.isIpad() ? this.getString(R.string.ipad)
                : this.beaconInformation.isAirTag() ? this.getString(R.string.airtag)
                : this.getString(R.string.unknown));

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

        binding.setBatteryLevel(this.beaconInformation.getBatteryLevel() + "");
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
            ((MaterialButton)currentIconButton).setIcon(AppCompatResources.getDrawable(this, R.drawable.apple));
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

    private void saveUpdatedDeviceName(final String newDeviceName) {
        final String oldDeviceName = this.beaconInformation.getName();

        if (oldDeviceName.equals(newDeviceName)) return; // nothing to do, no change

        this.beaconInformation.setUserOverrideName(newDeviceName);
        // save changes...
        var async = this.beaconRepo.storeUserBeaconOptions(new UserBeaconOptions(
                this.beaconId,
                System.currentTimeMillis(),
                this.beaconInformation.getUserOverrideName(),
                this.beaconInformation.getUserOverrideEmoji()
        )).observeOn(AndroidSchedulers.mainThread())
        .subscribe(() -> {
                Log.d(TAG, "Successfully updated UI-facing device name for beaconId=" + this.beaconId);
                this.hasNameChanges = true;
                this.binding.setDeviceName(this.beaconInformation.getName());
                binding.setPageTitle(this.getDeviceNameForTitle());
            },
            error -> Log.e(TAG, "Error occurred while trying to update user-facing device name for beaconId=" + this.beaconId, error));
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

        this.beaconInformation.setUserOverrideEmoji(newEmoji);

        // save changes
        var async = this.beaconRepo.storeUserBeaconOptions(new UserBeaconOptions(
                this.beaconId,
                System.currentTimeMillis(),
                this.beaconInformation.getUserOverrideName(),
                this.beaconInformation.getUserOverrideEmoji()
        )).observeOn(AndroidSchedulers.mainThread())
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

    private void onClickDeviceDelete() {
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