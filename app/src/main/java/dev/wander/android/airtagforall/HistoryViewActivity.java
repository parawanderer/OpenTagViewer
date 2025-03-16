package dev.wander.android.airtagforall;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import dev.wander.android.airtagforall.data.model.BeaconInformation;
import dev.wander.android.airtagforall.data.model.BeaconLocationReport;
import dev.wander.android.airtagforall.databinding.ActivityHistoryViewBinding;
import dev.wander.android.airtagforall.db.datastore.UserSettingsDataStore;
import dev.wander.android.airtagforall.db.repo.BeaconRepository;
import dev.wander.android.airtagforall.db.repo.UserSettingsRepository;
import dev.wander.android.airtagforall.db.repo.model.UserSettings;
import dev.wander.android.airtagforall.db.room.AirTag4AllDatabase;
import dev.wander.android.airtagforall.ui.history.HistoryItemsAdapter;
import dev.wander.android.airtagforall.util.parse.BeaconDataParser;

public class HistoryViewActivity extends AppCompatActivity implements OnMapReadyCallback {
    private static final String TAG = HistoryViewActivity.class.getSimpleName();

    private static final float HISTORY_SHEET_HALF_EXPANDED_RATIO = 0.4f;

    private GoogleMap map;

    private BeaconRepository beaconRepo;
    private UserSettingsRepository userSettingsRepo;

    private UserSettings userSettings;

    private String beaconId;

    private BeaconInformation beaconInformation;

    private List<BeaconLocationReport> locations = new ArrayList<>();

    private HistoryItemsAdapter historyItemsAdapter;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.beaconId = getIntent().getStringExtra("beaconId");
        Log.d(TAG, "Showing history view for beaconId=" + this.beaconId);

        this.beaconRepo = new BeaconRepository(
                AirTag4AllDatabase.getInstance(getApplicationContext()));

        this.userSettingsRepo = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.getApplicationContext()));

        this.userSettings = this.userSettingsRepo.getUserSettings();

        this.beaconInformation = this.beaconRepo.getById(this.beaconId)
                .flatMap(data -> BeaconDataParser.parseAsync(List.of(data)))
                .map(items -> items.get(0))
                .blockingFirst();

        ActivityHistoryViewBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_history_view);
        binding.setHandleClickBack(this::finish);

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        ViewGroup content = this.findViewById(R.id.history_bottomsheet_coordinator_layout);
        content.addView(getLayoutInflater().inflate(R.layout.view_history_bottom_sheet, content, false));
        this.setupBottomSheetHeights();

        this.historyItemsAdapter = new HistoryItemsAdapter(this.getResources(), this.locations);
        RecyclerView recyclerView = findViewById(R.id.recycler_view_history_items);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(this.historyItemsAdapter);

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.history_map);
        mapFragment.getMapAsync(this);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        this.map = googleMap;

        if (this.userSettings.getUseDarkTheme()) {
            // DARK THEME map
            map.setMapStyle(MapStyleOptions.loadRawResourceStyle(this.getApplicationContext(), R.raw.map_dark_style));
        }

        final long now = System.currentTimeMillis();
        final long yesterday = now - (1000 * 60 * 60 * 24);
        var async = this.beaconRepo.getLocationsFor(this.beaconId, yesterday, now)
                .subscribe(reports -> {
                    this.runOnUiThread(() -> this.updateLocationDataPoints(reports));
                }, error -> Log.e(TAG, "Failed to retrieve past locations for beaconId=" + this.beaconId));
    }

    private void setupBottomSheetHeights() {
        View bottomSheetChildView = this.findViewById(R.id.view_history_bottom_sheet_layout);
        ViewGroup.LayoutParams params = bottomSheetChildView.getLayoutParams();
        BottomSheetBehavior<View> bottomSheetBehavior = BottomSheetBehavior.from(bottomSheetChildView);
        if (params != null) {
            params.height = MATCH_PARENT;
            bottomSheetChildView.setLayoutParams(params);
            bottomSheetBehavior.setFitToContents(false);
            bottomSheetBehavior.setHalfExpandedRatio(HISTORY_SHEET_HALF_EXPANDED_RATIO);
        }
    }

    private synchronized void updateLocationDataPoints(List<BeaconLocationReport> reports) {
        this.locations.addAll(reports);

        // Update # of datapoints text
        TextView numberOfDatapoints = this.findViewById(R.id.history_datapoints_text);
        numberOfDatapoints.setText(this.getString(R.string.x_data_points, this.locations.size()));

        // notify that list of items has been inserted
        this.historyItemsAdapter.notifyItemRangeInserted(0, this.locations.size());

        // draw datapoints on map
        var coords = this.locations.stream().map(rep -> new LatLng(rep.getLatitude(), rep.getLongitude())).collect(Collectors.toList());

        var options = new PolylineOptions()
                .color(Color.BLUE)
                .width(6f)
                .clickable(true)
                .addAll(coords);

        var mutablePolyLine = this.map.addPolyline(options);
    }
}