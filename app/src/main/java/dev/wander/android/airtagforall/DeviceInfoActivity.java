package dev.wander.android.airtagforall;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.LinearLayout;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.databinding.DataBindingUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.wander.android.airtagforall.data.model.BeaconInformation;
import dev.wander.android.airtagforall.databinding.ActivityDeviceInfoBinding;
import dev.wander.android.airtagforall.databinding.ActivityMyDevicesListBinding;
import dev.wander.android.airtagforall.db.datastore.UserSettingsDataStore;
import dev.wander.android.airtagforall.db.repo.BeaconRepository;
import dev.wander.android.airtagforall.db.repo.UserSettingsRepository;
import dev.wander.android.airtagforall.db.repo.model.BeaconData;
import dev.wander.android.airtagforall.db.repo.model.UserSettings;
import dev.wander.android.airtagforall.db.room.AirTag4AllDatabase;
import dev.wander.android.airtagforall.db.room.entity.Import;
import dev.wander.android.airtagforall.util.parse.BeaconDataParser;
import io.reactivex.rxjava3.annotations.NonNull;

public class DeviceInfoActivity extends AppCompatActivity {
    private static final String TAG = DeviceInfoActivity.class.getSimpleName();

    private String beaconId;
    private UserSettingsRepository userSettingsRepo;
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

        this.userSettings = this.userSettingsRepo.getUserSettings();

        this.beaconRepo = new BeaconRepository(
                AirTag4AllDatabase.getInstance(getApplicationContext()));

        this.beaconData = this.beaconRepo.getById(this.beaconId).blockingFirst();
        this.beaconInformation = BeaconDataParser.parse(List.of(this.beaconData)).get(0);
        this.importData = this.beaconRepo.getImportById(this.beaconData.getOwnedBeaconInfo().importId).blockingFirst().orElseThrow();

        ActivityDeviceInfoBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_device_info);
        binding.setHandleClickBack(this::finish);
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
}