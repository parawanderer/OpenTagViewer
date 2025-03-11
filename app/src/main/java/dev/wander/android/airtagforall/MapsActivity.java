package dev.wander.android.airtagforall;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.GoogleMap.OnMapClickListener;

import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import dev.wander.android.airtagforall.data.model.BeaconInformation;
import dev.wander.android.airtagforall.data.model.BeaconLocationReport;
import dev.wander.android.airtagforall.databinding.ActivityMapsBinding;
import dev.wander.android.airtagforall.db.datastore.UserAuthDataStore;
import dev.wander.android.airtagforall.db.datastore.UserSettingsDataStore;
import dev.wander.android.airtagforall.db.repo.UserAuthRepository;
import dev.wander.android.airtagforall.db.repo.UserSettingsRepository;
import dev.wander.android.airtagforall.db.repo.model.AppleUserData;
import dev.wander.android.airtagforall.db.room.AirTag4AllDatabase;
import dev.wander.android.airtagforall.db.repo.BeaconRepository;
import dev.wander.android.airtagforall.db.repo.model.ImportData;
import dev.wander.android.airtagforall.db.util.BeaconCombinerUtil;
import dev.wander.android.airtagforall.python.PythonAppleService;
import dev.wander.android.airtagforall.python.PythonAuthService;
import dev.wander.android.airtagforall.util.AppCryptographyUtil;
import dev.wander.android.airtagforall.util.AppleZipImporterUtil;
import dev.wander.android.airtagforall.util.BeaconDataParser;
import dev.wander.android.airtagforall.util.ZipImporterException;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback, OnMapClickListener, ActivityCompat.OnRequestPermissionsResultCallback {
    private static final String TAG = MapsActivity.class.getSimpleName();

    private static final int GOOGLE_LOGO_PADDING_BOTTOM_PX = 40;

    private static final int HOURS_TO_GO_BACK_24H = 24;

    private static final long WAIT_BEFORE_REFETCH = 1000 * 60; // 1 MINUTE

    private static final long ONE_HOUR_IN_MS = 1000 * 60 * 60; // 1 HOUR

    private GoogleMap mMap;
    private ActivityMapsBinding binding;

    private BeaconRepository beaconRepo;

    private UserSettingsRepository userSettingsRepo;

    private UserAuthRepository userAuthRepo;

    private PythonAppleService appleService = null;

    private final Map<String, BeaconInformation> beacons = new ConcurrentHashMap<>();

    private final Map<String, List<BeaconLocationReport>> beaconLocations = new ConcurrentHashMap<>();

    private final Map<String, Marker> currentMarkers = new ConcurrentHashMap<>();


    private final Map<String, FrameLayout> dynamicCardsForTag = new ConcurrentHashMap<>();

    private long last24HHistoryFetchAt = 0L;

    private final ActivityResultLauncher<Intent> pickZipActivityLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            (ActivityResult result) -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    this.onImportFilePicked(data);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        this.checkApiKey();

        binding = ActivityMapsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        userSettingsRepo = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.getApplicationContext()));

        userAuthRepo = new UserAuthRepository(
                UserAuthDataStore.getInstance(getApplicationContext()),
                new AppCryptographyUtil());

        beaconRepo = new BeaconRepository(
                AirTag4AllDatabase.getInstance(getApplicationContext()));

        this.handleAuthAndShowDevices();

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
        mMap = googleMap;

        mMap.setOnMapClickListener(this);

        mMap.setPadding(0, 0, 0, GOOGLE_LOGO_PADDING_BOTTOM_PX);

        // Add a marker in Sydney and move the camera
        // LatLng sydney = new LatLng(-34, 151);
        // mMap.addMarker(new MarkerOptions().position(sydney).title("Marker in Sydney"));
        // mMap.moveCamera(CameraUpdateFactory.newLatLng(sydney));

        googleMap.setOnMyLocationButtonClickListener(() -> {
            Toast.makeText(this, "MyLocation button clicked", LENGTH_SHORT).show();
            // Return false so that we don't consume the event and the default behavior still occurs
            // (the camera animates to the user's current position).
            return false;
        });

        googleMap.setOnMyLocationClickListener((location) -> {
            Toast.makeText(this, "Current location: " + location, LENGTH_SHORT).show();
        });

        this.enableMyLocation();
    }

    // TODO: Starting with here, refactor. Taken from the Google maps examples...
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;

    private void enableMyLocation() {
        // Check if permissions are granted, if so, enable the my location layer
        if (ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED) {
            this.mMap.setMyLocationEnabled(true);
            return;
        }

        // Otherwise, request location permissions from the user
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, ACCESS_FINE_LOCATION)
            || ActivityCompat.shouldShowRequestPermissionRationale(this, ACCESS_COARSE_LOCATION)) {
            // display a dialogue with rationale
            // Display a dialog with rationale.
            RationaleDialog.newInstance(LOCATION_PERMISSION_REQUEST_CODE, true)
                    .show(this.getSupportFragmentManager(), "dialog");
        } else {
            // Location permission has not been granted yet, request it.
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{ ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION },
                    LOCATION_PERMISSION_REQUEST_CODE
            );
        }
    }

    private boolean permissionDenied = false;

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode != LOCATION_PERMISSION_REQUEST_CODE) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (isPermissionGranted(permissions, grantResults,
                ACCESS_FINE_LOCATION) ||
                isPermissionGranted(permissions, grantResults,
                        ACCESS_COARSE_LOCATION)) {
            // Enable the my location layer if the permission has been granted.
            enableMyLocation();
        } else {
            // Permission was denied. Display an error message
            // [START_EXCLUDE]
            // Display the missing permission error dialog when the fragments resume.
            permissionDenied = true;
            // [END_EXCLUDE]
        }
    }

    @Override
    protected void onResumeFragments() {
        super.onResumeFragments();

        if (permissionDenied) {
            // Permission was not granted, display error dialog.
            showMissingPermissionError();
            permissionDenied = false;
        }
    }

    /**
     * Displays a dialog with error message explaining that the location permission is missing.
     */
    private void showMissingPermissionError() {
        Toast.makeText(this, "PERMISSION DENIED ERROR", LENGTH_SHORT).show();
    }

    // TODO: end section taken from google maps examples...

    /**
     * Checks if the result contains a {@link PackageManager#PERMISSION_GRANTED} result for a
     * permission from a runtime permissions request.
     *
     * @see androidx.core.app.ActivityCompat.OnRequestPermissionsResultCallback
     */
    public static boolean isPermissionGranted(String[] grantPermissions, int[] grantResults,
                                              String permission) {
        for (int i = 0; i < grantPermissions.length; i++) {
            if (permission.equals(grantPermissions[i])) {
                return grantResults[i] == PackageManager.PERMISSION_GRANTED;
            }
        }
        return false;
    }

    /**
     * A dialog that explains the use of the location permission and requests the necessary
     * permission.
     * <p>
     * The activity should implement {@link androidx.core.app.ActivityCompat.OnRequestPermissionsResultCallback}
     * to handle permit or denial of this permission request.
     */
    public static class RationaleDialog extends DialogFragment {

        private static final String ARGUMENT_PERMISSION_REQUEST_CODE = "requestCode";

        private static final String ARGUMENT_FINISH_ACTIVITY = "finish";

        private boolean finishActivity = false;

        /**
         * Creates a new instance of a dialog displaying the rationale for the use of the location
         * permission.
         * <p>
         * The permission is requested after clicking 'ok'.
         *
         * @param requestCode Id of the request that is used to request the permission. It is
         * returned to the {@link androidx.core.app.ActivityCompat.OnRequestPermissionsResultCallback}.
         * @param finishActivity Whether the calling Activity should be finished if the dialog is
         * cancelled.
         */
        public static RationaleDialog newInstance(int requestCode, boolean finishActivity) {
            Bundle arguments = new Bundle();
            arguments.putInt(ARGUMENT_PERMISSION_REQUEST_CODE, requestCode);
            arguments.putBoolean(ARGUMENT_FINISH_ACTIVITY, finishActivity);
            RationaleDialog dialog = new RationaleDialog();
            dialog.setArguments(arguments);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            Bundle arguments = getArguments();
            final int requestCode = arguments.getInt(ARGUMENT_PERMISSION_REQUEST_CODE);
            finishActivity = arguments.getBoolean(ARGUMENT_FINISH_ACTIVITY);

            return new AlertDialog.Builder(getActivity())
                    .setMessage("Access to the location service is required to demonstrate the \\'my location\\' feature, which shows your current location on the map.")
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // After click on Ok, request the permission.
                            ActivityCompat.requestPermissions(getActivity(),
                                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                    requestCode);
                            // Do not finish the Activity while requesting permission.
                            finishActivity = false;
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
        }

        @Override
        public void onDismiss(DialogInterface dialog) {
            super.onDismiss(dialog);
            if (finishActivity) {
                Toast.makeText(getActivity(),
                                "Location permission is required for this demo.",
                                Toast.LENGTH_SHORT)
                        .show();
                getActivity().finish();
            }
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

        final long now = System.currentTimeMillis();

        if (this.isAppleServiceInitialised()) {
            if (now < this.last24HHistoryFetchAt + WAIT_BEFORE_REFETCH) {
                Log.d(TAG, String.format(
                        "We will not re-fetch Beacon history as less than %d ms (actual: %d ms) have passed since the last history fetch",
                        WAIT_BEFORE_REFETCH,
                        (now - this.last24HHistoryFetchAt)
                ));
            } else {
                this.fetchAndUpdateCurrentBeacons();
            }
        }
    }

    public void onClickMoreSettings(View view) {
        ImageButton bttn = findViewById(R.id.buttonMoreSettings);
        Log.i(TAG, "The global more button was just clicked!");

        var popupMenu = new PopupMenu(this, bttn);
        popupMenu.getMenuInflater().inflate(R.menu.global_map_more_menu, popupMenu.getMenu());

        popupMenu.setOnMenuItemClickListener(menuItem -> {
            Toast.makeText(this, "You clicked " + menuItem.getTitle(), LENGTH_SHORT).show();

            if (menuItem.getItemId() == R.id.do_import) {
                this.handleImport();
            } else if (menuItem.getItemId() == R.id.settings) {
                this.showSettingsPage();
            } else if (menuItem.getItemId() == R.id.information) {
                this.showInformationPage();
            }
            // TODO: the other ones

            return true;
        });

        popupMenu.show();
    }

    private void showInformationPage() {
        Log.i(TAG, "Show information page");

        // navigate to information page
        Intent intent = new Intent(this, InformationActivity.class);
        startActivity(intent);
    }

    private void showSettingsPage() {
        Log.i(TAG, "Show settings page");

        // navigate to settings page
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void onImportFilePicked(Intent data) {
        Log.i(TAG, "File has been picked");

        // combine them into the current list of beaconLocations & show this list
        var async = this.extractImportedData(data)
            .flatMap(this.beaconRepo::addNewImport)
            .doOnNext((__) -> {
                this.runOnUiThread(() -> {
                    Toast.makeText(this, "New data was inserted into DB", LENGTH_SHORT).show();
                });
            })
            .publish(storedBeacons ->
            /*
             * Note: this is a bit of a fork-join: https://stackoverflow.com/questions/48015796/using-rxjava-to-fork-into-tasks-and-combine-results
             * May be too much of an optimization, but I figured I might as well try out the extent
             * of what you can do with these RXJava observables as far as multithreading goes...
             */
                Observable.zip(
                        storedBeacons.flatMap(beacons -> {
                            Map<String, String> request = beacons.getOwnedBeacons().stream()
                                    .collect(Collectors.toMap(b -> b.id, b -> b.content));
                            return this.fetchLastReportsDay(request);
                        })
                        .doOnNext(this::addBeaconLocationsToCurrent)
                        .flatMap(this.beaconRepo::storeLocationCache), // add these to the current locations list
                        storedBeacons.flatMap(beacons -> BeaconDataParser.parseAsync(
                                    BeaconCombinerUtil.combine(
                                            beacons.getOwnedBeacons(),
                                            beacons.getBeaconNamingRecords()
                                    ))
                        )
                        .doOnNext(this::addBeaconToCurrent),
                        (lastReports, parsedBeaconData) -> {

                            this.runOnUiThread(() -> {
                                this.showLastDeviceLocations();
                                Toast.makeText(this, "New reports were visualised on the map!", LENGTH_SHORT).show();
                            });

                            return Pair.create(lastReports, parsedBeaconData);
                        }
                )
            )
            .subscribe((lastReportsAndBeaconData) -> {
                Log.i(TAG, "Finished visualising new location reports!");
            }, error -> {
                Log.e(TAG, "Error occurred while inserting into DB", error);
                this.runOnUiThread(() -> Toast.makeText(this, "Error occurred while inserting into DB", LENGTH_SHORT).show());
            });
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

    public void onClickLocationHistory(View view) {
        ImageButton btn = findViewById(R.id.locationHistoryButton);
        Log.i(TAG, "The location history button was just clicked!");
    }

    public void onClickRefresh(View view) {
        ImageButton btn = findViewById(R.id.refreshButton);
        Log.i(TAG, "The refresh button was just clicked!");

        final String beaconId = this.dynamicCardsForTag.entrySet()
                .stream().filter(kvp -> kvp.getValue().findViewById(R.id.device_refresh_button_container) == view)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Click refresh event was raised by a Beacon Device's card, but the beaconId could not be found for it!"));

        // we can now fetch for this Id only!
        var async = this.fetchLastReportsFor(
                beaconId,
                Objects.requireNonNull(this.beacons.get(beaconId)).getOwnedBeaconPlistRaw(),
                1)
                .doOnNext(this::addBeaconLocationsToCurrent)
                .flatMap(this.beaconRepo::storeLocationCache)
                .subscribe((__) -> {
                    Log.i(TAG, "Refreshed location data and markers for beaconId=" + beaconId + " on refresh button click");
                    this.runOnUiThread(() -> {
                        this.showLastDeviceLocations();
                        Toast.makeText(this, "Refreshed location data & markers for beaconId="+beaconId, LENGTH_SHORT).show();
                    });
                }, error -> {
                    Log.e(TAG, "Failed to refresh current location for beaconId=" + beaconId + " on refresh button click!");
                    this.runOnUiThread(() -> Toast.makeText(this, "Failed to refresh location for beaconId=" + beaconId, LENGTH_SHORT).show());
                });
    }

    public void onClickRing(View view) {
        ImageButton btn = findViewById(R.id.ringButton);
        Log.i(TAG, "The ring button was just clicked!");
    }

    public void onClickMoreForDevice(View view) {
        ImageButton btn = findViewById(R.id.moreButton);
        Log.i(TAG, "The more (device-level) button was just clicked!");
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
                this.appleService = new PythonAppleService(appleAccount);
                return this.appleService;
            });

        // get list of Beacons
        var asyncAllBeacons = this.beaconRepo.getAllBeacons()
                .flatMap(BeaconDataParser::parseAsync)
                .doOnNext(this::addBeaconToCurrent);

        // get list of cached (previously fetched) locations
        // (might be empty or might not be present for all of them)
        var asyncAllLocations = this.beaconRepo.getLastForAll();

        var asyncBeaconData = Observable.zip(asyncAllBeacons, asyncAllLocations, (allBeacons, allLatestLocations) -> {
            // temporarily show cached beacon locations until we get the new ones!
            var cachedLocations = allLatestLocations.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, kvp -> List.of(kvp.getValue())));
            this.addBeaconLocationsToCurrent(cachedLocations);

            this.runOnUiThread(() -> {
                // show the locations for all the devices that were already in the cache
                Toast.makeText(this.getApplicationContext(), "Showing cached locations...", LENGTH_SHORT).show();
                this.showLastDeviceLocations();
            });

            return allBeacons;
        });

        // initially show the cached locations (after we get those back from the DB)
        // afterwards try to fetch the latest location reports from the Apple servers
        // store those reports in the DB (cache) and then show the updated positions
        var asyncCombo = Observable.zip(asyncAppleService, asyncBeaconData, (service, beacons) -> {
            // map to expected format:
            final Map<String, String> requestInput = beacons.stream().collect(
                    Collectors.toMap(BeaconInformation::getBeaconId, BeaconInformation::getOwnedBeaconPlistRaw));
            return this.fetchLastReportsDay(requestInput);
        }).flatMap(o -> o)
        .flatMap(this.beaconRepo::storeLocationCache)
        .subscribe(lastReports -> {
            this.addBeaconLocationsToCurrent(lastReports);
            this.runOnUiThread(() -> {
                Toast.makeText(this.getApplicationContext(), "Yay, got last reports!", LENGTH_SHORT).show();
                this.showLastDeviceLocations();
            });
            Log.i(TAG, "Successfully retrieved latest reports!");
        }, error -> {
            Log.e(TAG, "Error while restoring account and trying to get latest beacons", error);
            this.runOnUiThread(() -> Toast.makeText(this.getApplicationContext(), "Error while trying to fetch data for beacons", LENGTH_SHORT).show());
        });
    }

    private synchronized void addBeaconToCurrent(final List<BeaconInformation> newBeaconInformation) {
        newBeaconInformation.forEach(beacon -> {
            final String beaconId = beacon.getBeaconId();
            if (this.beacons.containsKey(beaconId)) {
                Log.d(TAG, "Replacing existing beacon info for beaconId=" + beaconId);
            }
            this.beacons.put(beaconId, beacon);
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

                Log.i(TAG, String.format(
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

    private synchronized void showLastDeviceLocations() {
        for (BeaconInformation beacon : this.beacons.values()) {
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

    private boolean TEMP_hasNavigatedToMarkers = false; // TODO: improve implementation

    private synchronized void showBeaconOnMap(final BeaconInformation beacon, final BeaconLocationReport lastLocation) {
        // TODO: make this whole thing prettier, make it reuse tags if possible, etc...
        // Add a marker and move the camera
        var format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH);

        final String beaconId = beacon.getBeaconId();
        final LatLng locationTag = new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude());

        if (this.currentMarkers.containsKey(beaconId)) {
            // remove the old marker
            Log.d(TAG, "Removing old marker for beaconId=" + beaconId + " which is no longer valid due to a new location");
            var oldMarker = Objects.requireNonNull(this.currentMarkers.get(beaconId));
            oldMarker.remove();
            this.currentMarkers.remove(beaconId);
        }

        Log.i(TAG, "Going to add new marker for beaconId=" + beaconId);

        // TODO: make the markers prettier...
        final String markerTitle = String.format("%s %s", beacon.getEmoji(), beacon.getName());
        final String markerSnippet = String.format(Locale.ENGLISH, "Last seen: %s (%s, %d, %d, %d)",
                format.format(new Date(lastLocation.getTimestamp())),
                lastLocation.getDescription(),
                lastLocation.getConfidence(),
                lastLocation.getHorizontalAccuracy(),
                lastLocation.getStatus()
        );

        var markerOptions = new MarkerOptions()
                .position(locationTag)
                .title(markerTitle)
                .snippet(markerSnippet);


        Marker marker = mMap.addMarker(markerOptions);
        this.currentMarkers.put(beaconId, marker);
        if (!this.TEMP_hasNavigatedToMarkers) {
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                    locationTag,
                    16.0f
            ));
            this.TEMP_hasNavigatedToMarkers = true;
        }
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
        for (final BeaconInformation beacon : this.beacons.values()) {
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
            params.width = (this.getWindow().getDecorView().getWidth() - 80);
            v.setLayoutParams(params);

            // the title
            TextView deviceNameView = v.findViewById(R.id.device_name);
            if (beacon.getEmoji() != null && !beacon.getEmoji().isEmpty()) {
                deviceNameView.setText(String.format("%s %s", beacon.getEmoji(), beacon.getName()));
            } else {
                deviceNameView.setText(beacon.getName());
            }

            // the location
            // TODO: fetch pretty location name from google maps api
            TextView deviceLocation = v.findViewById(R.id.device_location);
            deviceLocation.setText(String.format(
                    Locale.ROOT, "%.6f, %.6f", lastLocation.getLatitude(), lastLocation.getLongitude()));

            // the last updated time
            // TODO: format it according to local timezone
            TextView deviceLastUpdate = v.findViewById(R.id.device_last_update);
            final var timeAgo = DateUtils.getRelativeTimeSpanString(
                    lastLocation.getTimestamp(),
                    now,
                    DateUtils.MINUTE_IN_MILLIS
            ).toString();
            deviceLastUpdate.setText(this.getString(R.string.last_updated_x, timeAgo));
        }

        if (this.dynamicCardsForTag.isEmpty()) {
            // HIDE PARENT CONTAINER (FOR NOW)
            scrollContainer.setVisibility(GONE);
        } else {
            // UNHIDE PARENT CONTAINER
            scrollContainer.setVisibility(VISIBLE);
        }
    }

    private void sendToLogin() {
        Intent intent = new Intent(this, AppleLoginActivity.class);
        startActivity(intent);
    }

    private void fetchAndUpdateCurrentBeacons() {
        var beacons = this.beacons.values().stream()
                .collect(Collectors.toMap(BeaconInformation::getBeaconId, BeaconInformation::getOwnedBeaconPlistRaw));

        var async = this.fetchLastReportsDay(beacons)
                .doOnNext(this::addBeaconLocationsToCurrent)
                .flatMap(this.beaconRepo::storeLocationCache)
                .subscribe((__) -> {
                    Log.i(TAG, "Refreshed location data and markers!");
                    this.runOnUiThread(() -> {
                        this.showLastDeviceLocations();
                        Toast.makeText(this, "Refreshed location data & markers", LENGTH_SHORT).show();
                    });
                }, error -> {
                    Log.e(TAG, "Failed to refresh current locations!");
                    this.runOnUiThread(() -> Toast.makeText(this, "Failed to refresh current location markers!", LENGTH_SHORT).show());
                });
    }

    private Observable<Map<String, List<BeaconLocationReport>>> fetchLastReportsDay(final Map<String, String> beaconIdToPlist) {
        final long now = System.currentTimeMillis();

        final int hoursToGoBack = (int) Math.min(
                Math.ceil(((double)now - (double)this.last24HHistoryFetchAt)/(double)ONE_HOUR_IN_MS),
                HOURS_TO_GO_BACK_24H
        );

        Log.i(TAG, "Preparing to fetch location reports for the last " + hoursToGoBack + " hours!");
        return this.appleService.getLastReports(beaconIdToPlist, hoursToGoBack)
                .doOnNext(reports -> this.last24HHistoryFetchAt = now); // on success, update this time.
    }

    private Observable<Map<String, List<BeaconLocationReport>>> fetchLastReportsFor(final String beaconId, final String pList, final int hoursToGoBack) {
        Log.i(TAG, "Preparing to fetch location reports for the last " + hoursToGoBack + " hours!");
        return this.appleService.getLastReports(Map.of(beaconId, pList), hoursToGoBack);
    }

    private boolean isAppleServiceInitialised() {
        return this.appleService != null;
    }
}