package dev.wander.android.opentagviewer;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;

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

        this.deviceListAdaptor = new DeviceListAdaptor(this.getResources(), this.beaconInfo, this.locations, this::onDeviceClicked);

        RecyclerView recyclerView = findViewById(R.id.my_devices_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(deviceListAdaptor);

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
                }, error -> Log.e(TAG, "Failure retrieving beacons and latest stored locations for beacon"));
    }

    private void onDeviceClicked(final BeaconInformation clickedDevice) {
        Intent deviceInfoIntent = new Intent(this, DeviceInfoActivity.class);
        deviceInfoIntent.putExtra("beaconId", clickedDevice.getBeaconId());
        deviceInfoActivityLauncher.launch(deviceInfoIntent);
    }
}