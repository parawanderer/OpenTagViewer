package dev.wander.android.opentagviewer;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;


import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Pair;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.GoogleMap.OnMapClickListener;
import com.google.android.libraries.places.api.Places;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.data.model.UserMapCameraPosition;
import dev.wander.android.opentagviewer.databinding.ActivityMapsBinding;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserCacheDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.AppleUserData;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.repo.BeaconRepository;
import dev.wander.android.opentagviewer.db.repo.model.ImportData;
import dev.wander.android.opentagviewer.db.util.BeaconCombinerUtil;
import dev.wander.android.opentagviewer.python.PythonAppleService;
import dev.wander.android.opentagviewer.python.PythonAuthService;
import dev.wander.android.opentagviewer.db.repo.UserDataRepository;
import dev.wander.android.opentagviewer.ui.maps.TagCardHelper;
import dev.wander.android.opentagviewer.ui.maps.TagListSwiperHelper;
import dev.wander.android.opentagviewer.util.LogCollectorUtil;
import dev.wander.android.opentagviewer.util.MapUtils;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.util.android.PermissionUtil;
import dev.wander.android.opentagviewer.ui.maps.VectorImageGeneratorUtil;
import dev.wander.android.opentagviewer.util.parse.AppleZipImporterUtil;
import dev.wander.android.opentagviewer.util.parse.BeaconDataParser;
import dev.wander.android.opentagviewer.util.parse.ZipImporterException;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.Data;

/**
 * TODO: this whole thing is a bit of a godclass. Decouple it.
 */
public class MapsActivity extends AppCompatActivity implements OnMapReadyCallback, OnMapClickListener, GoogleMap.OnMarkerClickListener, ActivityCompat.OnRequestPermissionsResultCallback {
    private static final String TAG = MapsActivity.class.getSimpleName();

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;

    private static final int GOOGLE_LOGO_PADDING_BOTTOM_PX = 40;

    private static final int HOURS_TO_GO_BACK_24H = 24;

    private static final long WAIT_BEFORE_REFETCH = 1000 * 60; // 1 MINUTE

    private static final long ONE_HOUR_IN_MS = 1000 * 60 * 60; // 1 HOUR

    private static final float CAMERA_ON_MAP_INITIAL_ZOOM = 16.0f; // see: https://developers.google.com/maps/documentation/android-sdk/views#zoom

    private static final float MARKER_ZINDEX_DEFAULT = 0.0f;

    private static final float MARKER_ZINDEX_TOP = 10.0f;

    private GoogleMap map;

    private ActivityMapsBinding binding;

    private BeaconRepository beaconRepo;

    private UserSettingsRepository userSettingsRepo;

    private UserAuthRepository userAuthRepo;

    private UserDataRepository userDataRepository;

    private PythonAppleService appleService = null;

    private UserSettings userSettings;

    private FusedLocationProviderClient fusedLocationClient = null;

    private Geocoder geocoder = null;

    private final Map<String, BeaconData> beacons = new ConcurrentHashMap<>();

    private final Map<String, List<BeaconLocationReport>> beaconLocations = new ConcurrentHashMap<>();

    private final Map<String, Marker> currentMarkers = new ConcurrentHashMap<>();

    private Marker lastFocusedMarker = null;

    private final Map<String, FrameLayout> dynamicCardsForTag = new ConcurrentHashMap<>();

    private boolean initialFetchComplete = false;
    private long last24HHistoryFetchAt = 0L;

    private TagListSwiperHelper tagListSwiperHelper = null;

    private final Handler refreshSchedulerHandler = new Handler();
    private Runnable nextLocationRefreshTask = null;

    private Optional<UserMapCameraPosition> lastCameraPositionOnLoad;

    private int windowWidth = 0;

    private final ActivityResultLauncher<Intent> pickZipActivityLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            (ActivityResult result) -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    this.onImportFilePicked(data);
                }
            }
    );

    private final ActivityResultLauncher<Intent> settingsEditActivityLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            (ActivityResult result) -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null && data.getBooleanExtra("requestSendToLogin", false)) {
                        this.handleSendToLogin();
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> deviceListActivityLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            (ActivityResult result) -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null && data.getBooleanExtra("isDeviceListChanged", false)) {
                        this.handleDeviceListChanged();
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> deviceInfoActivityLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            (ActivityResult result) -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null && (
                            data.getStringExtra("deviceWasRemoved") != null)
                            || data.getStringExtra("deviceWasChanged") != null) {
                        this.handleDeviceListChanged();
                    }
                }
            }
    );

    private ActivityResultLauncher<Intent> exportLogsActivityLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            (ActivityResult result) -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    this.onExportLogsToLocationPicked(data);
                }
            }
    );

    private static void run() {

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        OpenAirTagApplication app = (OpenAirTagApplication) this.getApplication();
        app.setupTheme();

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        this.checkApiKey();

        this.binding = ActivityMapsBinding.inflate(getLayoutInflater());
        setContentView(this.binding.getRoot());

        this.userSettingsRepo = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.getApplicationContext()));

        this.userSettings = userSettingsRepo.getUserSettings();

        this.userDataRepository = new UserDataRepository(
                UserCacheDataStore.getInstance(getApplicationContext())
        );

        this.userAuthRepo = new UserAuthRepository(
                UserAuthDataStore.getInstance(getApplicationContext()),
                new AppCryptographyUtil());

        this.beaconRepo = new BeaconRepository(
                OpenTagViewerDatabase.getInstance(getApplicationContext()));

        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        Places.initialize(this.getApplicationContext(), BuildConfig.MAPS_API_KEY);
        this.geocoder = new Geocoder(this.getApplicationContext(), Locale.getDefault());

        this.setupTagScrollArea();

        var async = this.getLastCameraPosition()
            .subscribe(pos -> {
                Log.d(TAG, "Got previous camera position to reset us to: " + pos);

                pos.ifPresent(userMapCameraPosition -> this.map.moveCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng(userMapCameraPosition.getLat(), userMapCameraPosition.getLon()),
                        userMapCameraPosition.getZoom()
                )));

            }, error -> Log.e(TAG, "Failed to get last camera position!", error));

        this.handleAuthAndShowDevices();

        this.windowWidth = this.getResources().getDisplayMetrics().widthPixels;

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
    }



    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        map = googleMap;

        map.setOnMapClickListener(this);
        map.setOnMarkerClickListener(this);

        map.setPadding(0, 0, 0, GOOGLE_LOGO_PADDING_BOTTOM_PX);
        // We don't want to use the default button. We have a custom button
        map.getUiSettings().setMyLocationButtonEnabled(false);
        map.getUiSettings().setRotateGesturesEnabled(false); // no rotation (mostly bc very annoying to reset)
        map.getUiSettings().setCompassEnabled(false); // not needed due to no rotation being allowed
        map.getUiSettings().setMapToolbarEnabled(false); // we have a custom button for this

        if (this.userSettings.hasDarkThemeEnabled()) {
            // DARK THEME map
            map.setMapStyle(MapStyleOptions.loadRawResourceStyle(this.getApplicationContext(), R.raw.map_dark_style));
        }

        this.enableMyLocation(false);
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (this.map != null) {
            var pos = this.map.getCameraPosition();
            var async = this.userDataRepository.storeLastCameraPosition(
                    UserMapCameraPosition.builder()
                            .zoom(pos.zoom)
                            .lat(pos.target.latitude)
                            .lon(pos.target.longitude)
                            .build()
            ).subscribe(
                    success -> Log.d(TAG, "Success storing last camera position!"),
                    error -> Log.e(TAG, "Error storing last camera position!", error));

            // cleanup location refresh task
            refreshSchedulerHandler.removeCallbacks(this.nextLocationRefreshTask);
            this.nextLocationRefreshTask = null;
        }
    }

    @Override
    public void onMapClick(LatLng point) {
        Log.i(TAG, "tapped, point=" + point);
        // TODO: hide UI elements when this occurs!
    }

    @Override
    protected void onResume() {
        super.onResume();

         // TODO: when a user changes their anisette URL in settings and returns here, this should be able to deal with querying the new URL

        this.refreshIfAllowed();
        this.reSchedulePeriodicTagLocationRefresher();
    }

    private void reSchedulePeriodicTagLocationRefresher() {
        // (re-)schedule tag location refresher
        if (this.nextLocationRefreshTask != null) {
            refreshSchedulerHandler.removeCallbacks(this.nextLocationRefreshTask);
        }
        this.nextLocationRefreshTask = () -> {
            refreshSchedulerHandler.postDelayed(this.nextLocationRefreshTask, WAIT_BEFORE_REFETCH);
            this.refreshIfAllowed();
        };
        refreshSchedulerHandler.postDelayed(this.nextLocationRefreshTask, WAIT_BEFORE_REFETCH);
    }

    private void refreshIfAllowed() {
        if (!this.isAppleServiceInitialised()) {
            Log.d(TAG, "AppleService was not initialised yet, so we can't refresh");
            return;
        }

        if (!this.initialFetchComplete) {
            Log.d(TAG, "Skipping refresh due to not having fully initialised yet");
            return;
        }

        final long now = System.currentTimeMillis();
        if (now < this.last24HHistoryFetchAt + WAIT_BEFORE_REFETCH) {
            Log.d(TAG, String.format(
                    "We will not re-fetch Beacon history as less than %d ms (actual: %d ms) have passed since the last history fetch",
                    WAIT_BEFORE_REFETCH,
                    (now - this.last24HHistoryFetchAt)
            ));
            return;
        }

        Log.d(TAG, "Performing automatic scheduled refresh of data for all tags...");
        this.fetchAndUpdateCurrentBeacons();
        Log.d(TAG, "Automatic scheduled refresh complete! Next automatic refresh will be in " + WAIT_BEFORE_REFETCH + " ms");
        //Toast.makeText(this, "Performing automatic periodic refresh...", LENGTH_SHORT).show();
    }

    public void onClickMoreSettings(View view)
    {
        Log.d(TAG, "Global more button was clicked");
        ImageButton bttn = findViewById(R.id.button_more_settings);

        var popupMenu = new PopupMenu(this, bttn);
        popupMenu.getMenuInflater().inflate(R.menu.global_map_more_menu, popupMenu.getMenu());

        // This item is conditionally visible (non-technical users probably don't need this)
        MenuItem exportLogsItem = popupMenu.getMenu().findItem(R.id.export_logs);
        UserSettings userSettings = this.getRefreshUserSettings();
        final boolean shouldShowExport = userSettings.getEnableDebugData() != null && userSettings.getEnableDebugData();
        exportLogsItem.setVisible(shouldShowExport);

        popupMenu.setOnMenuItemClickListener(menuItem -> {
            Log.d(TAG, "Menu option " + menuItem.getTitle() + " was selected");

            final int itemId = menuItem.getItemId();

            if (itemId == R.id.do_import) {
                this.handleImport();
            } else if (itemId == R.id.settings) {
                this.showSettingsPage();
            } else if (itemId == R.id.information) {
                this.showInformationPage();
            } else if (itemId == R.id.my_devices) {
                this.showMyDevicesPage();
            } else if (itemId == R.id.export_logs) {
                this.handleExportLogs();
            }

            return true;
        });

        popupMenu.show();
    }

    public void onClickMyLocation(View view) {
        Log.d(TAG, "My location button was clicked");
        this.animateCameraToMyLocation();
    }

    private void animateCameraToMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED) {

            this.fusedLocationClient.getLastLocation()
                    .addOnSuccessListener(this, location -> {
                        // https://developer.android.com/develop/sensors-and-location/location/retrieve-current#last-known
                        if (location == null) {
                            Log.w(TAG, "Last location was returned as null!");
                            return;
                        }

                        Log.d(TAG, "Navigating to current user position on the map...");
                        this.map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                new LatLng(location.getLatitude(), location.getLongitude()),
                                CAMERA_ON_MAP_INITIAL_ZOOM));
                    });

        } else {
            Log.e(TAG, "Clicked on 'My Location' button while not having Location permissions. This shouldn't happen as this button should have been hidden in this case!");
        }
    }

    public void onClickNavigateTo(View view) {
        Log.d(TAG, "Navigate to button was clicked");

        if (this.dynamicCardsForTag.isEmpty()) {
            Log.w(TAG, "Unexpected: managed to click navigateTo even though there was no tags listed! Expected this button to be disabled.");
            return;
        }

        final String beaconId = this.tagListSwiperHelper.getCurrentPrimaryCard();
        if (beaconId == null) {
            Log.w(TAG, "No current card was found!");
            return;
        }

        final List<BeaconLocationReport> locations = Objects.requireNonNull(this.beaconLocations.get(beaconId));
        if (locations.isEmpty()) {
            Log.w(TAG, "Can't navigate to a beacon that has no locations!");
            return;
        }
        final BeaconLocationReport lastLocation = locations.get(locations.size() - 1);
        Uri uri = Uri.parse(String.format(Locale.ROOT, "geo:%.7f,%.7f?q=%.7f,%.7f", lastLocation.getLatitude(), lastLocation.getLongitude(), lastLocation.getLatitude(), lastLocation.getLongitude()));
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, uri);
        mapIntent.setPackage("com.google.android.apps.maps");
        if (mapIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(mapIntent);
        } else {
            Log.e(TAG, "Could not start maps activity for currently visible tag!");
        }
    }

    public void askForLocationWithRationale() {
        var explanationDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.enable_location_permissions)
                .setMessage(R.string.location_permissions_will_only_be_used_to_visualise_on_map_text)
                .setIcon(R.drawable.my_location_24px)
                .setNegativeButton(R.string.decline, null)
                .setPositiveButton(R.string.accept, (dialog, which) -> {
                    Log.d(TAG, "Accept Enable Location Permissions button clicked. Now requesting permissions...");
                    this.performNativePermissionRequest();
                })
                .show();
    }

    private void showInformationPage() {
        Log.d(TAG, "Show information page");

        // navigate to information page
        Intent intent = new Intent(this, InformationActivity.class);
        startActivity(intent);
    }

    private void showSettingsPage() {
        Log.d(TAG, "Show settings page clicked");

        // navigate to settings page
        Intent intent = new Intent(this, SettingsActivity.class);
        settingsEditActivityLauncher.launch(intent);
    }

    private void showMyDevicesPage() {
        Log.d(TAG, "Show my devices page clicked");

        Intent intent = new Intent(this, MyDevicesListActivity.class);
        deviceListActivityLauncher.launch(intent);
    }

    private void onImportFilePicked(Intent data) {
        Log.d(TAG, "File has been picked");

        // combine them into the current list of beaconLocations & show this list
        var async = this.extractImportedData(data)
            .flatMap(this.beaconRepo::addNewImport)
            .doOnNext((importData) -> {
                this.runOnUiThread(() -> {
                    Toast.makeText(
                            this,
                            this.getString(R.string.loading_location_data_for_x_new_imported_devices, importData.getOwnedBeacons().size()),
                            LENGTH_LONG).show();
                });
            })
            .publish(storedBeacons ->
            /*
             * Note: this is a bit of a fork-join: https://stackoverflow.com/questions/48015796/using-rxjava-to-fork-into-tasks-and-combine-results
             * May be too much of an optimization, but I figured I might as well try out the extent
             * of what you can do with these RXJava observables as far as multithreading goes...
             */
                Observable.zip(
                        storedBeacons.flatMap(beacons ->
                            this.fetchLastReports(beacons.getOwnedBeacons().stream()
                                    .collect(Collectors.toMap(b -> b.id, b -> b.content)), HOURS_TO_GO_BACK_24H)
                        )
                        .doOnNext(this::addBeaconLocationsToCurrent),
                        storedBeacons.flatMap(beacons -> BeaconDataParser.parseAsync(BeaconCombinerUtil.combine(beacons)))
                        .doOnNext(this::addBeaconToCurrent),
                        Pair::create
                )
            )
            .flatMapCompletable((__) -> this.updateBeaconGeocodings())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(() -> {
                this.showLastDeviceLocations();
                Log.i(TAG, "Finished visualising new location reports!");
            }, error -> {
                Log.e(TAG, "Error occurred while importing new devices!", error);
                Toast.makeText(
                        this,
                        R.string.error_occurred_while_importing_new_devices_try_to_restart_the_app_and_retry,
                        LENGTH_LONG).show();
            });
    }

    private void onExportLogsToLocationPicked(@lombok.NonNull Intent data) {
        Log.d(TAG, "Export target location picked");

        Uri writeTarget = data.getData();
        if (writeTarget == null) {
            Log.w(TAG, "No write target given! Stopping here");
            return;
        }

        String logLines = LogCollectorUtil.getLastLogs();

        try (OutputStream os = this.getContentResolver().openOutputStream(writeTarget)) {
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os));
            bw.write(logLines);
            bw.flush();
            bw.close();

            Log.d(TAG, "Logs export to " + writeTarget + " complete!");

            Toast.makeText(
                    this,
                    R.string.log_file_has_been_exported_successfully,
                    LENGTH_LONG
            ).show();

        } catch (IOException e) {
            Log.e(TAG, "Failed to save file", e);
            Toast.makeText(
                    this,
                    R.string.failed_to_export_log_file,
                    LENGTH_LONG
            ).show();
        }
    }

    private Observable<ImportData> extractImportedData(Intent data) {
        return Observable.fromCallable(() -> {
            try {
                Uri zipFileUri = data.getData();
                assert zipFileUri != null;

                var util = new AppleZipImporterUtil(this.getApplicationContext());
                return util.extractZip(zipFileUri);

            } catch (ZipImporterException e) {
                Log.e(TAG, "Import or conversion error occurred while importing file", e);
                throw new RuntimeException(e);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error occurred while importing file into DB", e);
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.computation())
        .observeOn(AndroidSchedulers.mainThread());
    }

    private void handleImport() {
        // file picker to pick the zip
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/zip");
        pickZipActivityLauncher.launch(intent);
    }

    private void handleExportLogs() {
        Log.i(TAG, "Requested to dump logs...");

        String fileName = String.format(
                Locale.ROOT,
                "%s-%d.log",
                this.getString(R.string.app_name),
                System.currentTimeMillis()
        );

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);

        exportLogsActivityLauncher.launch(intent);
    }

    public void onClickLocationHistory(View view) {
        Log.d(TAG, "The location history button was just clicked!");

        final String beaconId = this.dynamicCardsForTag.entrySet()
                .stream().filter(kvp -> kvp.getValue().findViewById(R.id.device_history_button_container) == view)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Click location history event was raised by a Beacon Device's card, but the beaconId could not be found for it!"));

        var pos = this.map.getCameraPosition();

        Log.d(TAG, "Going to the history page for beaconId=" + beaconId);
        Intent viewHistoryIntent = new Intent(this, HistoryViewActivity.class);
        viewHistoryIntent.putExtra("beaconId", beaconId);
        viewHistoryIntent.putExtra("lon", pos.target.longitude);
        viewHistoryIntent.putExtra("lat", pos.target.latitude);
        viewHistoryIntent.putExtra("zoom", pos.zoom);

        startActivity(viewHistoryIntent);
    }

    public void onClickRefresh(View view) {
        Log.d(TAG, "The refresh button was clicked");

        final String beaconId = this.dynamicCardsForTag.entrySet()
                .stream().filter(kvp -> kvp.getValue().findViewById(R.id.device_refresh_button_container) == view)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Click refresh event was raised by a Beacon Device's card, but the beaconId could not be found for it!"));

        final FrameLayout container = Objects.requireNonNull(this.dynamicCardsForTag.get(beaconId));
        TagCardHelper.toggleRefreshLoading(container, true);

        // we can now fetch for this Id only!
        var async = this.fetchLastReportsFor(
                beaconId,
                Objects.requireNonNull(this.beacons.get(beaconId)).getInfo().getOwnedBeaconPlistRaw(),
                1)
                .doOnNext(this::addBeaconLocationsToCurrent)
                .flatMapCompletable((__) -> this.updateBeaconGeocodings())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    Log.i(TAG, "Refreshed location data and markers for beaconId=" + beaconId + " on refresh button click");
                    TagCardHelper.toggleRefreshLoading(container, false);
                    this.showLastDeviceLocations();
                    //Toast.makeText(this, "Refreshed location data & markers for beaconId="+beaconId, LENGTH_SHORT).show();
                }, error -> {
                    Log.e(TAG, "Failed to refresh current location for beaconId=" + beaconId + " on refresh button click!");
                    TagCardHelper.toggleRefreshLoading(container, false);
                    //Toast.makeText(this, "Failed to refresh location for beaconId=" + beaconId, LENGTH_SHORT).show();
                });
    }

    public void onClickRing(View view) {
        Log.i(TAG, "The ring button was clicked");
    }

    public void onClickMoreForDevice(View view) {
        Log.d(TAG, "The more (device-level) button was clicked");

        final String beaconId = this.dynamicCardsForTag.entrySet()
                .stream().filter(kvp -> kvp.getValue().findViewById(R.id.device_more_button_container) == view)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Click more event was raised by a Beacon Device's card, but the beaconId could not be found for it!"));

        Intent deviceInfoIntent = new Intent(this, DeviceInfoActivity.class);
        deviceInfoIntent.putExtra("beaconId", beaconId);
        deviceInfoActivityLauncher.launch(deviceInfoIntent);
    }

    private void checkApiKey() {
        try {
            ApplicationInfo appInfo = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            Bundle bundle = Objects.requireNonNull(appInfo.metaData);

            String apiKey = bundle.getString("com.google.android.geo.API_KEY");

            if (apiKey == null || apiKey.isBlank() || apiKey.equals("DEFAULT_API_KEY")) {
                Toast.makeText(this, "API Key was not set in secrets.properties", LENGTH_SHORT).show();
                throw new RuntimeException("API Key was not set in secrets.properties");
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package name not found.", e);
            throw new RuntimeException("Error getting package info", e);
        } catch (NullPointerException e) {
            Log.e(TAG, "Error accessing meta-data.", e); // Handle the case where meta-data is completely missing
            throw new RuntimeException("Error accessing meta-data in manifest", e);
        }
    }

    private void handleAuthAndShowDevices() {
        var async = userAuthRepo.getUserAuth()
            .subscribe(this::getAppleUser,
            error -> {
                Log.e(TAG, "Error retrieving user auth data!", error);
            });
    }

    private void getAppleUser(Optional<AppleUserData> userAuth) {
        if (userAuth.isEmpty()) {
            this.finish();
            this.sendToLogin();
            return;
        }
        // else stay here & restore the account & get the user settings
        var userSettings = userSettingsRepo.getUserSettings();

        // Get Apple account
        var asyncAppleService = PythonAuthService.restoreAccount(userAuth.get(), userSettings.getAnisetteServerUrl())
            .map(appleAccount -> {
                this.appleService = PythonAppleService.setup(appleAccount);
                return this.appleService;
            });

        // get list of Beacons
        var asyncAllBeacons = this.beaconRepo.getAllBeacons()
                .flatMap(BeaconDataParser::parseAsync)
                .doOnNext(this::addBeaconToCurrent);

        // get list of cached (previously fetched) locations
        // (might be empty or might not be present for all of them)
        var asyncAllLocations = this.beaconRepo.getLastLocationsForAll();

        var asyncBeaconData = Observable.zip(asyncAllBeacons, asyncAllLocations, (allBeacons, allLatestLocations) -> {
            // temporarily show cached beacon locations until we get the new ones!
            this.addBeaconLocationsToCurrent(MapUtils.toListOfOne(allLatestLocations));
            return allBeacons;
        }).subscribeOn(Schedulers.computation())
        .observeOn(AndroidSchedulers.mainThread())
        .flatMap(allBeacons -> {
            // show the locations for all the devices that were already in the cache
            this.showLastDeviceLocations();
            TagCardHelper.toggleRefreshLoadingAll(this.dynamicCardsForTag, true);
            return this.updateBeaconGeocodings().andThen(Observable.just(allBeacons));
        });

        // initially show the cached locations (after we get those back from the DB)
        // afterwards try to fetch the latest location reports from the Apple servers
        // store those reports in the DB (cache) and then show the updated positions
        var asyncCombo = Observable.zip(asyncAppleService, asyncBeaconData, (service, beacons) ->
            // map to expected format:
            this.fetchLastReports(
                    beacons.stream().collect(Collectors.toMap(BeaconInformation::getBeaconId, BeaconInformation::getOwnedBeaconPlistRaw)))
        ).flatMap(o -> o)
        .doOnNext(this::addBeaconLocationsToCurrent)
        .flatMap(o -> this.updateBeaconGeocodings().andThen(Observable.just(o)))
        .subscribeOn(Schedulers.computation())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(lastReports -> {
            this.initialFetchComplete = true;
            //Toast.makeText(this.getApplicationContext(), "Yay, got last reports!", LENGTH_SHORT).show();
            TagCardHelper.toggleRefreshLoadingAll(this.dynamicCardsForTag, false);
            this.showLastDeviceLocations();
            Log.i(TAG, "Successfully retrieved latest reports!");
        }, error -> {
            this.initialFetchComplete = true;
            Log.e(TAG, "Error while restoring account and trying to get latest beacons", error);
            TagCardHelper.toggleRefreshLoadingAll(this.dynamicCardsForTag, false);
            //Toast.makeText(this.getApplicationContext(), "Error while trying to fetch data for beacons", LENGTH_SHORT).show();
            // this error just happens every now and then. It's no big deal, we will retry automatically eventually...
        });
    }

    private synchronized void addBeaconToCurrent(final List<BeaconInformation> newBeaconInformation) {
        newBeaconInformation.forEach(beacon -> {
            final String beaconId = beacon.getBeaconId();
            if (this.beacons.containsKey(beaconId)) {
                Log.d(TAG, "Replacing existing beacon info for beaconId=" + beaconId);
            }
            this.beacons.put(beaconId, new BeaconData(beacon, Collections.emptyList()));
        });
    }

    private synchronized void addBeaconLocationsToCurrent(final Map<String, List<BeaconLocationReport>> newItems) {
        for (var key: newItems.keySet()) {
            if (this.beaconLocations.containsKey(key)) {

                var newMergedList = BeaconCombinerUtil.combineAndSort(
                        key,
                        Objects.requireNonNull(this.beaconLocations.get(key)),
                        Objects.requireNonNull(newItems.get(key))
                );

                Log.d(TAG, String.format(
                        "Merged location history for beaconId=%s, which had %d items of location history, with %d new items of locationHistory to get a total of %d items of locationHistory",
                        key,
                        Objects.requireNonNull(this.beaconLocations.get(key)).size(),
                        Objects.requireNonNull(newItems.get(key)).size(),
                        newMergedList.size()
                ));

                // we need to merge and re-sort...
                this.beaconLocations.put(key, newMergedList);
            } else {
                Log.d(TAG, "Adding new location for beaconId=" + key);
                this.beaconLocations.put(key, newItems.get(key));
            }
        }
    }

    private Completable updateBeaconGeocodings() {
        return Observable.fromCallable(this::updateBeaconGeocodingsSync)
        .flatMapCompletable(o -> o)
        .subscribeOn(Schedulers.io());
    }

    private synchronized Completable updateBeaconGeocodingsSync() {
        var tasks = new ArrayList<Completable>();

        for (BeaconData beaconData : this.beacons.values()) {
            final String beaconId = beaconData.getInfo().getBeaconId();

            if (!this.beaconLocations.containsKey(beaconId)) {
                Log.d(TAG, "Can't update geocoding for beacon=" + beaconId + " because it contained no locations");
                continue;
            }

            List<BeaconLocationReport> locations = Objects.requireNonNull(this.beaconLocations.get(beaconId));

            if (locations.isEmpty()) {
                Log.d(TAG, "Did not reverse geocode the last location for beaconId=" + beaconId + " because it had no known locations");
                continue;
            }

            BeaconLocationReport lastLocation = locations.get(locations.size() - 1);
            final Double lastLat = Optional.ofNullable(beaconData.getLastGeocodingLocation()).map(pos -> pos.latitude).orElse(null);
            final Double lastLon = Optional.ofNullable(beaconData.getLastGeocodingLocation()).map(pos -> pos.longitude).orElse(null);
            if (lastLat != null && lastLat == lastLocation.getLatitude() && lastLon != null && lastLon == lastLocation.getLongitude()) {
                Log.d(TAG, "No need to update geocoding for beaconId=" + beaconId + " because previous geocoding is still valid (location has not updated since the last check)");
                continue;
            }

            Completable asyncTask = this.reverseGeocode(lastLocation.getLatitude(), lastLocation.getLongitude())
                    .doOnNext(geocodingForLocation -> {
                        Log.d(TAG, "Got new reverse geocoding data for beaconId=" + beaconId);
                        beaconData.setGeocoding(Optional.ofNullable(geocodingForLocation).orElse(Collections.emptyList()));
                        beaconData.setLastGeocodingLocation(new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude()));
                    })
                    .doOnError(err -> {
                        Log.e(TAG, "Error occurred while trying to reverse geocode!", err);
                        beaconData.setGeocoding(Collections.emptyList());
                        beaconData.setLastGeocodingLocation(null);
                    }).ignoreElements();

            tasks.add(asyncTask);
        }

        return Completable.merge(tasks).doOnComplete(() -> Log.d(TAG, "Finished updating reverse geocoding data!"))
                .subscribeOn(Schedulers.io());
    }

    private Observable<List<Address>> reverseGeocode(double latitude, double longitude) {
        return Observable.fromCallable(() -> this.geocoder.getFromLocation(latitude, longitude, 1))
            .subscribeOn(Schedulers.io());
    }

    private synchronized void showLastDeviceLocations() {
        for (BeaconData beaconData : this.beacons.values()) {
            BeaconInformation beacon = beaconData.getInfo();
            final String beaconId = beacon.getBeaconId();

            if (!this.beaconLocations.containsKey(beaconId)) {
                Log.d(TAG, "No location was currently know for beacon with id " + beaconId + ". It is being skipped.");
                continue;
            }

            List<BeaconLocationReport> locations = Objects.requireNonNull(this.beaconLocations.get(beaconId));

            if (locations.isEmpty()) {
                Log.d(TAG, "Did not get any location reports for beacon device with id " + beacon.getBeaconId() + ". This device will not be shown in the UI.");
                continue;
            }

            BeaconLocationReport lastLocation = locations.get(locations.size() - 1);
            this.showBeaconOnMap(beacon, lastLocation);
        }
        this.updateBeaconCards();
    }

    private synchronized void showBeaconOnMap(final BeaconInformation beacon, final BeaconLocationReport lastLocation) {
        final String beaconId = beacon.getBeaconId();
        final LatLng locationTag = new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude());

        if (this.currentMarkers.containsKey(beaconId)) {
            // remove the old marker
            Log.d(TAG, "Going to move the existing marker for beaconId=" + beaconId);
            var marker = Objects.requireNonNull(this.currentMarkers.get(beaconId));
            marker.setPosition(locationTag);
            return;
        }
        Log.d(TAG, "Going to add new marker for beaconId=" + beaconId);

        BitmapDescriptor icon;
        if (beacon.isEmojiFilled()) {
            icon = VectorImageGeneratorUtil.makeMarker(
                    getResources(),
                    beacon.getEmoji(),
                    getColor(R.color.md_theme_background));
        } else {
            icon = VectorImageGeneratorUtil.makeMarker(
                    getResources(),
                    R.drawable.apple,
                    getColor(R.color.md_theme_background),
                    getColor(R.color.greyish)
            );
        }

        var markerOptions = new MarkerOptions()
                .position(locationTag)
                //.title(markerTitle)
                .icon(icon);
        Marker marker = this.map.addMarker(markerOptions);

        this.currentMarkers.put(beaconId, marker);
        if (this.currentMarkers.size() == 1) {
            // for the first marker, navigate to it smoothly on the map!
            // (we choose the first added marker here because it is the
            // one that will become visible in the UI tag list at the
            // bottom of the screen)
            this.goToBeaconOnMap(beaconId, CAMERA_ON_MAP_INITIAL_ZOOM);
        }
    }

    private void setupTagScrollArea() {
        HorizontalScrollView scrollContainer = this.findViewById(R.id.tags_scrollable_area);
        this.tagListSwiperHelper = new TagListSwiperHelper(
                scrollContainer,
                this.dynamicCardsForTag,
                this::goToBeaconOnMap
        );
        this.tagListSwiperHelper.setupTagScrollArea();
    }

    private void goToBeaconOnMap(final String beaconId) {
        this.goToBeaconOnMap(beaconId, null);
    }

    private void goToBeaconOnMap(final String beaconId, Float zoom) {
        try {
            Marker marker = Objects.requireNonNull(this.currentMarkers.get(beaconId));
            this.bringMarkerToTop(marker);

            var pos = marker.getPosition();

            Log.d(TAG, "Animating camera to position of marker for beaconId=" + beaconId + " after it was selected in the bottom tag list...");

            if (zoom != null) {
                this.map.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, zoom));
            } else {
                this.map.animateCamera(CameraUpdateFactory.newLatLng(pos));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failure when trying to navigate to marker on map on lock into card for beaconId=" + beaconId, e);
        }
    }

    private void bringMarkerToTop(Marker marker) {
        if (this.lastFocusedMarker == marker) {
            // do nothing
            return;
        }

        if (this.lastFocusedMarker != null) {
            this.lastFocusedMarker.setZIndex(MARKER_ZINDEX_DEFAULT);
        }
        marker.setZIndex(MARKER_ZINDEX_TOP);
        this.lastFocusedMarker = marker;
    }

    private synchronized void updateBeaconCards() {
        HorizontalScrollView scrollContainer = this.findViewById(R.id.tags_scrollable_area);
        LinearLayout cardsContainer = this.findViewById(R.id.tags_scroll_container);

        // remove all beacons that had cards that are now gone
        for (var beaconId : this.dynamicCardsForTag.keySet()) {
            if (!this.beacons.containsKey(beaconId) || !this.beaconLocations.containsKey(beaconId)) {
                Log.i(TAG, "Cleaning up view for beaconId=" + beaconId + " which did not have any locations associated with itself anymore");
                View view = this.dynamicCardsForTag.get(beaconId);
                cardsContainer.removeView(view);
                this.dynamicCardsForTag.remove(beaconId);
            }
        }

        final long now = System.currentTimeMillis();
        // remove all beacons that had cards that are now gone
        for (final BeaconData beaconData : this.beacons.values()) {
            final BeaconInformation beacon = beaconData.getInfo();
            final String beaconId = beacon.getBeaconId();
            if (!this.beaconLocations.containsKey(beaconId)) {
                Log.w(TAG, "Found a beacon (" + beaconId + ") without locations! We can't draw such a beacon. Skipping...");
                continue;
            }
            var locations = Objects.requireNonNull(this.beaconLocations.get(beaconId));
            if (locations.isEmpty()) {
                Log.w(TAG, "Fond a beacon (" + beaconId + ") with no location history items! We can't draw such a beacon. Skipping...");
                continue;
            }
            final BeaconLocationReport lastLocation = locations.get(locations.size() - 1);
            final List<Address> locationInfo = beaconData.getGeocoding();

            FrameLayout v;
            if (!this.dynamicCardsForTag.containsKey(beaconId)) {
                // MAKE A NEW CARD
                v = (FrameLayout) this.getLayoutInflater().inflate(R.layout.maps_tag_card, null);
                cardsContainer.addView(v);
                this.dynamicCardsForTag.put(beaconId, v);
            } else {
                // UPDATE EXISTING CARD
                v = this.dynamicCardsForTag.get(beaconId);
            }

            // match width to device screen
            assert v != null;
            var params = v.getLayoutParams();
            params.width = this.windowWidth != 0 ? (this.windowWidth - 80) : (this.getWindow().getDecorView().getWidth() - 80);

            v.setLayoutParams(params);

            // the title
            TextView deviceNameView = v.findViewById(R.id.device_name);
            deviceNameView.setText(beacon.getName());

            // icon
            if (beacon.isEmojiFilled()) {
                // use emoji
                TextView emojiContainer = v.findViewById(R.id.device_icon_emoji);
                ImageView iconContainer = v.findViewById(R.id.device_icon_img);
                emojiContainer.setText(beacon.getEmoji());
                emojiContainer.setVisibility(VISIBLE);
                iconContainer.setVisibility(GONE);
            }
            // ^ ELSE: show default apple icon


            // the location
            TextView deviceLocation = v.findViewById(R.id.device_location);
            if (locationInfo.isEmpty()) {
                deviceLocation.setText(String.format(
                        Locale.ROOT, "%.6f, %.6f", lastLocation.getLatitude(), lastLocation.getLongitude()));
            } else {
                var geoLocation = locationInfo.get(0);
                deviceLocation.setText(geoLocation.getAddressLine(0));
            }

            // the last updated time
            TextView deviceLastUpdate = v.findViewById(R.id.device_last_update);
            final var timeAgo = DateUtils.getRelativeTimeSpanString(
                    lastLocation.getTimestamp(),
                    now,
                    DateUtils.MINUTE_IN_MILLIS
            ).toString();
            deviceLastUpdate.setText(this.getString(R.string.last_updated_x, timeAgo));
        }


        ImageButton navigationButton = this.findViewById(R.id.button_navigate_to);
        if (this.dynamicCardsForTag.isEmpty()) {
            scrollContainer.setVisibility(GONE); // HIDE PARENT CONTAINER (FOR NOW)
            navigationButton.setVisibility(GONE);
        } else {
            scrollContainer.setVisibility(VISIBLE); // UNHIDE PARENT CONTAINER
            navigationButton.setVisibility(VISIBLE);
        }
    }

    private void sendToLogin() {
        Intent intent = new Intent(this, AppleLoginActivity.class);
        startActivity(intent);
    }

    private void fetchAndUpdateCurrentBeacons() {
        var beacons = this.beacons.values().stream()
                .collect(Collectors.toMap(b -> b.getInfo().getBeaconId(), b -> b.getInfo().getOwnedBeaconPlistRaw()));

        TagCardHelper.toggleRefreshLoadingAll(this.dynamicCardsForTag, true);

        var async = this.fetchLastReports(beacons)
                .doOnNext(this::addBeaconLocationsToCurrent)
                .flatMapCompletable((__) -> this.updateBeaconGeocodings())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    Log.d(TAG, "Refreshed location data and markers!");
                    TagCardHelper.toggleRefreshLoadingAll(this.dynamicCardsForTag, false);
                    this.showLastDeviceLocations();
                    //Toast.makeText(this, "Refreshed location data & markers", LENGTH_SHORT).show();
                }, error -> {
                    Log.e(TAG, "Failed to refresh current locations!", error);
                    TagCardHelper.toggleRefreshLoadingAll(this.dynamicCardsForTag, false);
                    //Toast.makeText(this, "Failed to refresh current location markers!", LENGTH_SHORT).show();
                });
    }

    private Observable<Map<String, List<BeaconLocationReport>>> fetchLastReports(final Map<String, String> beaconIdToPlist) {
        final long now = System.currentTimeMillis();

        final int hoursToGoBack = (int) Math.min(
                Math.ceil(((double)now - (double)this.last24HHistoryFetchAt)/(double)ONE_HOUR_IN_MS),
                HOURS_TO_GO_BACK_24H
        );

        Log.d(TAG, "Preparing to fetch location reports for the last " + hoursToGoBack + " hours!");
        return this.appleService.getLastReports(beaconIdToPlist, hoursToGoBack)
                .doOnNext(reports -> this.last24HHistoryFetchAt = now) // on success, update this time.
                .flatMap(this.beaconRepo::storeToLocationCache);
    }

    private Observable<Map<String, List<BeaconLocationReport>>> fetchLastReports(final Map<String, String> beaconIdToPlist, final int hoursToGoBack) {
        Log.d(TAG, "Preparing to fetch location reports for the last " + hoursToGoBack + " hours!");
        return this.appleService.getLastReports(beaconIdToPlist, hoursToGoBack)
                .flatMap(this.beaconRepo::storeToLocationCache);
    }

    private Observable<Map<String, List<BeaconLocationReport>>> fetchLastReportsFor(final String beaconId, final String pList, final int hoursToGoBack) {
        Log.i(TAG, "Preparing to fetch location reports for the last " + hoursToGoBack + " hours!");
        return this.appleService.getLastReports(Map.of(beaconId, pList), hoursToGoBack)
                .flatMap(this.beaconRepo::storeToLocationCache);
    }

    private boolean isAppleServiceInitialised() {
        return this.appleService != null;
    }

    private void enableMyLocation(boolean navigateToMyLocation) {
        // Check if permissions are granted, if so, enable the my location layer
        if (ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED) {
            Log.i(TAG, "Enabling 'my location' related UI features...");
            this.map.setMyLocationEnabled(true);

            // This UI button is only available if the user enables own location permissions.
            ImageButton button = findViewById(R.id.button_my_location);
            button.setVisibility(VISIBLE);

            if (navigateToMyLocation) {
                // smooth animate to current user's position!
                this.animateCameraToMyLocation();
            }

            return;
        }

        // Otherwise, request location permissions from the user
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, ACCESS_FINE_LOCATION)
                || ActivityCompat.shouldShowRequestPermissionRationale(this, ACCESS_COARSE_LOCATION)) {
            Log.d(TAG, "We are being asked to show a rationale dialogue for why we need location permissions. Proceeding to do this...");
            this.askForLocationWithRationale();
        } else {
            // Location permission has not been granted yet, request it.
            this.performNativePermissionRequest();
        }
    }

    private void performNativePermissionRequest() {
        Log.d(TAG, "Performing native android permission request");
        ActivityCompat.requestPermissions(
                this,
                new String[]{ ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION },
                LOCATION_PERMISSION_REQUEST_CODE
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode != LOCATION_PERMISSION_REQUEST_CODE) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (PermissionUtil.isPermissionGranted(permissions, grantResults, ACCESS_FINE_LOCATION) ||
                PermissionUtil.isPermissionGranted(permissions, grantResults, ACCESS_COARSE_LOCATION)) {
            Log.i(TAG, "Permission request for location was granted");
            this.enableMyLocation(true);
        } else {
            Log.i(TAG, "Location permission request was refused, so not going to be rendering current user location");
        }
    }

    @Override
    public boolean onMarkerClick(@NonNull Marker marker) {
        this.bringMarkerToTop(marker);

        Optional<String> beaconIdForMarker = this.currentMarkers.entrySet().stream()
                .filter(kvp -> kvp.getValue().equals(marker))
                .map(Map.Entry::getKey)
                .findFirst();

        if (beaconIdForMarker.isPresent()) {
            this.tagListSwiperHelper.navigateToCard(beaconIdForMarker.get());
        } else {
            Log.w(TAG, "Clicked on a marker that could not be associated back to any beaconId!");
        }

        return false;
    }

    private void handleDeviceListChanged() {
        // TODO: do this in a nicer way...
        this.recreate();
    }

    private void handleSendToLogin() {
        this.finish(); // finish current activity, send to login.
        // Login will send back to instance of this if it succeeds.
        Intent intent = new Intent(this, AppleLoginActivity.class);
        startActivity(intent);
    }

    private UserSettings getRefreshUserSettings() {
        this.userSettings = this.userSettingsRepo.getUserSettings();
        return this.userSettings;
    }

    private Observable<Optional<UserMapCameraPosition>> getLastCameraPosition() {
        if (this.lastCameraPositionOnLoad != null) {
            return Observable.just(this.lastCameraPositionOnLoad);
        }

        return this.userDataRepository.getLastCameraPosition()
                .doOnNext(pos -> this.lastCameraPositionOnLoad = pos)
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Data
    private static final class BeaconData {
        @lombok.NonNull private BeaconInformation info;
        @lombok.NonNull private List<Address> geocoding;
        private LatLng lastGeocodingLocation;

        public BeaconData(@lombok.NonNull BeaconInformation info, @lombok.NonNull List<Address> geocoding) {
            this.info = info;
            this.geocoding = geocoding;
        }
    }
}