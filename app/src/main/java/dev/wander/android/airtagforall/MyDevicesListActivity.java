package dev.wander.android.airtagforall;

import android.os.Bundle;
import android.util.Log;
import android.util.Pair;

import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.wander.android.airtagforall.data.model.BeaconInformation;
import dev.wander.android.airtagforall.data.model.BeaconLocationReport;
import dev.wander.android.airtagforall.databinding.ActivityMyDevicesListBinding;
import dev.wander.android.airtagforall.db.repo.BeaconRepository;
import dev.wander.android.airtagforall.db.room.AirTag4AllDatabase;
import dev.wander.android.airtagforall.ui.mydevices.CustomAdapter;
import dev.wander.android.airtagforall.util.parse.BeaconDataParser;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class MyDevicesListActivity extends AppCompatActivity {
    private static final String TAG = MyDevicesListActivity.class.getSimpleName();

    private BeaconRepository beaconRepo;

    private final List<BeaconInformation> beaconInfo = new ArrayList<>();

    private final Map<String, BeaconLocationReport> locations = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.beaconRepo = new BeaconRepository(
                AirTag4AllDatabase.getInstance(getApplicationContext()));

        ActivityMyDevicesListBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_my_devices_list);
        binding.setHandleClickBack(this::finish);

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        var customAdapter = new CustomAdapter(this.getResources(), this.beaconInfo, this.locations);

        RecyclerView recyclerView = findViewById(R.id.my_devices_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(customAdapter);


        var asyncLocations = this.beaconRepo.getLastForAll();

        var asyncBeacons = this.beaconRepo.getAllBeacons()
                .flatMap(BeaconDataParser::parseAsync);

        var async = Observable.zip(asyncBeacons, asyncLocations, Pair::create)
            .subscribeOn(Schedulers.io())
            .subscribe((beaconsAndLocations) -> {

                this.beaconInfo.addAll(beaconsAndLocations.first);
                this.locations.putAll(beaconsAndLocations.second);

                this.runOnUiThread(() -> {
                    customAdapter.notifyItemRangeInserted(0, this.beaconInfo.size());
                });

            }, error -> Log.e(TAG, "Failure retrieving beacons and latest stored locations for beacon"));
    }
}