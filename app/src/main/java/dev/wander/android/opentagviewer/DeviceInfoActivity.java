package dev.wander.android.opentagviewer;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
import dev.wander.android.opentagviewer.db.room.AirTag4AllDatabase;
import dev.wander.android.opentagviewer.db.room.entity.Import;
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
                AirTag4AllDatabase.getInstance(getApplicationContext()));

        this.beaconData = this.beaconRepo.getById(this.beaconId).blockingFirst();
        this.beaconInformation = BeaconDataParser.parse(List.of(this.beaconData)).get(0);
        this.importData = this.beaconRepo.getImportById(this.beaconData.getOwnedBeaconInfo().importId).blockingFirst().orElseThrow();

        ActivityDeviceInfoBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_device_info);
        binding.setHandleClickBack(this::finish);
        binding.setHandleClickMenu(this::handleClickMenu);

        binding.setPageTitle(this.getDeviceNameForTitle());
        binding.setDeviceName(this.beaconInformation.getName());
        binding.setExportedAt(timestampFormat.format(new Date(this.importData.exportedAt)));
        binding.setImportedAt(timestampFormat.format(new Date(this.importData.importedAt)));
        binding.setExportedBy(this.importData.sourceUser);

        binding.setDeviceType(this.beaconInformation.isIpad() ? this.getString(R.string.ipad)
                : this.beaconInformation.isAirTag() ? this.getString(R.string.airtag)
                : this.getString(R.string.unknown));

        // debug info
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

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        // TODO: users should be able to rename and change the emojis of their icons.
    }

    private String getDeviceNameForTitle() {
        if (this.beaconInformation.getEmoji() != null && !this.beaconInformation.getEmoji().isBlank()) {
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
                .setMessage(R.string.are_you_sure_you_want_to_remove_this_device_once_removed_it_will_need_to_be_reimported_to_get_it_back)
                .setNegativeButton(R.string.confirm, (dialog1, which) -> {
                    Log.d(TAG, "Clicked to confirm device deletion. Now proceeding to delete (actually hide) device...");
                    this.handleDeviceRemoval();
                }).setNeutralButton(R.string.cancel, null)
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