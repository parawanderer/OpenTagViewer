package dev.wander.android.opentagviewer;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.databinding.ActivityMyDevicesListBinding;
import dev.wander.android.opentagviewer.db.repo.BeaconRepository;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.ui.mydevices.DeviceListAdaptor;
import dev.wander.android.opentagviewer.util.android.PropertiesUtil;
import dev.wander.android.opentagviewer.util.parse.BeaconDataParser;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class MyDevicesListActivity extends AppCompatActivity {
    private static final String TAG = MyDevicesListActivity.class.getSimpleName();

    private BeaconRepository beaconRepo;

    private final List<BeaconInformation> beaconInfo = new ArrayList<>();

    private final Map<String, BeaconLocationReport> locations = new HashMap<>();

    private DeviceListAdaptor deviceListAdaptor;

    private boolean devicesListChanged = false;

    private final ActivityResultLauncher<Intent> deviceInfoActivityLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            (ActivityResult result) -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null && data.getStringExtra("deviceWasRemoved") != null) {
                        this.refreshListOnItemRemoved(data.getStringExtra("deviceWasRemoved"));
                    }
                    if (data != null && data.getStringExtra("deviceWasChanged") != null) {
                        this.refreshListOnBeaconChanged(data.getStringExtra("deviceWasChanged"));
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.beaconRepo = new BeaconRepository(
                OpenTagViewerDatabase.getInstance(getApplicationContext()));

        ActivityMyDevicesListBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_my_devices_list);
        WindowPaddingUtil.insertUITopPadding(binding.getRoot());
        binding.setHandleClickBack(this::handleEndActivity);

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        this.deviceListAdaptor = new DeviceListAdaptor(
                this.getResources(),
                this.beaconInfo,
                this.locations,
                this::onDeviceClicked,
                this::onDeviceLongPressed);

        RecyclerView recyclerView = findViewById(R.id.my_devices_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(deviceListAdaptor);

        findViewById(R.id.my_devices_empty_import_button)
                .setOnClickListener(v -> this.handleStartImport());

        findViewById(R.id.my_devices_empty_wiki_link)
                .setOnClickListener(v -> this.openExportGuide());

        this.getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleEndActivity();
            }
        });

        this.fetchDeviceInfoAndRender();
    }

    private void handleEndActivity() {
        Intent data = new Intent();
        data.putExtra("isDeviceListChanged", this.devicesListChanged);
        setResult(RESULT_OK, data);
        this.finish();
    }

    private void refreshListOnItemRemoved(final String beaconId) {
        this.devicesListChanged = true;

        var removedIndex = IntStream.range(0, this.beaconInfo.size())
                        .filter(i -> this.beaconInfo.get(i).getBeaconId().equals(beaconId))
                        .findFirst();

        if (removedIndex.isPresent()) {
            final int index = removedIndex.getAsInt();
            this.beaconInfo.remove(index);
            deviceListAdaptor.notifyItemRangeRemoved(index, 1);
            this.updateEmptyState();
        }
    }

    private void refreshListOnBeaconChanged(final String beaconId) {
        this.devicesListChanged = true;

        var changedIndex = IntStream.range(0, this.beaconInfo.size())
                .filter(i -> this.beaconInfo.get(i).getBeaconId().equals(beaconId))
                .findFirst();

        if (changedIndex.isPresent()) {
            final int index = changedIndex.getAsInt();

            var async = this.beaconRepo.getById(beaconId)
                    .flatMap(beacon -> BeaconDataParser.parseAsync(List.of(beacon)))
                    .map(parsed -> parsed.get(0))
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(newDataForBeacon -> {
                        this.beaconInfo.set(index, newDataForBeacon);
                        deviceListAdaptor.notifyItemChanged(index);
                    }, error -> Log.e(TAG, "Error occurred while querying for updated data for beaconId=" + beaconId, error));
        }
    }

    private void fetchDeviceInfoAndRender() {
        var asyncLocations = this.beaconRepo.getLastLocationsForAll();

        var asyncBeacons = this.beaconRepo.getAllBeacons()
                .flatMap(BeaconDataParser::parseAsync);

        var async = Observable.zip(asyncBeacons, asyncLocations, Pair::create)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((beaconsAndLocations) -> {
                    this.beaconInfo.addAll(beaconsAndLocations.first);
                    this.locations.putAll(beaconsAndLocations.second);
                    deviceListAdaptor.notifyItemRangeInserted(0, this.beaconInfo.size());
                    this.updateEmptyState();
                }, error -> Log.e(TAG, "Failure retrieving beacons and latest stored locations for beacon"));
    }

    private void onDeviceClicked(final BeaconInformation clickedDevice) {
        Intent deviceInfoIntent = new Intent(this, DeviceInfoActivity.class);
        deviceInfoIntent.putExtra("beaconId", clickedDevice.getBeaconId());
        deviceInfoActivityLauncher.launch(deviceInfoIntent);
    }

    /**
     * Long press on a row offers to remove it, saving a trip through the device's detail page.
     * <br>
     * Anchored to the pressed row rather than shown as a dialog, so it is obvious which device
     * is about to be acted on - the confirmation itself does not name the device.
     */
    private void onDeviceLongPressed(final View anchor, final BeaconInformation device) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenuInflater().inflate(R.menu.device_list_item_menu, menu.getMenu());

        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_remove_device) {
                this.confirmRemoveDevice(device);
                return true;
            }
            return false;
        });

        menu.show();
    }

    private void confirmRemoveDevice(final BeaconInformation device) {
        new MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
                .setTitle(R.string.remove_device)
                .setIcon(R.drawable.delete_24px)
                .setMessage(R.string.are_you_sure_you_want_to_remove_this_device_once_removed_it_will_need_to_be_reimported_to_get_it_back)
                .setPositiveButton(R.string.confirm, (dialog, which) -> this.removeDevice(device))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void removeDevice(final BeaconInformation device) {
        final String beaconId = device.getBeaconId();

        // Same call DeviceInfoActivity makes: rows are hidden rather than deleted, so the
        // location history survives a re-import.
        var async = this.beaconRepo.markBeaconAsRemoved(beaconId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> this.refreshListOnItemRemoved(beaconId),
                        error -> {
                            Log.e(TAG, "Failure marking beacon as removed!", error);
                            Toast.makeText(
                                    this.getApplicationContext(),
                                    R.string.error_occurred_while_removing_the_device,
                                    LENGTH_LONG).show();
                        });
    }

    /**
     * The zip has to be produced on a Mac, so a user who has nothing imported cannot get one
     * from inside the app. Without this the empty state tells them what they need and gives
     * them no way to find out how to get it.
     */
    private void openExportGuide() {
        var properties = PropertiesUtil.getProperties(this.getAssets(), "app.properties");
        if (properties == null) {
            Log.w(TAG, "Could not read app.properties; no export guide link to open");
            return;
        }

        final String url = properties.getProperty("exportWikiPage");
        if (url == null || url.isBlank()) {
            Log.w(TAG, "No exportWikiPage configured in app.properties");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (intent.resolveActivity(getPackageManager()) != null) {
            this.startActivity(intent);
        }
    }

    private void handleStartImport() {
        // Importing is driven from MapsActivity, which owns the file picker and the code that
        // fetches locations for whatever comes back. Hand the request back rather than
        // duplicating any of that here.
        Intent data = new Intent();
        data.putExtra("isDeviceListChanged", this.devicesListChanged);
        data.putExtra("startImport", true);
        setResult(RESULT_OK, data);
        this.finish();
    }

    /** Swaps the list for the empty state, or back, after anything changes the contents. */
    private void updateEmptyState() {
        final boolean isEmpty = this.beaconInfo.isEmpty();

        findViewById(R.id.my_devices_empty_state).setVisibility(isEmpty ? VISIBLE : GONE);
        findViewById(R.id.my_devices_list).setVisibility(isEmpty ? GONE : VISIBLE);
    }
}