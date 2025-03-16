package dev.wander.android.airtagforall;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED;
import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_DRAGGING;
import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HALF_EXPANDED;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.location.Geocoder;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import dev.wander.android.airtagforall.data.model.BeaconInformation;
import dev.wander.android.airtagforall.data.model.BeaconLocationReport;
import dev.wander.android.airtagforall.databinding.ActivityHistoryViewBinding;
import dev.wander.android.airtagforall.db.datastore.UserSettingsDataStore;
import dev.wander.android.airtagforall.db.repo.BeaconRepository;
import dev.wander.android.airtagforall.db.repo.UserSettingsRepository;
import dev.wander.android.airtagforall.db.repo.model.UserSettings;
import dev.wander.android.airtagforall.db.room.AirTag4AllDatabase;
import dev.wander.android.airtagforall.db.util.BeaconCombinerUtil;
import dev.wander.android.airtagforall.python.PythonAppleService;
import dev.wander.android.airtagforall.ui.history.HistoryItemsAdapter;
import dev.wander.android.airtagforall.util.parse.BeaconDataParser;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class HistoryViewActivity extends AppCompatActivity implements OnMapReadyCallback {
    private static final String TAG = HistoryViewActivity.class.getSimpleName();

    private static final float HISTORY_SHEET_HALF_EXPANDED_RATIO = 0.4f;

    private static final float LINE_WIDTH = 16f;
    private static final float OUTLINE_WIDTH = 22f;

    private static final int FOCUS_PADDING = 120;

    private static final long DAY_IN_MS = 1000 * 60 * 60 * 24;

    private static final long SEVEN_DAYS_IN_MS = DAY_IN_MS * 7;

    private GoogleMap map;

    private BeaconRepository beaconRepo;
    private UserSettingsRepository userSettingsRepo;
    private PythonAppleService appleService;

    private Geocoder geocoder = null;

    private UserSettings userSettings;

    private String beaconId;

    private double defaultLatitude;
    private double defaultLongitude;
    private float defaultZoom;

    private BeaconInformation beaconInformation;

    private List<BeaconLocationReport> locations = new ArrayList<>();

    private HistoryItemsAdapter historyItemsAdapter;

    private int daysBack = 0;
    private long currentBeginningOfDay = -1;

    private Polyline currentHistoryLineOutline = null;
    private Polyline currentHistoryLine = null;
    private Marker singleCoordMarker = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        this.beaconId = getIntent().getStringExtra("beaconId");
        Log.d(TAG, "Showing history view for beaconId=" + this.beaconId);

        this.defaultLatitude = intent.getDoubleExtra("lat", 0.0f);
        this.defaultLongitude = intent.getDoubleExtra("lon", 0.0f);
        this.defaultZoom = intent.getFloatExtra("zoom", 16.0f);

        this.beaconRepo = new BeaconRepository(
                AirTag4AllDatabase.getInstance(getApplicationContext()));

        this.userSettingsRepo = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.getApplicationContext()));

        this.appleService = PythonAppleService.getInstance();

        this.geocoder = new Geocoder(this.getApplicationContext(), Locale.getDefault());

        this.userSettings = this.userSettingsRepo.getUserSettings();

        this.beaconInformation = this.beaconRepo.getById(this.beaconId)
                .flatMap(data -> BeaconDataParser.parseAsync(List.of(data)))
                .map(items -> items.get(0))
                .blockingFirst();

        ActivityHistoryViewBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_history_view);
        binding.setHandleClickBack(this::finish);
        binding.setPageTitle(this.getString(R.string.history_x, this.getCurrentBeaconName()));

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        ViewGroup content = this.findViewById(R.id.history_bottomsheet_coordinator_layout);
        content.addView(getLayoutInflater().inflate(R.layout.view_history_bottom_sheet, content, false));
        this.setupBottomSheet();

        this.historyItemsAdapter = new HistoryItemsAdapter(this.geocoder, this.locations, this.userSettings);
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

        // move to same position that we left when we went to the history page from the main page
        this.map.moveCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(this.defaultLatitude, this.defaultLongitude), this.defaultZoom));

        this.fetchAndUpdateDataForCurrentDay();
    }

    private void fetchAndUpdateDataForCurrentDay() {
        final MaterialButton moveLeftButton = this.findViewById(R.id.history_move_left_button);
        final MaterialButton moveRightButton = this.findViewById(R.id.history_move_right_button);
        moveLeftButton.setClickable(false); // temp disable
        moveRightButton.setClickable(false); // temp disable

        final LinearLayout dataReportContainer = this.findViewById(R.id.history_data_overview_top);
        final LinearLayout errorMessageContainer = this.findViewById(R.id.history_error_message);

        final LinearProgressIndicator historyLoadingProgress = this.findViewById(R.id.history_loading_progress_indicator);

        final long now = System.currentTimeMillis();

        final long nowWithDaysBack = now - (this.daysBack * DAY_IN_MS);
        // We are using this thing to solve the missing API version support: https://developer.android.com/studio/write/java8-support#library-desugaring
        @SuppressLint("NewApi") final long beginningOfDay = Instant.ofEpochMilli(nowWithDaysBack).atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        this.currentBeginningOfDay = beginningOfDay;

        if (!this.hasReportsForDayLocally(beginningOfDay)) {
            // show if we will be loading for a bit.
            // if it is available locally, then don't show because it will update very quickly!
            historyLoadingProgress.show();
        }

        // fetch & do
        var async = this.getReportsForDay(beginningOfDay)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(items -> {
                        historyLoadingProgress.hide();

                        this.setRetryButtonLoading(true);
                        dataReportContainer.setVisibility(VISIBLE);
                        errorMessageContainer.setVisibility(GONE);

                        moveLeftButton.setClickable(true);
                        if (moveRightButton.getVisibility() != INVISIBLE) moveRightButton.setClickable(true); // allow navigation

                        this.updateForNewLocationsList(items);
                    },
                    error -> {
                        Log.e(TAG, "Failure to fetch location reports in time range: " + beginningOfDay + " - " + (beginningOfDay + DAY_IN_MS), error);
                        historyLoadingProgress.hide();

                        this.setRetryButtonLoading(true);
                        dataReportContainer.setVisibility(GONE);
                        errorMessageContainer.setVisibility(VISIBLE);

                        moveLeftButton.setClickable(true);
                        if (moveRightButton.getVisibility() != INVISIBLE) moveRightButton.setClickable(true); // allow navigation
                    });
    }

    private static final Map<String, List<BeaconLocationReport>> REPORTS_CACHE = new ConcurrentHashMap<>();

    private static String createReportsForDayCacheKey(final String beaconId, final long beginningOfDay) {
        return String.format(Locale.ROOT, "%d-%s", beginningOfDay, beaconId);
    }

    private boolean hasReportsForDayLocally(final long beginningOfDay) {
        final String cacheKey = createReportsForDayCacheKey(beaconId, beginningOfDay);
        return REPORTS_CACHE.containsKey(cacheKey);
    }

    private Observable<List<BeaconLocationReport>> getReportsForDay(final long beginningOfDay) {
        final boolean isForToday = this.daysBack == 0;
        final String cacheKey = createReportsForDayCacheKey(beaconId, beginningOfDay);
        final long endOfDay = beginningOfDay + DAY_IN_MS;

        if (REPORTS_CACHE.containsKey(cacheKey)) {
            // retrieve from cache if fetched in the past already
            Log.d(TAG, "Returned location data for beaconId=" + beaconId + " from cache for time range: " + beginningOfDay + "-" + endOfDay);
            return Observable.just(Objects.requireNonNull(REPORTS_CACHE.get(cacheKey)));
        }

        // otherwise actually attempt to fetch
        var reqData = Map.of(this.beaconId, this.beaconInformation.getOwnedBeaconPlistRaw());
        var asyncReq = this.appleService.getReportsBetween(reqData, beginningOfDay, endOfDay);

        final long now = System.currentTimeMillis();
        if (beginningOfDay < now - SEVEN_DAYS_IN_MS) {
            // we have a small issue here: the api does not seem to return data older than 7 days.
            // so if we are trying to fetch anything older than 7 days,
            // try to retrieve it from our local DB/cache, too.

            var asyncDB = this.beaconRepo.getLocationsFor(beaconId, beginningOfDay, endOfDay);

            Log.d(TAG, "Going to perform a merged localdb + remote fetch for beaconId=" + beaconId + " location data in range: " + beginningOfDay + "-" + endOfDay);
            return Observable.zip(
                    // try to fetch remotely anyways and combine uniquely later
                    asyncReq.flatMap(this.beaconRepo::storeToLocationCache).map(locations -> locations.get(beaconId)),
                    // also try to fetch from DB for same time range
                    asyncDB,
                    (locationsRemote, locationsLocal) -> {
                        Log.d(TAG, "Got " + locationsRemote.size() + " locations from Apple server and got " + locationsLocal.size() + " locations from local DB for beaconId" + beaconId);

                        // merge both lists for unique events
                        var mergedList = BeaconCombinerUtil.combineAndSort(beaconId, locationsRemote, locationsLocal);
                        Log.d(TAG, "Final merged location history list has " + mergedList.size() + " items!");

                        return mergedList;
                    }).doOnNext(locations -> {
                        // Don't cache the current day (it could still update)!
                        if (!isForToday) {
                            REPORTS_CACHE.put(cacheKey, locations);
                        }
                    })
                    .subscribeOn(Schedulers.computation()); // cache this combination, there will be no more updates at this point
        }

        Log.d(TAG, "Going to perform a fresh fetch for beaconId=" + beaconId + " location data in range: " + beginningOfDay + "-" + endOfDay);
        return asyncReq
                .doOnNext(locations -> {
                    // Don't cache the current day (it could still update)!
                    if (!isForToday) {
                        REPORTS_CACHE.put(cacheKey, locations.get(beaconId));
                    }
                })
                .flatMap(this.beaconRepo::storeToLocationCache)
                .map(locations -> locations.get(beaconId))
                .subscribeOn(Schedulers.computation());
    }


    private synchronized void updateForNewLocationsList(final List<BeaconLocationReport> newReports) {
        final int oldNumItems = this.locations.size();
        if (oldNumItems > 0) {
            // cleanup old items
            this.locations.clear();
            this.historyItemsAdapter.notifyItemRangeRemoved(0, oldNumItems);
        }

        this.cleanupOldLines();

        TextView currentRangeText = this.findViewById(R.id.history_drawer_title);
        //MaterialButton moveLeftButton = this.findViewById(R.id.history_move_left_button);
        MaterialButton moveRightButton = this.findViewById(R.id.history_move_right_button);

        if (this.daysBack == 0) {
            currentRangeText.setText(R.string.today);
            moveRightButton.setVisibility(INVISIBLE);
        } else if (this.daysBack == 1) {
            currentRangeText.setText(R.string.yesterday);
            moveRightButton.setVisibility(VISIBLE);
        } else {
            var format = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEE, dd MMM yyyy");
            var timestampFormat = new SimpleDateFormat(format, Locale.getDefault());
            currentRangeText.setText(timestampFormat.format(new Date(this.currentBeginningOfDay)));

            moveRightButton.setVisibility(VISIBLE);
        }

        this.drawNewLocationList(newReports);
    }

    private void cleanupOldLines() {
        if (this.currentHistoryLine != null) {
            this.currentHistoryLine.remove();
            this.currentHistoryLine = null;
        }
        if (this.currentHistoryLineOutline != null) {
            this.currentHistoryLineOutline.remove();
            this.currentHistoryLineOutline = null;
        }
        if (this.singleCoordMarker != null) {
            this.singleCoordMarker.remove();
            this.singleCoordMarker = null;
        }
    }

    private void setupBottomSheet() {
        View bottomSheetChildView = this.findViewById(R.id.view_history_bottom_sheet_layout);
        ViewGroup.LayoutParams params = bottomSheetChildView.getLayoutParams();
        BottomSheetBehavior<View> bottomSheetBehavior = BottomSheetBehavior.from(bottomSheetChildView);
        if (params != null) {
            params.height = MATCH_PARENT;
            bottomSheetChildView.setLayoutParams(params);
            bottomSheetBehavior.setFitToContents(false);
            bottomSheetBehavior.setHalfExpandedRatio(HISTORY_SHEET_HALF_EXPANDED_RATIO);
        }

        bottomSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {}

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                if (map == null) return;

                int height = bottomSheet.getHeight();
                int offset = (int)((height - bottomSheetBehavior.getPeekHeight()) * slideOffset) + bottomSheetBehavior.getPeekHeight();

                map.setPadding(0, 0, 0, offset);
            }
        });

        // setup menu buttons
        final MaterialButton moveLeftButton = this.findViewById(R.id.history_move_left_button);
        final MaterialButton moveRightButton = this.findViewById(R.id.history_move_right_button);
        moveLeftButton.setOnClickListener(v -> {
            this.daysBack++;
            if (this.daysBack > 0) {
                moveRightButton.setVisibility(VISIBLE);
                moveRightButton.setClickable(true);
            } else {
                moveRightButton.setVisibility(INVISIBLE);
                moveRightButton.setClickable(false);
            }
            this.fetchAndUpdateDataForCurrentDay();
        });
        moveRightButton.setOnClickListener(v -> {
            if (this.daysBack <= 1) {
                moveRightButton.setVisibility(INVISIBLE);
                moveRightButton.setClickable(false);
            }
            this.daysBack--;
            this.fetchAndUpdateDataForCurrentDay();
        });

        final MaterialButton retryButton = this.findViewById(R.id.history_fetch_retry_button);
        retryButton.setOnClickListener(v -> this.handleRetryHistoryFetch());

        final LinearProgressIndicator historyLoadingProgress = this.findViewById(R.id.history_loading_progress_indicator);
        historyLoadingProgress.hide();
    }

    private void handleRetryHistoryFetch() {
        this.setRetryButtonLoading(false);
        this.fetchAndUpdateDataForCurrentDay();
    }

    private void setRetryButtonLoading(boolean isComplete) {
        final MaterialButton retryButton = this.findViewById(R.id.history_fetch_retry_button);

        if (!isComplete) {
            retryButton.setClickable(false); // temporarily disable
        } else {
            retryButton.setClickable(true);
        }
    }

    private String getCurrentBeaconName() {
        if (this.beaconInformation.getEmoji() != null && !this.beaconInformation.getEmoji().isBlank()) {
            return String.format("%s %s", this.beaconInformation.getEmoji(), this.beaconInformation.getName());
        }
        if (this.beaconInformation.getName() != null && !this.beaconInformation.getName().isBlank()) {
            return this.beaconInformation.getName();
        }
        return this.beaconInformation.getBeaconId();
    }

    private synchronized void drawNewLocationList(List<BeaconLocationReport> reports) {
        this.locations.addAll(reports);

        // Update # of datapoints text
        TextView numberOfDatapoints = this.findViewById(R.id.history_datapoints_text);
        numberOfDatapoints.setText(this.getString(R.string.x_data_points, this.locations.size()));

        // notify that list of items has been inserted
        this.historyItemsAdapter.notifyItemRangeInserted(0, this.locations.size());

        Double latMax = null;
        Double latMin = null;
        Double lonMax = null;
        Double lonMin = null;

        // draw datapoints on map
        var coords = this.locations.stream().map(rep -> new LatLng(rep.getLatitude(), rep.getLongitude()))
                .collect(Collectors.toList());

        for (var coord : coords) {
            if (latMax == null || latMax < coord.latitude) latMax = coord.latitude;
            if (latMin == null || latMin > coord.latitude) latMin = coord.latitude;
            if (lonMax == null || lonMax < coord.longitude) lonMax = coord.longitude;
            if (lonMin == null || lonMin > coord.longitude) lonMin = coord.longitude;
        }

        this.drawNewLines(coords);

        if (!coords.isEmpty()) {
            this.animateCameraToLines(latMin, latMax, lonMin, lonMax);
        }
    }

    private void drawNewLines(final List<LatLng> coords) {
        final int numCoords = coords.size();
        if (numCoords > 1) {
            var optionsOutlineLine = new PolylineOptions()
                    .color(this.getColor(R.color.maps_line_outline))
                    .width(OUTLINE_WIDTH)
                    .clickable(false)
                    .addAll(coords);
            this.currentHistoryLineOutline = this.map.addPolyline(optionsOutlineLine);

            var optionsPrimaryLine = new PolylineOptions()
                    .color(this.getColor(R.color.maps_line_primary))
                    .width(LINE_WIDTH)
                    .clickable(true)
                    .addAll(coords);

            this.currentHistoryLine = this.map.addPolyline(optionsPrimaryLine);
        } else if (numCoords == 1) {
            // if we just had a single item, then draw a single marker
            var markerOptions = new MarkerOptions()
                    .position(coords.get(0));
            this.singleCoordMarker = this.map.addMarker(markerOptions);
        }
    }

    private void animateCameraToLines(final Double latMin, final Double latMax, final Double lonMin, final Double lonMax) {
        this.map.animateCamera(CameraUpdateFactory.newLatLngBounds(new LatLngBounds(
                new LatLng(latMin, lonMin),
                new LatLng(latMax, lonMax)
        ), FOCUS_PADDING));
    }
}