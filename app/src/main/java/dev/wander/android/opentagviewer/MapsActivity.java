package dev.wander.android.opentagviewer;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
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
import android.content.res.ColorStateList;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
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
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;

import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.ui.importing.BundlePasscodeDialog;
import dev.wander.android.opentagviewer.ui.importing.ImportOutcome;
import dev.wander.android.opentagviewer.ui.importing.ImportedButNotLocatedDialog;
import dev.wander.android.opentagviewer.ui.login.TwoFactorAgainOverlay;
import dev.wander.android.opentagviewer.ui.settings.AnisetteUpgradeDialog;
import dev.wander.android.opentagviewer.ui.settings.ICloudSetupOfferDialog;
import dev.wander.android.opentagviewer.ui.maps.IMapProvider;
import dev.wander.android.opentagviewer.ui.maps.MapProviderFactory;
import dev.wander.android.opentagviewer.ui.maps.GoogleMapProvider;
import dev.wander.android.opentagviewer.ui.maps.AMapProvider;
import dev.wander.android.opentagviewer.ui.maps.MapMarker;
import dev.wander.android.opentagviewer.ui.maps.MapPolyline;
import dev.wander.android.opentagviewer.ui.maps.MarkerPalette;
import com.google.android.libraries.places.api.Places;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.ui.error.ErrorReportActivity;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.data.model.UserMapCameraPosition;
import dev.wander.android.opentagviewer.databinding.ActivityMapsBinding;
import dev.wander.android.opentagviewer.anisette.LocalAnisette;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
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
import dev.wander.android.opentagviewer.python.AccessoryRequest;
import dev.wander.android.opentagviewer.python.icloud.ICloudFailures;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.LogRedactor;
import dev.wander.android.opentagviewer.ui.BeaconIcon;
import dev.wander.android.opentagviewer.python.PythonAppleService;
import dev.wander.android.opentagviewer.python.PythonAccountLoginException;
import dev.wander.android.opentagviewer.python.PythonAuthService;
import dev.wander.android.opentagviewer.db.repo.UserDataRepository;
import dev.wander.android.opentagviewer.ui.maps.TagCardHelper;
import dev.wander.android.opentagviewer.ui.maps.TagListSwiperHelper;
import dev.wander.android.opentagviewer.util.android.FusedPhoneLocation;
import dev.wander.android.opentagviewer.util.LogCollectorUtil;
import dev.wander.android.opentagviewer.util.MapUtils;
import dev.wander.android.opentagviewer.util.TagOrder;
import dev.wander.android.opentagviewer.util.TagVisibility;
import dev.wander.android.opentagviewer.python.icloud.AccountRefresher;
import dev.wander.android.opentagviewer.util.android.AddressLookup;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.util.android.PermissionUtil;
import dev.wander.android.opentagviewer.ui.maps.VectorImageGeneratorUtil;
import dev.wander.android.opentagviewer.util.parse.AppleZipImporterUtil;
import dev.wander.android.opentagviewer.util.parse.BeaconDataParser;
import dev.wander.android.opentagviewer.util.parse.ZipImporterException;
import dev.wander.android.opentagviewer.util.rx.BeaconLocationHistory;
import dev.wander.android.opentagviewer.util.rx.LongFetchBannerState;
import dev.wander.android.opentagviewer.util.rx.MarkerFocus;
import dev.wander.android.opentagviewer.util.rx.AccountReadPolicy;
import dev.wander.android.opentagviewer.util.rx.RefreshPolicy;
import dev.wander.android.opentagviewer.util.rx.RxFlows;
import dev.wander.android.opentagviewer.ble.BlePermissions;
import dev.wander.android.opentagviewer.ble.BleSoundTriggerPhase;
import dev.wander.android.opentagviewer.ble.BleSoundTriggerStatus;
import dev.wander.android.opentagviewer.ble.BleSoundTriggerUpdate;
import dev.wander.android.opentagviewer.ble.NearbyTagLabel;
import dev.wander.android.opentagviewer.ble.NearbyTagSighting;
import dev.wander.android.opentagviewer.ble.NearbyTagSightings;
import dev.wander.android.opentagviewer.ble.NearbyTagIndex;
import dev.wander.android.opentagviewer.ble.NearbyTagWatcher;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.Data;

/**
 * TODO: this whole thing is a bit of a godclass. Decouple it.
 */
public class MapsActivity extends AppCompatActivity implements IMapProvider.OnMapReadyCallback, IMapProvider.OnMapClickListener, IMapProvider.OnMarkerClickListener, ActivityCompat.OnRequestPermissionsResultCallback {
    private static final String TAG = MapsActivity.class.getSimpleName();

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final int RING_PERMISSION_REQUEST_CODE = 2;
    private static final int NEARBY_PERMISSION_REQUEST_CODE = 3;

    private static final int GOOGLE_LOGO_PADDING_BOTTOM_PX = 40;

    private static final int HOURS_TO_GO_BACK_24H = 24;

    /**
     * How far back to look for a tag nothing has ever searched for.
     *
     * <p><b>A day is the wrong window for a tag's first fetch.</b> Everything else here asks
     * about the time since the last fetch, which is right for a tag the app has been watching -
     * but a tag that has just arrived from a zip or from the account has no history at all, and
     * one last seen on Tuesday comes back empty from a window that starts this morning. It then
     * sits in My Devices saying "No last location known" while its locations are sitting on
     * Apple's servers, findable by opening the history screen and paging back - which is exactly
     * how @parawanderer found this.
     *
     * <p>Seven days because that is all Apple keeps, so it is the whole of what can be had, and
     * one request rather than a widening loop. It costs little: ~672 key indices for an aligned
     * tag against the 2000 that makes a search count as expensive, so it does not look like a
     * wide search and cannot push a healthy tag towards the backoff.
     *
     * <p>Applied per beacon and only while {@code last_scan_at} is null, so it happens once and
     * then stops - a tag that has been searched drops back to the ordinary window whether or not
     * that first search found anything.
     */
    private static final int HOURS_TO_GO_BACK_FIRST_TIME = 24 * 7;

    private static final long WAIT_BEFORE_REFETCH = 1000 * 60; // 1 MINUTE

    private static final float CAMERA_ON_MAP_INITIAL_ZOOM = 16.0f; // see: https://developers.google.com/maps/documentation/android-sdk/views#zoom

    private IMapProvider mapProvider;
    private GoogleMap map; // 保留用于向后兼容，逐步迁移

    private ActivityMapsBinding binding;

    private BeaconRepository beaconRepo;

    private UserSettingsRepository userSettingsRepo;

    private UserAuthRepository userAuthRepo;

    private UserDataRepository userDataRepository;

    private PythonAppleService appleService = null;

    private UserSettings userSettings;

    private FusedLocationProviderClient fusedLocationClient = null;

    private AddressLookup geocoder = null;

    private final Map<String, BeaconData> beacons = new ConcurrentHashMap<>();

    /** The beaconId continuous ping is currently running for, or null if it is off. Only one
     * runs at a time - see {@link #onClickRing}. */
    private String continuousPingBeaconId;

    /** The in-flight continuous ping loop, so leaving the screen stops the radio work rather
     * than leaving it running in the background with nothing left to show its state. */
    private Disposable continuousPingDisposable;

    /** Which tag's ring button asked for BLE permission, so the result callback - which carries
     * no context of its own - knows what to start once it is granted. */
    private String ringPermissionRequestBeaconId;

    /**
     * Tags this phone can hear right now, and how full their batteries say they are.
     *
     * <p>Fed by a scan that runs only while this screen is resumed, so it is a display of what
     * is audible rather than any kind of tracking. Entries age out on their own - see
     * {@link NearbyTagSightings}.
     */
    private final NearbyTagSightings nearbySightings = new NearbyTagSightings();

    /** The in-flight nearby scan, disposed in {@link #onPause()} so the radio stops with the screen. */
    private Disposable nearbyWatchDisposable;

    /**
     * Redraws the cards once a second while the nearby scan is running, so a nearby card's
     * "heard N seconds ago" keeps counting up between sightings instead of only changing when
     * one arrives.
     *
     * <p>A separate ticker rather than something {@link #onTagHeardNearby} drives, because a
     * sighting fires roughly every one to three seconds while a tag is genuinely in range - so
     * driving the redraw from sightings alone would repaint the line back to "0s" almost as
     * often as it changed, and never show the gap growing in between.
     */
    private Disposable nearbyStatusTickerDisposable;

    /**
     * Whether this activity has already asked for the BLE permission the nearby watch needs.
     *
     * <p>Never reset for the life of the activity: the system dialog pauses this activity, so
     * asking again from every {@code onResume} re-prompted the moment the user denied - an
     * inescapable loop on Android 10 and below, and a silent auto-denied request burned on
     * every resume above that. Granting from the dialog still takes effect immediately through
     * {@code onRequestPermissionsResult}; a user who denied can still enable it later through
     * the system settings, which resumes this activity and passes the granted check directly.
     */
    private boolean nearbyBlePermissionRequested;

    /** The pending retry after the scan died mid-session - see {@link #onNearbyWatchEnded}. */
    private Disposable nearbyWatchRetryDisposable;

    /** The one place a Bluetooth sighting is persisted - see {@link AccessorySightingPersister}. */
    private AccessorySightingPersister sightingPersister;

    /** Location history plus the "can this be drawn" rule. See BeaconLocationHistoryTest. */
    private final BeaconLocationHistory beaconLocations = new BeaconLocationHistory();

    private final Map<String, String> currentMarkers = new ConcurrentHashMap<>(); // 存储markerId

    /** Keeps the selected tag's marker above the overlapping ones. See MarkerFocusTest. */
    private final MarkerFocus markerFocus = new MarkerFocus(new MarkerFocus.Markers() {
        @Override
        public String markerIdFor(String beaconId) {
            return MapsActivity.this.currentMarkers.get(beaconId);
        }

        @Override
        public void setZIndex(String markerId, float zIndex) {
            if (MapsActivity.this.mapProvider != null) {
                MapsActivity.this.mapProvider.setMarkerZIndex(markerId, zIndex);
            }
        }
    });

    private final Map<String, FrameLayout> dynamicCardsForTag = new ConcurrentHashMap<>();

    private boolean initialFetchComplete = false;

    /** When a refresh is allowed, and how much history it should ask for. See RefreshPolicyTest. */
    // Shared across activity instances on purpose - see RefreshPolicy.shared. Rebuilding this
    // screen must not look like an app that has never spoken to Apple.
    /**
     * How often the app re-reads the Apple account on its own.
     *
     * <p><b>Six hours, against the location refresh's one minute.</b> These answer different
     * questions: locations change constantly and are the point of the map, while what tags exist
     * changes when somebody adds one in Find My or renames it - rare, and never urgent. A read
     * also decrypts every record on the account and queues behind the location fetches, so doing
     * it eagerly costs the user's own work rather than just bandwidth.
     */
    /**
     * How often a running app re-reads the account.
     *
     * <p><b>Six hours, until somebody pointed out what that looks like.</b> An iPad picks up a
     * renamed AirTag in seconds; this app showed the old name for the rest of the afternoon. The
     * read is a CloudKit query for the account's accessories - and the location fetch beside it
     * runs every <i>minute</i>, so four of these an hour is not the expensive thing here.
     */
    private static final long WAIT_BEFORE_REREADING_ACCOUNT = 15L * 60 * 1000;

    /**
     * And the floor for the read on resume, which is the one that makes it feel immediate.
     *
     * <p>The moment a stale name is noticed is the moment the app is opened. Short enough that
     * opening it after a rename shows the new one; long enough that flicking between two apps
     * does not spend a Python call each time.
     */
    private static final long WAIT_BEFORE_REREADING_ON_RESUME = 60L * 1000;

    private final AccountReadPolicy accountReadPolicy = new AccountReadPolicy(
            WAIT_BEFORE_REREADING_ACCOUNT, WAIT_BEFORE_REREADING_ON_RESUME);

    /**
     * Whether an Apple account is linked, as of the last time this screen resumed.
     *
     * <p>Cached rather than asked on every tick: the answer lives in the encrypted datastore, and
     * the tick runs every minute. It can only become true while this screen is away - linking
     * happens on another one - so resuming is exactly when it is worth asking again.
     */
    private volatile boolean accountIsLinked = false;

    private final RefreshPolicy refreshPolicy =
            RefreshPolicy.shared(WAIT_BEFORE_REFETCH, HOURS_TO_GO_BACK_24H);

    private TagListSwiperHelper tagListSwiperHelper = null;

    private final Handler refreshSchedulerHandler = new Handler();
    private Runnable nextLocationRefreshTask = null;

    /**
     * How long a fetch may run before we tell the user it is still working.
     * <br>
     * Long enough that an ordinary refresh never flashes the banner, short enough that a
     * first fetch does not sit there looking frozen.
     */
    private static final long SHOW_LONG_FETCH_BANNER_AFTER_MS = 6000L;

    private final Handler longFetchBannerHandler = new Handler(Looper.getMainLooper());
    private final Runnable showLongFetchBanner = () -> this.setLongFetchBannerVisible(true);

    /** All three are only ever touched on the main looper, so they need no synchronisation. */
    /** Banner bookkeeping. Touched only from the banner handler. See LongFetchBannerStateTest. */
    private final LongFetchBannerState bannerState = new LongFetchBannerState();

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
                        return;
                    }
                    // Both want the same thing - a full rebuild. The tags live in memory here
                    // and that model is what decides both what is drawn and what is fetched,
                    // so showing or hiding the owner's devices is not a redraw.
                    if (data != null && (data.getBooleanExtra("mapProviderChanged", false)
                            || data.getBooleanExtra("shownDevicesChanged", false))) {
                        this.recreate();
                    }
                }
            }
    );

    /**
     * Connecting an iCloud account, from the one-time offer after signing in.
     *
     * <p>A rebuild when anything came back, for the reason the device list already does it: tags
     * read from the account have to appear without the user going somewhere else and returning,
     * and this screen accumulates its tags on load rather than merging.
     */
    private final ActivityResultLauncher<Intent> fetchFromICloudLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            (ActivityResult result) -> {
                final Intent data = result.getData();
                if (data == null) {
                    return;
                }
                // The iCloud screen offers a file import as its way out for anybody who cannot
                // use an account; the picker lives here, so it asks us to start it.
                if (data.getBooleanExtra(
                        FetchFromICloudActivity.RESULT_WANTS_FILE_IMPORT, false)) {
                    this.handleImport();
                    return;
                }
                if (data.getBooleanExtra(FetchFromICloudActivity.RESULT_IMPORTED, false)) {
                    this.handleDeviceListChanged();
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
                    // The empty state on the device list offers to import, but the picker and
                    // everything that runs after it live here, so it asks us to start it.
                    if (data != null && data.getBooleanExtra("startImport", false)) {
                        this.handleImport();
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
                            data.getStringExtra("deviceWasRemoved") != null
                            || data.getStringExtra("deviceWasChanged") != null)) {
                        this.handleDeviceListChanged();
                    }

                    // The tag page has no way to fetch - the picker, the Python service and the
                    // card that shows the answer all live here - so it asks. See
                    // DeviceInfoActivity#lookForItAgainNow.
                    final String retry = data == null
                            ? null : data.getStringExtra(DeviceInfoActivity.RETRY_IGNORED_BEACON);
                    if (retry != null) {
                        this.lookForAnIgnoredTagAgain(retry);
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
        this.sightingPersister = new AccessorySightingPersister(
                this.beaconRepo, new FusedPhoneLocation(this.getApplicationContext()));

        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        Places.initialize(this.getApplicationContext(), BuildConfig.MAPS_API_KEY);
        this.geocoder = AppDependencies.geocoder(
                this.getApplicationContext(), Locale.getDefault());

        this.setupTagScrollArea();

        var async = this.getLastCameraPosition()
            .subscribe(pos -> {
                Log.d(TAG, "Got previous camera position to reset us to: " + pos);

                pos.ifPresent(userMapCameraPosition -> {
                    if (this.mapProvider != null) {
                        this.mapProvider.moveCamera(
                                userMapCameraPosition.getLat(),
                                userMapCameraPosition.getLon(),
                                userMapCameraPosition.getZoom()
                        );
                    }
                });

            }, error -> Log.e(TAG, "Failed to get last camera position!", error));

        this.handleAuthAndShowDevices();

        this.windowWidth = this.getResources().getDisplayMetrics().widthPixels;

        // 根据用户设置创建地图提供商
        String mapProviderType = this.userSettings.getMapProvider();
        IMapProvider tempProvider = MapProviderFactory.create(mapProviderType);
        
        // 初始化地图（注意：this.mapProvider 仅在 onMapReady 后赋值，避免初始化竞态）
        tempProvider.initialize(this, R.id.map, this);
    }



    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     */
    @Override
    public void onMapReady(IMapProvider provider) {
        this.mapProvider = provider;

        // 如果是Google Maps，保留向后兼容
        if (provider instanceof GoogleMapProvider) {
            this.map = ((GoogleMapProvider) provider).getGoogleMap();
        }

        mapProvider.setOnMapClickListener(this);
        mapProvider.setOnMarkerClickListener(this);

        mapProvider.setPadding(0, 0, 0, GOOGLE_LOGO_PADDING_BOTTOM_PX);
        // We don't want to use the default button. We have a custom button
        mapProvider.setMyLocationButtonEnabled(false);
        mapProvider.setRotateGesturesEnabled(false); // no rotation (mostly bc very annoying to reset)
        mapProvider.setCompassEnabled(false); // not needed due to no rotation being allowed
        mapProvider.setMapToolbarEnabled(false); // we have a custom button for this

        mapProvider.setMapStyle(this.getPreferredMapStyle());

        this.enableMyLocation(false);

        // 地图准备好后，刷新一次当前显示的信标位置
        this.showLastDeviceLocations();
    }

    @Override
    protected void onPause() {
        super.onPause();

        this.stopWatchingForNearbyTags();

        if (this.mapProvider != null) {
            IMapProvider.CameraPosition pos = this.mapProvider.getCameraPosition();
            if (pos != null) {
                var async = this.userDataRepository.storeLastCameraPosition(
                        UserMapCameraPosition.builder()
                                .zoom(pos.getZoom())
                                .lat(pos.getLatitude())
                                .lon(pos.getLongitude())
                                .build()
                ).subscribe(
                        success -> Log.d(TAG, "Success storing last camera position!"),
                        error -> Log.e(TAG, "Error storing last camera position!", error));
            }

            // cleanup location refresh task
            refreshSchedulerHandler.removeCallbacks(this.nextLocationRefreshTask);
            this.nextLocationRefreshTask = null;
        }

        this.longFetchBannerHandler.removeCallbacks(this.showLongFetchBanner);
        
        // 调用高德地图的生命周期方法
        if (this.mapProvider instanceof AMapProvider) {
            ((AMapProvider) this.mapProvider).onPause();
        }
    }

    @Override
    public void onMapClick(double latitude, double longitude) {
        Log.i(TAG, "tapped, point=(" + latitude + ", " + longitude + ")");
        // TODO: hide UI elements when this occurs!
    }

    @Override
    protected void onResume() {
        super.onResume();

        this.userSettings = this.userSettingsRepo.getUserSettings();
        if (this.mapProvider != null) {
            this.mapProvider.setMapStyle(this.getPreferredMapStyle());
        }

         // TODO: when a user changes their anisette URL in settings and returns here, this should be able to deal with querying the new URL

        this.rememberWhetherAnAccountIsLinked();
        this.refreshIfAllowed();
        // **The account too, not only the locations.** Resuming already refetched where the tags
        // are and never asked what the tags *were* - so a tag renamed elsewhere kept its old name
        // on this screen until the periodic read came round.
        this.rereadTheAccountIfAllowed(true);
        this.reSchedulePeriodicTagLocationRefresher();
        this.startWatchingForNearbyTags();

        // 调用高德地图的生命周期方法
        if (this.mapProvider instanceof AMapProvider) {
            ((AMapProvider) this.mapProvider).onResume();
        }
    }

    /**
     * <b>Continuous ping stops when the screen does, not when the activity is destroyed.</b>
     *
     * <p>Pressing Home does not destroy an activity, so disposing in {@code onDestroy} left the
     * loop scanning and connecting over Bluetooth with the app in the background - burning the
     * radio for a sound the user is no longer in a position to hear, and with no way to stop it
     * short of coming back to this screen. {@code onDestroy} may not run for a long time, or at
     * all before the process is killed.
     *
     * <p><b>{@code onStop} rather than {@code onPause}</b>, which is a different question: pause
     * fires for a dialog or the notification shade, and someone walking towards a tag by ear
     * should not lose the ping to a passing notification. Stop means the screen is genuinely
     * gone.
     *
     * <p>Through {@link #stopContinuousPing()} rather than disposing directly, so the card's
     * button and spinner are reset too - otherwise returning to a stopped loop finds a card
     * still captioned "Stop" with a spinner that will never move again.
     */
    @Override
    protected void onStop() {
        super.onStop();
        this.stopContinuousPing();
    }

    /**
     * Listen for the user's own tags for as long as this screen is in front of somebody.
     *
     * <p><b>Tied to the screen rather than to a service, deliberately.</b> Nothing here runs in
     * the background: this starts in {@code onResume} and is disposed in {@code onPause}, so the
     * radio is on only while a person is looking at the result. That keeps it a display feature
     * rather than a tracking one, and a scan next to a lit screen costs little beside the screen.
     *
     * <p>Silent when it cannot run. No permission, Bluetooth off, or no tags with usable
     * accessory JSON all mean no sightings, which renders as no badges - the same as hearing
     * nothing. None of those is worth interrupting somebody looking at a map for.
     */
    private void startWatchingForNearbyTags() {
        this.stopWatchingForNearbyTags();

        // Asked for here rather than left to the ring button: this scan is what feeds the
        // battery/audible badge on every card, so it wants to be running as soon as the screen
        // opens, not only once somebody has separately triggered a ring. Silently doing nothing
        // without permission, as NearbyTagWatcher itself does, would just look like every tag
        // is permanently out of range.
        //
        // Before the empty-list check, so a first launch - where the beacons have not loaded
        // yet - still asks. And at most once per activity: the system dialog pauses this
        // activity, so a request fired from every onResume re-prompted the instant the user
        // denied, a loop with no way out on Android 10 and below.
        if (!BlePermissions.granted(this)) {
            if (!this.nearbyBlePermissionRequested) {
                this.nearbyBlePermissionRequested = true;
                Log.d(TAG, "Requesting BLE permission(s) to watch for nearby tags");
                ActivityCompat.requestPermissions(
                        this, BlePermissions.required(), NEARBY_PERMISSION_REQUEST_CODE);
            }
            return;
        }

        final Map<String, String> accessoryJsonByBeaconId = new HashMap<>();
        for (final var entry : this.beacons.entrySet()) {
            final String accessoryJson = entry.getValue().getInfo().getOwnedBeaconAccessoryJson();
            if (accessoryJson != null && !accessoryJson.isEmpty()) {
                accessoryJsonByBeaconId.put(entry.getKey(), accessoryJson);
            }
        }
        if (accessoryJsonByBeaconId.isEmpty()) {
            // Ordinary on a cold start: the beacons load asynchronously and are not here yet.
            // addBeaconToCurrent starts the watch once they arrive - see there.
            return;
        }

        this.nearbyWatchDisposable = new NearbyTagWatcher(
                AppDependencies.accessoryMacResolver(), this.sightingPersister::onSighting)
                .watch(this.getApplicationContext(), accessoryJsonByBeaconId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::onTagHeardNearby,
                        error -> Log.w(TAG, "Nearby tag watch ended unexpectedly", error),
                        this::onNearbyWatchEnded);

        this.nearbyStatusTickerDisposable = Observable
                .interval(1, TimeUnit.SECONDS, AndroidSchedulers.mainThread())
                .subscribe(tick -> this.updateBeaconCards());
    }

    /**
     * The scan died mid-session rather than being stopped: Bluetooth toggled off, or the
     * platform refused the scan (e.g. too many scan starts in a short window).
     *
     * <p>Cancellation does not come through here - disposing skips onComplete - so this only
     * runs for genuine mid-session death. Two things then must not keep happening: the
     * once-per-second ticker redrawing every card for a scan that can no longer produce
     * sightings, and the badges claiming tags are here based on a radio nobody is listening
     * to. And one thing must: a retry, or the nearby feature stays dead for the rest of the
     * session even after Bluetooth comes back. One attempt per 30 seconds is far under the
     * platform's scan-start budget, and each failed attempt completes again and reschedules,
     * so it self-heals whenever the radio returns.
     */
    private void onNearbyWatchEnded() {
        Log.i(TAG, "The nearby tag watch ended mid-session; retrying in 30s");
        this.stopWatchingForNearbyTags();
        this.updateBeaconCards();

        this.nearbyWatchRetryDisposable = Observable
                .timer(30, TimeUnit.SECONDS, AndroidSchedulers.mainThread())
                .subscribe(tick -> this.startWatchingForNearbyTags());
    }

    private void stopWatchingForNearbyTags() {
        if (this.nearbyWatchDisposable != null && !this.nearbyWatchDisposable.isDisposed()) {
            this.nearbyWatchDisposable.dispose();
        }
        this.nearbyWatchDisposable = null;
        if (this.nearbyStatusTickerDisposable != null
                && !this.nearbyStatusTickerDisposable.isDisposed()) {
            this.nearbyStatusTickerDisposable.dispose();
        }
        this.nearbyStatusTickerDisposable = null;
        if (this.nearbyWatchRetryDisposable != null
                && !this.nearbyWatchRetryDisposable.isDisposed()) {
            this.nearbyWatchRetryDisposable.dispose();
        }
        this.nearbyWatchRetryDisposable = null;
        // Nothing on screen may go on claiming a tag is here once we have stopped listening.
        this.nearbySightings.clear();
    }

    /**
     * Redraws one card when its tag is heard.
     *
     * <p>Only that card, and only when it exists: a sighting arrives per advertisement, which is
     * every second or two per tag, and redrawing the whole row that often would be visible.
     */
    private void onTagHeardNearby(final NearbyTagSighting sighting) {
        this.nearbySightings.record(sighting);

        final FrameLayout card = this.dynamicCardsForTag.get(sighting.getBeaconId());
        if (card != null) {
            this.showNearbyStatusOn(card, sighting, System.currentTimeMillis());
        }
    }

    /**
     * How recently a sighting has to have arrived for {@link #showNearbyStatusOn} to light the
     * pulse dot rather than dim it.
     *
     * <p>Under the one-to-three-second gap between advertisements a tag in range genuinely
     * produces, so the dot visibly lights and dims once per sighting instead of just staying lit
     * - which is the live-activity read this is for, in place of a number that either sat at
     * "0s" permanently (driven only by sightings) or needed a second ticker to mean anything.
     */
    private static final long PULSE_WINDOW_MS = 1_000L;

    /**
     * Replaces a card's "last updated" line while its tag is audible, and lights the pulse dot
     * beside it if a sighting arrived within {@link #PULSE_WINDOW_MS}.
     *
     * <p>The line says something different from "last updated two hours ago", which describes
     * when Apple's network last reported it: a sighting means this phone can hear it right now.
     * Showing both would need a taller card, and the row is already measured to the pixel - see
     * {@code TagCardLayoutTest}.
     *
     * <p>The line - and the dot with it - go back to the timestamp on their own once the
     * sighting ages out, because nothing announces that a tag has left; we simply stop hearing
     * it. See the {@code else} branch in {@code updateBeaconCards} that calls this only when a
     * fresh sighting exists.
     */
    private void showNearbyStatusOn(
            final FrameLayout card, final NearbyTagSighting sighting, final long nowMs) {
        final TextView line = card.findViewById(R.id.device_last_update);
        line.setText(this.getString(R.string.nearby_now_with_battery_and_signal,
                this.getString(NearbyTagLabel.shortBatteryLabel(sighting.getBatteryLevel())),
                NearbyTagLabel.signalStrengthBars(sighting.getRssi())));

        // Never negative: nowMs can be a hair behind seenAtMs when this runs right off the scan
        // callback, before the clock the caller reads has ticked past it.
        final long msSinceSighting = Math.max(0, nowMs - sighting.getSeenAtMs());
        final boolean pulsing = msSinceSighting < PULSE_WINDOW_MS;

        final ImageView pulse = card.findViewById(R.id.device_nearby_pulse);
        pulse.setVisibility(VISIBLE);
        pulse.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(card, pulsing
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOutlineVariant)));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 调用高德地图的生命周期方法
        if (this.mapProvider instanceof AMapProvider) {
            ((AMapProvider) this.mapProvider).onDestroy();
        }
    }

    private void reSchedulePeriodicTagLocationRefresher() {
        // (re-)schedule tag location refresher
        if (this.nextLocationRefreshTask != null) {
            refreshSchedulerHandler.removeCallbacks(this.nextLocationRefreshTask);
        }
        this.nextLocationRefreshTask = () -> {
            refreshSchedulerHandler.postDelayed(this.nextLocationRefreshTask, WAIT_BEFORE_REFETCH);
            this.refreshIfAllowed();
            this.rereadTheAccountIfAllowed(false);
        };
        refreshSchedulerHandler.postDelayed(this.nextLocationRefreshTask, WAIT_BEFORE_REFETCH);
    }

    private void refreshIfAllowed() {
        final long now = System.currentTimeMillis();

        final RefreshPolicy.Decision decision = this.refreshPolicy.decide(
                now,
                this.isAppleServiceInitialised(),
                this.initialFetchComplete,
                PythonAppleService.isBusy());

        if (!decision.shouldRefresh()) {
            Log.d(TAG, String.format("Skipping the scheduled refresh: %s (%s)",
                    decision.reason(), this.refreshPolicy.describeTimeSinceLastFetch(now)));
            return;
        }

        Log.d(TAG, "Performing automatic scheduled refresh of data for all tags...");
        this.fetchAndUpdateCurrentBeacons();
        Log.d(TAG, "Automatic scheduled refresh complete! Next automatic refresh will be in " + WAIT_BEFORE_REFETCH + " ms");
    }

    /**
     * Ask the account what it holds now, if it is time and nothing else is running.
     *
     * <p><b>Silent by design.</b> Nobody asked for this, so there is nothing to show and nothing
     * to report: it succeeds by the device list quietly being right, and by the map picking up a
     * tag that was added in Find My without anybody going to look for a button.
     *
     * <p>The device list is only rebuilt when something actually changed, because rebuilding it
     * moves rows under whoever is reading them.
     */
    private void rereadTheAccountIfAllowed(final boolean justResumed) {
        final long now = System.currentTimeMillis();

        final AccountReadPolicy.Decision decision = justResumed
                ? this.accountReadPolicy.decideOnResume(
                        now, this.accountIsLinked, PythonAppleService.isBusy())
                : this.accountReadPolicy.decide(
                        now, this.accountIsLinked, PythonAppleService.isBusy());

        if (!decision.shouldRead()) {
            return;
        }

        // Marked before it runs, not after. A read that takes twenty minutes queued behind a
        // location fetch must not earn another one the moment it finishes.
        this.accountReadPolicy.markRead(now);
        Log.d(TAG, "Re-reading the Apple account in the background");

        var async = new AccountRefresher(
                new KeychainMembershipRepository(
                        UserAuthDataStore.getInstance(this.getApplicationContext()),
                        new AppCryptographyUtil()),
                this.beaconRepo)
                .refresh()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        held -> Log.i(TAG, "Background account read holds " + held.size() + " tags"),
                        error -> {
                            // **Logged and nothing else, even for a refused sign-in.** Nobody
                            // asked for this read, so nothing may appear because of it - being
                            // thrown onto a login screen the moment the app opens, before the
                            // map has drawn and while locations may still be fetching perfectly
                            // well, is its own bug. The screens somebody presses handle it.
                            if (ICloudFailures.meansSignInAgain(error)) {
                                Log.w(TAG, "The account cannot be re-read until the user signs"
                                        + " in again; nothing is being changed from here", error);
                                return;
                            }
                            Log.w(TAG, "Background account read failed", error);
                        });
    }

    private void rememberWhetherAnAccountIsLinked() {
        var async = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(this.getApplicationContext()),
                new AppCryptographyUtil())
                .get()
                .firstOrError()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        held -> this.accountIsLinked = held.isPresent(),
                        error -> Log.w(TAG, "Could not tell whether an account is linked", error));
    }

    /**
     * Search for one tag the app had given up on, because somebody asked.
     *
     * <p><b>Through the manual fetch path, which the backoff does not touch.</b> That is the
     * whole point of the button: the tag is skipped by the scheduled fetches precisely because
     * it has answered nothing for months, and a person pressing "check now" is overriding that
     * judgement, which they are entitled to do.
     *
     * <p>Nothing here clears the ignored flag. A successful search does that on its own, in the
     * same place every other successful search does - see
     * {@code OwnedBeaconDao#recordSuccessfulScan}. A second path that cleared it separately
     * could disagree with the first, and would be the version that quietly un-ignores a tag that
     * still found nothing.
     */
    private void lookForAnIgnoredTagAgain(final String beaconId) {
        final BeaconData beacon = this.beacons.get(beaconId);
        if (beacon == null) {
            Log.w(TAG, "Asked to look for " + beaconId + " again, but it is not on this screen");
            return;
        }

        Log.i(TAG, "Looking again for " + beaconId + ", which had been set aside");
        Toast.makeText(this.getApplicationContext(), R.string.tag_ignored_retrying, LENGTH_LONG)
                .show();

        // **The whole week, not a day.** This tag was set aside precisely because it has not
        // reported in a very long time, so asking about the last twenty-four hours is close to
        // guaranteed to find nothing - and the person tapping "look again" would be told the
        // same thing they were told before, having done the one thing the screen offered them.
        var async = this.fetchLastReportsFor(
                        beaconId, beacon.getInfo().getOwnedBeaconPlistRaw(),
                        HOURS_TO_GO_BACK_FIRST_TIME)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        reports -> this.handleDeviceListChanged(),
                        error -> Log.e(TAG, "Looking again for " + beaconId + " failed", error));
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
                        if (this.mapProvider != null) {
                            this.mapProvider.animateCamera(
                                    location.getLatitude(),
                                    location.getLongitude(),
                                    CAMERA_ON_MAP_INITIAL_ZOOM,
                                    null
                            );
                        }
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

        final Optional<BeaconLocationReport> maybeLast = this.beaconLocations.lastLocationOf(beaconId);
        if (maybeLast.isEmpty()) {
            Log.w(TAG, "Can't navigate to a beacon that has no locations!");
            return;
        }
        final BeaconLocationReport lastLocation = maybeLast.get();
        Uri uri = Uri.parse(String.format(Locale.ROOT, "geo:%.7f,%.7f?q=%.7f,%.7f", lastLocation.getLatitude(), lastLocation.getLongitude(), lastLocation.getLatitude(), lastLocation.getLongitude()));

        // **Whatever the user's maps application is, not Google's.** This used to name
        // com.google.android.apps.maps, which is the wrong shape of answer twice over. geo: is
        // a standard scheme that Organic Maps, OsmAnd, Magic Earth and the rest all register
        // for, and naming one package means the system chooser - which already remembers what
        // this user picked last time - never gets to offer any of them. This app exists for
        // people who have opted out of one walled garden; sending them into another is the
        // wrong default.
        //
        // It also could not work. From Android 11 the app cannot see a package it has not
        // declared in <queries>, so resolveActivity returned null on every current device with
        // or without Google Maps, and the button silently did nothing. The manifest now
        // declares the geo: intent; without that entry this check fails again exactly as
        // before, so the two changes belong together.
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, uri);
        if (mapIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(mapIntent);
        } else {
            // Said out loud, because the alternative is a button that does nothing. A phone
            // with no maps application at all is unusual but entirely possible on a stripped
            // build, and "nothing happened" is the one outcome the user cannot act on.
            Log.e(TAG, "No installed application can open a geo: link for the visible tag");
            Toast.makeText(this, R.string.no_app_to_open_a_map_with, LENGTH_SHORT).show();
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
        this.onImportFilePicked(data, null);
    }

    /**
     * @param passcode the code to unlock the bundle with, or null on the first attempt. The
     *                 importer decides whether one is needed from the zip's own headers, so it
     *                 is never asked for until it is known to be required.
     */
    private void onImportFilePicked(Intent data, final String passcode) {
        Log.d(TAG, "File has been picked");

        // **Two chains, not one, and the split is the whole point.**
        //
        // Reading the zip and committing the tags is the import. Going to Apple for their
        // locations is a separate operation that happens to run next, and it fails for
        // completely unrelated reasons - a network, a session, an Anisette server. Joined into
        // one chain they shared an error handler, so every one of those reported "Error
        // occurred while importing new devices. Try to restart the app and retry" for an import
        // that had already succeeded and could be seen in the device list.
        //
        // Issues #19 and #26 are 34 comments of people working that out for themselves; three
        // of them fixed their "import error" by changing their Anisette server, which is not
        // consulted while reading a zip. A marker saying how far the chain got would fix the
        // message, but decoupling makes the wrong message impossible to write.
        var async = this.extractImportedData(data, passcode)
            .flatMap(this.beaconRepo::addNewImport)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                importData -> {
                    Toast.makeText(
                            this,
                            this.getString(R.string.loading_location_data_for_x_new_imported_devices, importData.getOwnedBeacons().size()),
                            LENGTH_LONG).show();
                    this.locateFreshlyImportedTags(importData);
                },
                error -> {
                    // Nothing was stored, so this really is an import failure and can say so.
                    // The fetch never runs, and the periodic refresh is still waiting on this.
                    this.initialFetchComplete = true;
                    this.onImportFailed(data, error);
                });
    }

    /**
     * The first fetch for tags that are already on the phone.
     *
     * <p><b>Started by a successful import, and answerable for nothing about it.</b> Whatever
     * happens here, the tags are stored and stay stored - so a failure is about locations, and
     * the message says that rather than retracting an import that worked.
     */
    private void locateFreshlyImportedTags(final ImportData storedBeacons) {
        /*
         * Fetching and parsing still run concurrently, but they are merged rather than
         * zipped: zip completed as soon as the single-emission parse branch did, which
         * cancelled every accessory after the first. See RxFlows#allThen for the full
         * account. The two doOnNext handlers write to different maps, so their order
         * relative to each other does not matter.
         */
        // **Deferred, because this is now called from the main thread.**
        //
        // `subscribeOn` moves the *subscription*, not the construction - and building this
        // expression is not free: `combine` streams every beacon into two maps and
        // `plistFallbacks` walks them again, both evaluated as arguments before `allThen` is
        // even entered. While this hung off the import chain it inherited that chain's IO
        // thread; splitting the two handed it to whatever called it, which is the UI thread.
        // Nothing here belongs there.
        var async = Completable.defer(() -> RxFlows.allThen(
                    // A last pass once everything has landed, for anything the per-accessory
                    // passes below could not resolve.
                    this.updateBeaconGeocodings(),
                    this.fetchLastReports(
                            BeaconRepository.plistFallbacks(storedBeacons.getOwnedBeacons()), HOURS_TO_GO_BACK_24H)
                            // **Geocoded as each accessory lands, not only at the end.** This
                            // used to be a bare doOnNext with the geocoding left to the `then`
                            // above, which runs after the whole batch - and a batch is one
                            // sequential fetch per tag, where a tag with no key alignment record
                            // takes minutes. So every card sat showing raw coordinates for the
                            // length of the run, despite geocoding being a Google call that had
                            // nothing to wait for. Cheap to repeat: a beacon whose location has
                            // not moved since its last geocoding is skipped.
                            .concatMap(reports -> {
                                this.addBeaconLocationsToCurrent(reports);
                                // Same reason as the periodic refresh: the model is not the
                                // screen, and waiting for the slowest tag to redraw the fast
                                // ones is the whole complaint.
                                this.runOnUiThread(this::showLastDeviceLocations);
                                return this.updateBeaconGeocodings()
                                        .andThen(Observable.just(reports));
                            }),
                    BeaconDataParser.parseAsync(BeaconCombinerUtil.combine(storedBeacons))
                            .doOnNext(this::addBeaconToCurrent)
            ))
            // **Explicit now that this is its own chain.** It used to inherit whatever thread
            // `addNewImport` emitted on; nothing here is safe to start on the main one.
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            // An import is a first fetch too. Without this the periodic refresh stays disabled
            // for the rest of the session whenever the app started with nothing stored, which
            // is exactly the case where someone has just imported their first zip.
            .doFinally(() -> this.initialFetchComplete = true)
            .subscribe(() -> {
                this.showLastDeviceLocations();
                Log.i(TAG, "Finished visualising new location reports!");
            }, error -> this.onFirstFetchAfterImportFailed(error));
    }

    /**
     * What to say when the zip could not be read, or the tags could not be stored.
     *
     * @param data the picked file, kept so a locked bundle can be retried with a code rather
     *             than sending the user back to the picker
     */
    private void onImportFailed(final Intent data, final Throwable error) {
        Log.e(TAG, "Error occurred while importing new devices!", error);

        switch (ImportOutcome.of(error)) {
            case ASK_FOR_THE_PASSCODE:
                // A question, not a failure. The exporter locks bundles by default, so this is
                // the ordinary path rather than something having gone wrong.
                BundlePasscodeDialog.show(
                        this,
                        ZipImporterException.reasonOf(error)
                                == ZipImporterException.Reason.WRONG_PASSCODE,
                        code -> this.onImportFilePicked(data, code));
                return;

            case REPORT_THE_IMPORT:
                // **An import nobody can explain goes to the report page, not to a toast.**
                //
                // Every named reason has advice worth giving - the wrong file, a damaged zip, a
                // passcode. This one has none, and what it used to say was "try to restart the
                // app and retry", which cannot help and asks somebody to repeat the thing that
                // just failed.
                Log.w(TAG, "Import failed for a reason nothing here can name", error);
                this.startActivity(ErrorReportActivity.intentFor(
                        this, describe(error), R.string.error_report_body_import));
                return;

            default:
                Toast.makeText(this, importFailureMessage(error), LENGTH_LONG).show();
        }
    }

    /**
     * What to say when the tags are stored and Apple could not be asked where they are.
     *
     * <p><b>Never that the import failed.</b> It did not - the tags are in the database and in
     * the device list, and telling somebody otherwise sends them to re-import a bundle that
     * imported fine. That was the state of issues #19 and #26.
     *
     * <p>The two causes with real handling keep it: a session Apple has stopped accepting goes
     * back to sign-in, and one wanting a code gets asked for one. What is left is overwhelmingly
     * the Anisette server, which is why the dialog names it - it is the answer three reporters
     * arrived at themselves, one of them 25 comments in.
     *
     * <p><b>And no report button, deliberately.</b> The bug page belongs to the zip half. This
     * half fails for reasons that are somebody's server being down or their phone being on a
     * train, it retries by itself every minute, and inviting a report each time it happens would
     * fill the tracker with weather.
     */
    private void onFirstFetchAfterImportFailed(final Throwable error) {
        Log.e(TAG, "Tags were imported, but their first location fetch failed", error);

        if (isAccountRestoreFailure(error)) {
            handleAccountRestoreFailureOnUiThread();
            return;
        }

        // Self-handling and asynchronous: it asks Apple whether this session actually wants a
        // code, and does nothing if it does not. Harmless to call alongside the dialog.
        this.askForACodeIfTheSessionNeedsOne();

        ImportedButNotLocatedDialog.show(
                this, describe(error), this::showSettingsPage);
    }

    /**
     * The failure in the words it arrived in, for pasting into a report.
     *
     * <p>Class name and message rather than a stack trace: the trace is in the log the page
     * offers, and a screenful of frames is not something anybody reads off a phone.
     */
    private static String describe(final Throwable error) {
        return ErrorReportActivity.describe(error);
    }

    /**
     * What to tell somebody whose import did not work.
     *
     * <p>Almost always they picked the wrong file, and for a long time the answer to that was
     * "try to restart the app and retry" - advice for a broken app, given to somebody who
     * chose their holiday photos. The generic message is kept for the cases that really are
     * unexplained, which is the only place it was ever true.
     */
    private static int importFailureMessage(Throwable error) {
        switch (ZipImporterException.reasonOf(error)) {
            case NOT_A_ZIP:
                return R.string.import_failed_not_a_zip;
            case NOT_AN_EXPORT:
                return R.string.import_failed_not_an_export;
            case DAMAGED:
                return R.string.import_failed_damaged;
            case NO_TAGS:
                return R.string.import_failed_no_tags;
            case UNREADABLE:
                return R.string.import_failed_unreadable;
            case WRONG_PASSCODE:
                return R.string.import_failed_wrong_passcode;
            case LOCKED:
                // Normally answered with a prompt rather than a message. Reached only if the
                // user dismissed it, which is them declining, so it says what is still true.
                return R.string.import_failed_locked;
            default:
                return R.string.error_occurred_while_importing_new_devices_try_to_restart_the_app_and_retry;
        }
    }

    private void onExportLogsToLocationPicked(@lombok.NonNull Intent data) {
        Log.d(TAG, "Export target location picked");

        Uri writeTarget = data.getData();
        if (writeTarget == null) {
            Log.w(TAG, "No write target given! Stopping here");
            return;
        }

        // **Off the main thread, which it was not.** Four blocking things happen here - reading
        // the database for the import provenance, spawning `logcat` and reading it out, running
        // it through the redactor, and writing the file. All of them were on the UI thread. The
        // database one would throw outright (Room refuses main-thread queries), and the rest
        // were an ANR waiting for a slow enough device.
        var async = Observable.fromCallable(() -> {
                    final Import lastImport =
                            OpenTagViewerDatabase.getInstance(this.getApplicationContext())
                                    .importDao().getMostRecent();

                    final String logLines = LogCollectorUtil.getLastLogsWithHeader(
                            BuildConfig.VERSION_NAME,
                            BuildConfig.BUILD_COMMIT,
                            lastImport == null ? null : lastImport.exportedVia);

                    // **Redacted here too, and not because this button is more dangerous than
                    // the other one - because it is the same log.** This wrote a raw logcat
                    // while the error page's copy went through the redactor, which is two
                    // answers to "is my Apple ID in this file" from one app. The file from this
                    // button is the one that historically got attached to issues.
                    final LogRedactor.Redacted cleaned =
                            AppDependencies.logRedactor().redact(logLines);
                    if (cleaned == null) {
                        // Nothing written, deliberately. A raw log cannot be un-posted, and
                        // somebody exporting one is on their way to attaching it somewhere.
                        return new ExportedLog(null, null);
                    }

                    try (OutputStream os =
                                 this.getContentResolver().openOutputStream(writeTarget)) {
                        final BufferedWriter bw =
                                new BufferedWriter(new OutputStreamWriter(os));
                        bw.write(cleaned.getText());
                        bw.flush();
                        bw.close();
                    }
                    return new ExportedLog(writeTarget, cleaned.getSummary());
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        written -> {
                            if (written.target == null) {
                                Log.w(TAG, "The log could not be cleaned, so none was written");
                                Toast.makeText(
                                        this,
                                        R.string.export_logs_cannot_be_cleaned,
                                        LENGTH_LONG
                                ).show();
                                return;
                            }

                            Log.d(TAG, "Logs export to " + written.target + " complete!");
                            Toast.makeText(
                                    this,
                                    this.getString(
                                            R.string.export_logs_cleaned, written.summary),
                                    LENGTH_LONG
                            ).show();
                        },
                        error -> {
                            Log.e(TAG, "Failed to save file", error);
                            Toast.makeText(
                                    this,
                                    R.string.failed_to_export_log_file,
                                    LENGTH_LONG
                            ).show();
                        });
    }

    /** Where the log went and what came out of it, or a null target meaning nothing was written. */
    private static final class ExportedLog {
        private final Uri target;
        private final String summary;

        private ExportedLog(final Uri target, final String summary) {
            this.target = target;
            this.summary = summary;
        }
    }

    private Observable<ImportData> extractImportedData(Intent data, final String passcode) {
        return Observable.fromCallable(() -> {
            try {
                Uri zipFileUri = data.getData();
                if (zipFileUri == null) {
                    // A result with no file in it. Nothing to diagnose, but it is still the
                    // picked file's problem rather than the app's, so say so as such.
                    throw new ZipImporterException(
                            ZipImporterException.Reason.UNREADABLE, "Picker returned no file");
                }

                var util = new AppleZipImporterUtil(this.getApplicationContext());
                return util.extractZip(zipFileUri, passcode);

            } catch (ZipImporterException e) {
                // Rethrown as itself rather than wrapped: the reason it carries is what decides
                // what the user is told, and it only survives if the exception does.
                Log.e(TAG, "Import failed (" + e.getReason() + ")", e);
                throw e;
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

        IMapProvider.CameraPosition pos = this.mapProvider != null ? this.mapProvider.getCameraPosition() : null;

        Log.d(TAG, "Going to the history page for beaconId=" + beaconId);
        Intent viewHistoryIntent = new Intent(this, HistoryViewActivity.class);
        viewHistoryIntent.putExtra("beaconId", beaconId);
        if (pos != null) {
            viewHistoryIntent.putExtra("lon", pos.getLongitude());
            viewHistoryIntent.putExtra("lat", pos.getLatitude());
            viewHistoryIntent.putExtra("zoom", pos.getZoom());
        }

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

    /**
     * Toggle continuous ping (repeated scan + play-sound-nearby, see
     * {@code AccessorySoundTrigger#playSoundContinuously}) for this card's tag - on until tapped
     * again, unlike {@code DeviceInfoActivity}'s one-shot "Play Sound Nearby".
     *
     * <p>Only one tag at a time: starting it for a different tag stops whichever was running,
     * since it is one Bluetooth radio and one thing to listen for.
     */
    public void onClickRing(View view) {
        Log.d(TAG, "The ring button was clicked");

        final String beaconId = this.dynamicCardsForTag.entrySet()
                .stream().filter(kvp -> kvp.getValue().findViewById(R.id.device_ring_button_container) == view)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Click ring event was raised by a Beacon Device's card, but the beaconId could not be found for it!"));

        final boolean wasRunningForThisTag = beaconId.equals(this.continuousPingBeaconId);
        this.stopContinuousPing();
        if (wasRunningForThisTag) {
            return;
        }

        if (!BlePermissions.granted(this)) {
            Log.d(TAG, "Requesting BLE permission(s) before starting continuous ping for beaconId=" + beaconId);
            this.ringPermissionRequestBeaconId = beaconId;
            ActivityCompat.requestPermissions(this, BlePermissions.required(), RING_PERMISSION_REQUEST_CODE);
            return;
        }

        this.startContinuousPing(beaconId);
    }

    private void startContinuousPing(final String beaconId) {
        final BeaconData beaconData = this.beacons.get(beaconId);
        if (beaconData == null) {
            Log.w(TAG, "Cannot start continuous ping: no loaded data for beaconId=" + beaconId);
            return;
        }
        final String accessoryJson = beaconData.getInfo().getOwnedBeaconAccessoryJson();

        this.continuousPingBeaconId = beaconId;
        final FrameLayout container = this.dynamicCardsForTag.get(beaconId);
        if (container != null) {
            TagCardHelper.toggleRingActive(container, true);
        }

        this.continuousPingDisposable = AppDependencies.accessorySoundTrigger()
                .playSoundContinuously(this.getApplicationContext(), accessoryJson)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        update -> this.handleContinuousPingUpdate(beaconId, update),
                        error -> {
                            // playSoundContinuously's contract is to never error onto this path -
                            // see its interface doc - so reaching here means a bug in that
                            // contract, not an ordinary "not found nearby" or "no permission".
                            Log.e(TAG, "Continuous ping stopped unexpectedly for beaconId=" + beaconId, error);
                            this.stopContinuousPing();
                        });
    }

    /**
     * Shows continuous ping's current phase on the card's ring label - "Scanning...",
     * "Connecting...", "Sending..." - so it reads as active work rather than nothing happening,
     * without a toast firing every few seconds for as long as it runs.
     *
     * <p>Between cycles ({@link BleSoundTriggerPhase#DONE}) a successful write shows "Ringing!"
     * rather than jumping straight back to "Stop" - the write itself is near-instant, so without
     * this the sequence reads as scan, connect, done, with no visible moment where it actually
     * worked. It shows for the whole {@code CONTINUOUS_PING_PAUSE_MS} gap before the next cycle's
     * scan starts, which is also roughly how long an AirTag's chirp lasts. A not-found or failed
     * attempt goes back to "Stop" directly and keeps looping - the tag may come into range on
     * the next cycle. {@link BleSoundTriggerStatus#MISSING_PERMISSION} and
     * {@link BleSoundTriggerStatus#NO_CANDIDATE_MACS} do not: nothing about waiting and trying
     * again fixes either, so looping on them is pure battery burn with no chance of succeeding -
     * this stops the loop and says why instead.
     */
    private void handleContinuousPingUpdate(final String beaconId, final BleSoundTriggerUpdate update) {
        Log.d(TAG, "Continuous ping update for beaconId=" + beaconId + ": " + update.getPhase()
                + (update.getResult() == null ? "" : " (" + update.getResult().getStatus() + ")"));

        this.sightingPersister.keepWhatTheSightingProved(beaconId, update);

        // A card for a beaconId other than the one this loop is for stopped existing (e.g. the
        // tag left the visible list) or continuous ping was stopped/switched to another tag
        // since this update was emitted - either way, there is nothing left to show it on.
        if (!beaconId.equals(this.continuousPingBeaconId)) {
            return;
        }

        if (update.getPhase() == BleSoundTriggerPhase.DONE) {
            final BleSoundTriggerStatus status = update.getResult().getStatus();
            if (status == BleSoundTriggerStatus.MISSING_PERMISSION
                    || status == BleSoundTriggerStatus.NO_CANDIDATE_MACS) {
                Log.w(TAG, "Stopping continuous ping for beaconId=" + beaconId
                        + ": unrecoverable status " + status);
                this.stopContinuousPing();
                Toast.makeText(this, status == BleSoundTriggerStatus.MISSING_PERMISSION
                                ? R.string.play_sound_permission_denied
                                : R.string.play_sound_no_candidate_macs,
                        LENGTH_LONG).show();
                return;
            }
        }

        final FrameLayout container = this.dynamicCardsForTag.get(beaconId);
        if (container == null) {
            return;
        }

        final int labelRes;
        switch (update.getPhase()) {
            case CONNECTING:
                labelRes = R.string.ring_status_connecting;
                break;
            case TRIGGERING:
                labelRes = R.string.ring_status_triggering;
                break;
            case DONE:
                labelRes = update.getResult().getStatus() == BleSoundTriggerStatus.SUCCESS
                        ? R.string.ring_status_success
                        : R.string.stop_ringing;
                break;
            case SCANNING:
            default:
                labelRes = R.string.ring_status_scanning;
                break;
        }
        TagCardHelper.setRingLabel(container, this.getString(labelRes));

        // The spinner runs for SCANNING/CONNECTING/TRIGGERING and stops at DONE - a label
        // alone ("Scanning...", "Connecting...") can sit on screen for several seconds with
        // nothing else moving, which reads as stuck rather than as work in progress.
        TagCardHelper.setRingLoading(container, update.getPhase() != BleSoundTriggerPhase.DONE);
    }

    private void stopContinuousPing() {
        if (this.continuousPingDisposable != null && !this.continuousPingDisposable.isDisposed()) {
            this.continuousPingDisposable.dispose();
        }
        this.continuousPingDisposable = null;

        if (this.continuousPingBeaconId != null) {
            final FrameLayout container = this.dynamicCardsForTag.get(this.continuousPingBeaconId);
            if (container != null) {
                TagCardHelper.toggleRingActive(container, false);
                // In case this stopped mid-attempt (spinner showing) rather than between
                // cycles - otherwise the icon stays hidden behind a spinner that will never
                // update again.
                TagCardHelper.setRingLoading(container, false);
            }
        }
        this.continuousPingBeaconId = null;
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
        // Somebody is signed in, which is the only moment this is worth raising: they can see
        // what they already have, and what switching would cost them.
        // **Asked before the dialog runs, not after.** `AnisetteUpgradeDialog.offerIfDue` marks
        // itself as offered by mutating this very settings object, deliberately, so that a
        // dismissal counts. That means asking afterwards whether it was due always answers "no"
        // - and the iCloud offer below, whose whole condition is "not while Anisette is
        // outstanding", would open on top of it. Both dialogs appeared at once, which is exactly
        // the thing the deferral exists to prevent.
        final boolean anisetteWasDue = this.userSettings.shouldOfferLocalAnisette(true);

        this.offerLocalAnisetteUpgradeIfDue();

        // And, for anybody not already reading their account, the offer to start. Second and
        // only when the screen is free: both are due at once for somebody updating, and the
        // Anisette one wins because it is about the session continuing to work. Nothing marks
        // this one as made in the meantime, so it returns on the next launch.
        if (!anisetteWasDue) {
            this.offerICloudSetupIfDue();
        }

        // else stay here & restore the account.
        // Note: FindMy 0.9.x embeds the anisette URL in the account JSON itself,
        // so we no longer need to read userSettings.getAnisetteServerUrl() here.
        // Produce Anisette on this device where possible, rather than relaying every refresh
        // through a public Anisette server. Falls back to the configured server by itself.
        var asyncAppleService = PythonAuthService.restoreAccount(
                    userAuth.get(),
                    // Somebody is signed in here, so an unchosen mode keeps them on whatever
                    // already works: switching would present Apple with a different machine.
                    AppDependencies.anisette(this, this.userSettings, true))
            .map(appleAccount -> {
                this.appleService = PythonAppleService.setup(appleAccount);

                // **The other door.** getAccount now hands back an account that needs a second
                // factor rather than discarding it (see main.py, and issue #43), so a restore
                // can land here already unusable. Asked on the main thread because it puts a
                // view up; harmless when the session is fine, which is almost always.
                this.runOnUiThread(this::askForACodeIfTheSessionNeedsOne);

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
            // Not on every start. This screen is rebuilt whenever the theme, language or map
            // provider changes - AppCompat relaunches every activity in the process - and an
            // unconditional fetch here meant each of those cost a full walk of every tag's
            // key history. The cached locations are already on screen by this point, so
            // skipping is invisible except for the absence of the "locating" banner.
            this.refreshPolicy.decide(
                        System.currentTimeMillis(), true, true, PythonAppleService.isBusy())
                    .shouldRefresh()
            // From the model rather than from `beacons`, which is what came out of the parser
            // and so still has the owner's own devices in it - see plistsToFetch.
            ? this.fetchLastReports(this.plistsToFetch())
            : this.skipTheStartupFetch()
        ).flatMap(o -> o)
        .doOnNext(this::addBeaconLocationsToCurrent)
        .flatMap(o -> this.updateBeaconGeocodings().andThen(Observable.just(o)))
        .subscribeOn(Schedulers.computation())
        .observeOn(AndroidSchedulers.mainThread())
        // On termination rather than on a result. With no beacons stored - a first run, or
        // before any import - this stream completes without ever emitting, so setting the flag
        // in onNext left it false forever and the periodic refresh never ran again.
        .doFinally(() -> this.initialFetchComplete = true)
        .subscribe(lastReports -> {
            //Toast.makeText(this.getApplicationContext(), "Yay, got last reports!", LENGTH_SHORT).show();
            TagCardHelper.toggleRefreshLoadingAll(this.dynamicCardsForTag, false);
            this.showLastDeviceLocations();
            Log.i(TAG, "Successfully retrieved latest reports!");
        }, error -> {
            Log.e(TAG, "Error while restoring account and trying to get latest beacons", error);
            TagCardHelper.toggleRefreshLoadingAll(this.dynamicCardsForTag, false);

            if (isAccountRestoreFailure(error)) {
                // Most likely cause: a session blob saved by FindMy 0.7.6 that 0.9.x cannot
                // restore. Wipe the bad blob and route the user back to login with a hint.
                Log.w(TAG, "Account restore failed; clearing saved auth and prompting re-login");
                handleAccountRestoreFailureOnUiThread();
                return;
            }

            // **Not every fetch failure is weather.** The comment below is true of most of them
            // and completely wrong about one: once Apple moves the session to REQUIRE_2FA every
            // fetch fails its state check before a request leaves the phone, and no amount of
            // retrying fixes it. That was permanent and silent - stale pins, nothing on screen.
            this.askForACodeIfTheSessionNeedsOne();
            //Toast.makeText(this.getApplicationContext(), "Error while trying to fetch data for beacons", LENGTH_SHORT).show();
            // this error just happens every now and then. It's no big deal, we will retry automatically eventually...
        });
    }

    /**
     * Invite somebody updating from an older version to stop signing in through a server.
     *
     * <p>They were left on their server deliberately, because moving a session presents Apple
     * with a different machine and costs a sign-in. That trade is theirs to make, so it is put
     * to them once - see {@link AnisetteUpgradeDialog}, which decides whether there is anything
     * to ask and records that it was asked.
     *
     * <p>Accepting saves first and signs out only once the save has landed. The other order
     * looks the same right up until the write loses a race with the activity finishing, at
     * which point they sign in again and arrive back on the server they just left.
     */
    private void offerLocalAnisetteUpgradeIfDue() {
        this.runOnUiThread(() -> AnisetteUpgradeDialog.offerIfDue(this, this.userSettings,
            accepted -> {
                var saved = this.userSettingsRepo.storeUserSettings(this.userSettings);

                var async = (accepted ? saved.andThen(this.userAuthRepo.clearUser()) : saved)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(() -> {
                            if (!accepted) {
                                return;
                            }
                            Log.i(TAG, "switching to local Anisette; signing in again");
                            this.finish();
                            this.sendToLogin();
                        }, error -> Log.e(TAG,
                                "Failed to record the local Anisette choice", error));
            }));
    }

    /**
     * The overlay that rescues a session Apple wants a code for. Built on demand, because most
     * sessions never need it.
     */
    private TwoFactorAgainOverlay twoFactorAgain;

    /**
     * Whether a check on the session's state is already in flight or answered.
     *
     * <p>Cleared when a code rescues the session, so a later staleness is caught too - and only
     * then. Not cleared on sign-out, because there is nothing left to ask about.
     */
    private boolean alreadyAskingAboutTheSession = false;

    /**
     * Ask whether this session needs a code, and put the overlay up if it does.
     *
     * <p><b>Called from both doors onto the same state.</b> A restore can hand back an account
     * already in {@code REQUIRE_2FA}, and a session that restored perfectly can be moved there
     * by Apple later, failing every fetch from that moment. Before this, those two produced
     * completely different behaviour - a forced re-login, and permanent silence - for one
     * condition with one fix.
     *
     * <p>Cheap on the common path: a healthy account answers without Python asking it anything,
     * and this only runs when a fetch has already failed outright or a restore has just landed.
     *
     * <p>Does nothing if the overlay is already up, so a periodic refresh ticking behind it
     * cannot ask Apple for a second code while somebody is typing the first.
     */
    private void askForACodeIfTheSessionNeedsOne() {
        // **Once, however many accessories failed.** Every tag in the batch fails for the same
        // reason when a session goes stale, and each failure calls this - so without the latch a
        // six-tag account would ask Apple for six codes, or sign out six times over. The check
        // and the set are both on the main thread, which is why a plain boolean is enough.
        if (this.appleService == null
                || this.alreadyAskingAboutTheSession
                || (this.twoFactorAgain != null && this.twoFactorAgain.isShowing())) {
            return;
        }
        this.alreadyAskingAboutTheSession = true;

        var async = PythonAuthService.secondFactorMethodsIfNeeded(this.appleService.getAccount())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(needed -> {
                    if (needed.isEmpty()) {
                        return; // nothing wrong with this session
                    }

                    final List<PythonAuthService.AuthMethod> methods = needed.get();

                    if (methods.isEmpty()) {
                        // **A code is needed and there is no way to ask for one.** Real sessions
                        // reach this: FindMy.py reports "Unexpected login state after reauth ...
                        // Please log in again". Showing an empty code box would be worse than
                        // the bug this replaces, so this is the case that still signs out.
                        Log.w(TAG, "The session needs a code but offers no way to send one;"
                                + " signing out so the user can sign in properly");
                        this.handleAccountRestoreFailureOnUiThread();
                        return;
                    }

                    Log.i(TAG, "The session needs a second factor; asking for one");
                    this.showTheTwoFactorOverlay(methods);
                }, error -> Log.w(TAG,
                        "Could not work out whether this session needs a code", error));
    }

    private void showTheTwoFactorOverlay(final List<PythonAuthService.AuthMethod> methods) {
        if (this.twoFactorAgain == null) {
            this.twoFactorAgain = new TwoFactorAgainOverlay(
                    this,
                    this.findViewById(R.id.two_factor_again_overlay),
                    // Rescued: the session works again, so do what the failed fetch was for.
                    // Unconditionally rather than through refreshIfAllowed, which would consult
                    // the interval and usually decline - the fetch that just failed does not
                    // count as one that happened, and the user has earned an answer by typing
                    // a code.
                    () -> {
                        Log.i(TAG, "session rescued; fetching what the stale session could not");
                        // Armed again: this session is healthy now, and could go stale later.
                        this.alreadyAskingAboutTheSession = false;
                        this.fetchAndUpdateCurrentBeacons();
                    },
                    // Given up on: delete the login and send them to sign in properly. The same
                    // recovery a failed restore has always used.
                    this::handleAccountRestoreFailureOnUiThread);
        }

        this.twoFactorAgain.show(methods);
    }

    /**
     * Offer to connect an iCloud account, once, to anybody who has not.
     *
     * <p><b>Asked after the membership lookup rather than off {@link #accountIsLinked}.</b> That
     * field is filled in asynchronously, and reading it here would usually catch its default of
     * false - offering the account to somebody who already has one connected, and burning their
     * one-and-only prompt on a question that does not apply to them.
     *
     * <p>Declining still saves, because the flag recording that the offer was made is written
     * into the same settings object. Without that write the prompt returns on every launch,
     * which is precisely what somebody happy importing zips should never see.
     */
    private void offerICloudSetupIfDue() {
        var async = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(this.getApplicationContext()),
                new AppCryptographyUtil())
                .get()
                .firstOrError()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        held -> ICloudSetupOfferDialog.offerIfDue(
                                this, this.userSettings, held.isPresent(), this::recordICloudOffer),
                        error -> Log.w(TAG,
                                "Could not tell whether an account is linked, so not offering"
                                        + " to connect one", error));
    }

    /** Persist the answer, and act on it if they said yes. */
    private void recordICloudOffer(final boolean accepted) {
        var async = this.userSettingsRepo.storeUserSettings(this.userSettings)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    if (accepted) {
                        Log.i(TAG, "taking them to connect an iCloud account");
                        this.fetchFromICloudLauncher.launch(
                                new Intent(this, FetchFromICloudActivity.class));
                    }
                }, error -> Log.e(TAG, "Failed to record the iCloud offer", error));
    }

    private static boolean isAccountRestoreFailure(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof PythonAccountLoginException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Send somebody back to sign in, and tell them why.
     *
     * <p>This used to be a toast asserting that the FindMy library had been updated - true of
     * the one migration it was written for, and wrong every other time. What a person needs
     * here is what happened, and the reassurance that their tags and history are still on the
     * phone; the explaining is done by the login screen, because this one is finishing.
     *
     * <p><b>The address is read before the account is cleared</b>, which is the whole reason
     * this is not two independent steps: clearing is what destroys it.
     */
    private void handleAccountRestoreFailureOnUiThread() {
        final String email = signedInEmailOrNull();

        // Let go of the Apple session too, closing its HTTP session and sockets rather
        // than leaving them to a finaliser that cannot do it. See issue #133.
        PythonAppleService.forget();


        var disposable = this.userAuthRepo.clearUser()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    this.finish();
                    this.sendToLoginBecauseTheSessionExpired(email);
                }, err -> {
                    Log.e(TAG, "Failed to clear stale auth blob during restore-failure recovery", err);
                    // Fall through silently — at worst the user has to log out manually.
                });
    }

    /**
     * The signed-in address, or null if it cannot be read.
     *
     * <p>Deliberately incurious about why it might not be: this runs while recovering from an
     * account that has already failed to restore, and a missing address costs a prefilled
     * field, not the recovery.
     */
    private String signedInEmailOrNull() {
        try {
            return this.userAuthRepo.getUserAuth().blockingFirst()
                    .map(stored -> stored.getUser().getAccount().getInfo().getAccountName())
                    .orElse(null);
        } catch (final Exception e) {
            Log.w(TAG, "Could not read the signed-in address to prefill the login screen", e);
            return null;
        }
    }

    /**
     * Takes tags into the screen's model, <b>dropping the ones the user has chosen not to see</b>.
     *
     * <p><b>The one place that filter can go and cover everything.</b> {@link #beacons} is not
     * just what the carousel draws from - it is also where both fetch paths get their list, the
     * startup one at {@code loadEverything} and the periodic one in
     * {@link #fetchAndUpdateCurrentBeacons()}. So a tag that never enters here is never drawn
     * <i>and</i> never searched for, which is the whole point: an Apple device located only
     * through the crowd-sourced network updates so rarely that asking about it costs a slot in
     * the batch and buys nothing.
     *
     * <p>Filtering at the two fetch sites instead would have been three places to keep in step,
     * and the failure mode of getting it wrong is silent both ways round. See
     * {@link TagVisibility}.
     */
    private synchronized void addBeaconToCurrent(final List<BeaconInformation> allBeaconInformation) {
        final List<BeaconInformation> newBeaconInformation = TagVisibility.visible(
                allBeaconInformation, this.userSettings.shouldShowAppleDevices());

        final int hidden = allBeaconInformation.size() - newBeaconInformation.size();
        if (hidden > 0) {
            Log.i(TAG, "Leaving out " + hidden + " of the owner's own Apple devices, which are"
                    + " neither shown nor searched for unless the setting is on");
        }

        newBeaconInformation.forEach(beacon -> {
            final String beaconId = beacon.getBeaconId();
            if (this.beacons.containsKey(beaconId)) {
                Log.d(TAG, "Replacing existing beacon info for beaconId=" + beaconId);
            }
            this.beacons.put(beaconId, new BeaconData(beacon, Collections.emptyList()));
        });

        // The nearby watch could not start from onResume on a cold launch: it reads
        // this.beacons, which was still empty because this load runs asynchronously, and
        // nothing retried once the tags arrived - so the whole session had no pulse, no
        // Nearby line, and no passive alignment correction until the app was backgrounded
        // and reopened. Started here, once, when there is finally something to watch for.
        // Guarded on the disposable so the periodic account refresh, which also lands here,
        // does not bounce a running scan - Android silently blocks an app that starts scans
        // too often.
        if (!newBeaconInformation.isEmpty() && this.nearbyWatchDisposable == null) {
            // Re-checked on the main thread: this load finishes on a background thread, and by
            // the time the post runs, onResume may have started the watch already.
            this.runOnUiThread(() -> {
                if (this.nearbyWatchDisposable == null && !this.isFinishing()) {
                    this.startWatchingForNearbyTags();
                }
            });
        }
    }

    private synchronized void addBeaconLocationsToCurrent(final Map<String, List<BeaconLocationReport>> newItems) {
        newItems.forEach((beaconId, reports) -> {
            final int before = this.beaconLocations.sizeOf(beaconId);
            final int after = this.beaconLocations.merge(beaconId, reports);

            Log.d(TAG, String.format(
                    "Location history for beaconId=%s: %d held + %d fetched = %d after de-duplication",
                    beaconId, before, reports.size(), after));
        });
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

            final Optional<BeaconLocationReport> maybeLast = this.beaconLocations.lastLocationOf(beaconId);
            if (maybeLast.isEmpty()) {
                Log.d(TAG, "Can't update geocoding for beacon=" + beaconId + " because it contained no locations");
                continue;
            }

            BeaconLocationReport lastLocation = maybeLast.get();
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

            final Optional<BeaconLocationReport> maybeLast = this.beaconLocations.lastLocationOf(beaconId);
            if (maybeLast.isEmpty()) {
                Log.d(TAG, "No location is currently known for beaconId=" + beaconId + ", so it cannot be drawn. Skipping.");
                continue;
            }

            this.showBeaconOnMap(beacon, maybeLast.get());
        }
        this.updateBeaconCards();
    }

    private synchronized void showBeaconOnMap(final BeaconInformation beacon, final BeaconLocationReport lastLocation) {
        final String beaconId = beacon.getBeaconId();
        
        if (this.mapProvider == null) {
            Log.w(TAG, "Map provider is not ready yet, cannot show beacon");
            return;
        }

        if (this.currentMarkers.containsKey(beaconId)) {
            // remove the old marker and add a new one
            Log.d(TAG, "Going to replace the existing marker for beaconId=" + beaconId);
            this.mapProvider.removeMarker(beaconId);
        }
        Log.d(TAG, "Going to add new marker for beaconId=" + beaconId);

        // 创建自定义标记图标
        android.graphics.Bitmap iconBitmap;
        // **Resolved from the theme, not read from the colour resource.** The two agree in every
        // built-in theme, including at night - and stop agreeing the moment system colours are
        // on, because DynamicColors rewrites the theme attribute and cannot rewrite a fixed
        // value in colors.xml. The cards took the wallpaper's tint and the pins did not. See
        // MarkerPalette.
        if (beacon.isEmojiFilled()) {
            iconBitmap = VectorImageGeneratorUtil.makeMarker(
                    getResources(),
                    beacon.getEmoji(),
                    MarkerPalette.fill(this));
        } else {
            iconBitmap = VectorImageGeneratorUtil.makeMarker(
                    getResources(),
                    R.drawable.apple,
                    MarkerPalette.fill(this),
                    MarkerPalette.icon(this)
            );
        }

        // 使用抽象接口添加标记
        MapMarker marker = MapMarker.builder()
                .id(beaconId)
                .latitude(lastLocation.getLatitude())
                .longitude(lastLocation.getLongitude())
                .icon(iconBitmap)
                // Every refresh removes and re-adds the markers, so the selected one has to be
                // built raised. Setting it only on selection would let the next refresh drop
                // it back underneath the pile without the user touching anything.
                .zIndex(this.markerFocus.zIndexFor(beaconId))
                .build();
        
        String markerId = this.mapProvider.addMarker(marker);
        this.currentMarkers.put(beaconId, markerId); // 存储markerId而不是Marker对象

        if (this.currentMarkers.size() == 1) {
            // for the first marker, navigate to it smoothly on the map!
            this.goToBeaconOnMap(beaconId, CAMERA_ON_MAP_INITIAL_ZOOM);
        }
    }

    private void setupTagScrollArea() {
        HorizontalScrollView scrollContainer = this.findViewById(R.id.tags_scrollable_area);

        // The cards size themselves to their content now, so nothing absorbs the navigation bar
        // any more. This used to be hidden by the area's fixed 240dp height, which held about
        // 70dp of slack below the card.
        WindowPaddingUtil.insertUIBottomPadding(scrollContainer);

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
            if (this.mapProvider == null) {
                Log.w(TAG, "Map provider is not ready yet");
                return;
            }
            
            // 从beaconLocations获取位置信息
            final Optional<BeaconLocationReport> maybeLast = this.beaconLocations.lastLocationOf(beaconId);
            if (maybeLast.isEmpty()) {
                Log.w(TAG, "No locations found for beaconId=" + beaconId);
                return;
            }

            BeaconLocationReport lastLocation = maybeLast.get();
            double lat = lastLocation.getLatitude();
            double lon = lastLocation.getLongitude();

            Log.d(TAG, "Animating camera to position of marker for beaconId=" + beaconId + " after it was selected in the bottom tag list...");

            this.markerFocus.focus(beaconId);

            if (zoom != null) {
                this.mapProvider.animateCamera(lat, lon, zoom, null);
            } else {
                // 使用当前缩放级别
                IMapProvider.CameraPosition currentPos = this.mapProvider.getCameraPosition();
                float currentZoom = currentPos != null ? currentPos.getZoom() : CAMERA_ON_MAP_INITIAL_ZOOM;
                this.mapProvider.animateCamera(lat, lon, currentZoom, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failure when trying to navigate to marker on map on lock into card for beaconId=" + beaconId, e);
        }
    }

    private synchronized void updateBeaconCards() {
        HorizontalScrollView scrollContainer = this.findViewById(R.id.tags_scrollable_area);
        LinearLayout cardsContainer = this.findViewById(R.id.tags_scroll_container);

        // Read once, not per card: it does not change between cards, and BlePermissions.granted
        // is a real permission check, not a field read.
        final boolean canRing = BlePermissions.granted(this);

        // remove all beacons that had cards that are now gone
        for (var beaconId : this.dynamicCardsForTag.keySet()) {
            if (!this.beacons.containsKey(beaconId) || !this.beaconLocations.isDrawable(beaconId)) {
                Log.i(TAG, "Cleaning up view for beaconId=" + beaconId + " which did not have any locations associated with itself anymore");
                View view = this.dynamicCardsForTag.get(beaconId);
                cardsContainer.removeView(view);
                this.dynamicCardsForTag.remove(beaconId);
            }
        }

        final long now = System.currentTimeMillis();

        // **In the order the user arranged, not the order the map happens to hold them in.**
        //
        // this.beacons is a ConcurrentHashMap, so its iteration order is hash order - arbitrary,
        // and until now that was the carousel's order too. Cards are also only created once and
        // reused, so whatever order they were first added in stuck for the life of the screen.
        // Sorting here and placing each card at its index below is what makes a drag on the
        // device list show up over here.
        final List<BeaconInformation> inOrder = TagOrder.sorted(this.beacons.values().stream()
                .map(BeaconData::getInfo)
                .collect(Collectors.toList()));

        int index = 0;
        for (final BeaconInformation ordered : inOrder) {
            final BeaconData beaconData = this.beacons.get(ordered.getBeaconId());
            if (beaconData == null) {
                continue;
            }
            final BeaconInformation beacon = beaconData.getInfo();
            final String beaconId = beacon.getBeaconId();
            final Optional<BeaconLocationReport> maybeLast = this.beaconLocations.lastLocationOf(beaconId);
            if (maybeLast.isEmpty()) {
                // Debug, not a warning. A tag with no locations yet is the ordinary state of a
                // freshly imported one, and of any tag nobody has walked past since it was
                // added - there is no card to draw because there is nowhere to draw it, which
                // is correct rather than a fault. At warning level it sat in every logcat
                // looking like the cause of whatever else was being investigated.
                Log.d(TAG, "No locations held for beaconId=" + beaconId + " yet, so it has no card");
                continue;
            }
            final BeaconLocationReport lastLocation = maybeLast.get();
            final List<Address> locationInfo = beaconData.getGeocoding();

            FrameLayout v;
            if (!this.dynamicCardsForTag.containsKey(beaconId)) {
                // MAKE A NEW CARD
                v = (FrameLayout) this.getLayoutInflater().inflate(R.layout.maps_tag_card, null);
                cardsContainer.addView(v, Math.min(index, cardsContainer.getChildCount()));
                this.dynamicCardsForTag.put(beaconId, v);
            } else {
                // UPDATE EXISTING CARD
                v = this.dynamicCardsForTag.get(beaconId);
            }

            // match width to device screen
            assert v != null;
            var params = v.getLayoutParams();
            params.width = this.windowWidth != 0 ? (this.windowWidth - 80) : (this.getWindow().getDecorView().getWidth() - 80);

            // Cards size themselves to their own content, so a two-line address made one card
            // taller than its neighbours. Inflating with a null root drops the layout's own
            // height, and the container hands out WRAP_CONTENT by default, so it has to be set
            // here: MATCH_PARENT inside a wrap_content horizontal LinearLayout triggers
            // forceUniformHeight, which measures every card to the height of the tallest.
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;

            v.setLayoutParams(params);

            // The ring button toggles a Bluetooth scan, so it has no honest job to do without
            // the permission that scan needs - showing it only to have every tap re-ask for
            // permission would be a worse experience than not showing it. Re-applied on every
            // redraw rather than once, so a permission grant or revocation while the screen is
            // open is reflected without needing anything else to notice.
            final View ringButtonContainer = v.findViewById(R.id.device_ring_button_container);
            ringButtonContainer.setVisibility(canRing ? VISIBLE : GONE);

            // the title
            TextView deviceNameView = v.findViewById(R.id.device_name);
            deviceNameView.setText(beacon.getName());

            // icon
            TextView emojiContainer = v.findViewById(R.id.device_icon_emoji);
            ImageView iconContainer = v.findViewById(R.id.device_icon_img);
            if (beacon.isEmojiFilled()) {
                // Whatever the user or their Apple device set always wins.
                emojiContainer.setText(beacon.getEmoji());
                emojiContainer.setVisibility(VISIBLE);
                iconContainer.setVisibility(GONE);
            } else {
                // Was always Apple's logo, for a Chipolo and an OpenHaystack tag alike.
                BeaconIcon.applyTo(iconContainer, beacon);
                iconContainer.setVisibility(VISIBLE);
                emojiContainer.setVisibility(GONE);
            }


            // the location
            TextView deviceLocation = v.findViewById(R.id.device_location);
            if (locationInfo.isEmpty()) {
                deviceLocation.setText(String.format(
                        Locale.ROOT, "%.6f, %.6f", lastLocation.getLatitude(), lastLocation.getLongitude()));
            } else {
                var geoLocation = locationInfo.get(0);
                deviceLocation.setText(geoLocation.getAddressLine(0));
            }

            // the last updated time - unless the tag is audible right now, which is both newer
            // and more useful than when Apple's network last reported it. See
            // showNearbyStatusOn; a sighting ages out on its own, so this line comes back.
            TextView deviceLastUpdate = v.findViewById(R.id.device_last_update);
            final NearbyTagSighting heardNow = this.nearbySightings.freshFor(beaconId, now);
            if (heardNow != null) {
                this.showNearbyStatusOn(v, heardNow, now);
            } else {
                final var timeAgo = DateUtils.getRelativeTimeSpanString(
                        lastLocation.getTimestamp(),
                        now,
                        DateUtils.MINUTE_IN_MILLIS
                ).toString();
                deviceLastUpdate.setText(this.getString(R.string.last_updated_x, timeAgo));
                // Nothing live to pulse for once the sighting has aged out.
                ((ImageView) v.findViewById(R.id.device_nearby_pulse)).setVisibility(GONE);
            }

            // **Put an existing card where it now belongs.** Cards are created once and reused,
            // so a card added before the user rearranged anything keeps its original slot
            // otherwise - the model would be in the new order and the screen in the old one.
            // Only moved when it is actually in the wrong place: removeView/addView on every
            // pass would detach and reattach every card on every refresh tick, which loses the
            // scroll position and interrupts anything mid-animation.
            if (cardsContainer.indexOfChild(v) != index) {
                cardsContainer.removeView(v);
                cardsContainer.addView(v, Math.min(index, cardsContainer.getChildCount()));
            }

            index++;
        }


        ImageButton navigationButton = this.findViewById(R.id.button_navigate_to);
        if (this.dynamicCardsForTag.isEmpty()) {
            // INVISIBLE, not GONE. buttons_bottom_right is positioned with layout_above
            // against this view, and RelativeLayout ignores layout_above when the anchor is
            // GONE - the buttons would then fall back to the top of the screen and render
            // under the status bar. Left INVISIBLE it still occupies its (zero, since it has
            // no cards) height at the bottom, so the anchor keeps resolving.
            scrollContainer.setVisibility(INVISIBLE);
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

    /** As above, but for somebody who did not choose to be signed out. */
    private void sendToLoginBecauseTheSessionExpired(final String email) {
        Intent intent = new Intent(this, AppleLoginActivity.class);
        intent.putExtra(AppleLoginActivity.EXTRA_SESSION_EXPIRED, true);
        if (email != null) {
            intent.putExtra(AppleLoginActivity.EXTRA_PREFILL_EMAIL, email);
        }
        startActivity(intent);
    }

    private IMapProvider.MapStyle getPreferredMapStyle() {
        if (this.userSettings == null || this.userSettings.getUseDarkTheme() == null) {
            return IMapProvider.MapStyle.FOLLOW_SYSTEM;
        }
        return this.userSettings.getUseDarkTheme()
                ? IMapProvider.MapStyle.DARK
                : IMapProvider.MapStyle.LIGHT;
    }

    /**
     * What to ask Apple about: every tag in the screen's model, with its plist where it has one.
     *
     * <p><b>Not {@code Collectors.toMap}</b>, which throws on a null value. A self-generated tag
     * has no plist, and one of those used to take down the fetch for every other tag as well as
     * itself. Same null-tolerant shape as the import path - see
     * {@code BeaconRepository.plistFallbacks} - and a null here is meaningful rather than
     * missing, so every id goes in.
     *
     * <p><b>Read from the model, which is already filtered.</b> {@link #addBeaconToCurrent} is
     * where the owner's own devices are left out, so anything that reaches this map is something
     * the user can see, and there is no second place for that rule to be got wrong.
     */
    private Map<String, String> plistsToFetch() {
        final Map<String, String> plists = new HashMap<>();
        this.beacons.values().forEach(b ->
                plists.put(b.getInfo().getBeaconId(), b.getInfo().getOwnedBeaconPlistRaw()));
        return plists;
    }

    private void fetchAndUpdateCurrentBeacons() {
        TagCardHelper.toggleRefreshLoadingAll(this.dynamicCardsForTag, true);

        var async = this.fetchLastReports(this.plistsToFetch())
                // **Drawn as each tag lands, not once at the end.**
                //
                // The fetch is one accessory at a time, and a tag with no key alignment record
                // can take minutes on its own - so a batch of six is quarter of an hour during
                // which nothing on screen moved, even though most of those answers arrived in
                // the first few seconds. addBeaconLocationsToCurrent only updates the model;
                // showLastDeviceLocations is what redraws, and it used to run in the terminal
                // subscribe below, once, after the slowest tag.
                //
                // It is also what makes the fetch order worth anything: putting the tags that
                // answer first is pointless if nothing is drawn until the silent ones have been
                // ground through too. See ScanOrder.
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(reports -> {
                    this.addBeaconLocationsToCurrent(reports);
                    this.showLastDeviceLocations();
                })
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

    /**
     * Stand in for a startup fetch that was not worth making.
     *
     * <p>Empty rather than absent, so the stream still completes and everything hanging off
     * it - the loading indicators, {@code initialFetchComplete}, the periodic refresher -
     * runs exactly as it would have. The scheduled refresh will pick things up when the
     * interval is actually up.
     */
    private Observable<Map<String, List<BeaconLocationReport>>> skipTheStartupFetch() {
        Log.i(TAG, "Skipping the startup fetch: " + this.refreshPolicy.describeTimeSinceLastFetch(
                System.currentTimeMillis()) + ", so the cached locations stand");
        return Observable.empty();
    }

    private Observable<Map<String, List<BeaconLocationReport>>> fetchLastReports(final Map<String, String> beaconIdToPlist) {
        // Captured once and reused below: recording the finish time instead would leave a gap
        // in history the width of the fetch, which for an unaligned tag is minutes.
        final long now = System.currentTimeMillis();
        final int hoursToGoBack = this.refreshPolicy.hoursToGoBack(now);

        Log.d(TAG, "Preparing to fetch location reports for the last " + hoursToGoBack + " hours!");
        // **The scheduled variant, and the only caller of it.** This overload is the periodic
        // tick - it is the one that reads refreshPolicy for its window - so it is where tags
        // that have gone quiet are allowed to be skipped. Every other fetch here is somebody
        // asking, and asks about whatever it was given.
        return this.beaconRepo.toScheduledAccessoryRequests(beaconIdToPlist)
                .doOnSubscribe(__ -> this.markFetchStarted())
                .flatMap(requests -> this.fetchOneAccessoryAtATime(requests, hoursToGoBack))
                .doOnNext(reports -> this.refreshPolicy.markFetched(now)) // on success, update this time.
                .doFinally(this::markFetchFinished);
    }

    private Observable<Map<String, List<BeaconLocationReport>>> fetchLastReports(final Map<String, String> beaconIdToPlist, final int hoursToGoBack) {
        Log.d(TAG, "Preparing to fetch location reports for the last " + hoursToGoBack + " hours!");
        return this.beaconRepo.toAccessoryRequests(beaconIdToPlist)
                .doOnSubscribe(__ -> this.markFetchStarted())
                .flatMap(requests -> this.fetchOneAccessoryAtATime(requests, hoursToGoBack))
                .doFinally(this::markFetchFinished);
    }

    /**
     * Fetches each accessory in its own call into Python, storing the result before moving on.
     * <br>
     * Handing Python the whole list meant it returned a single dict at the very end, so the
     * updated key alignment for every accessory was written in one go. A tag with no alignment
     * record takes minutes to resolve, and quitting part-way through discarded the work for
     * all of them - including the ones that had already finished - so the next launch searched
     * the same tens of thousands of key indices again. One call per accessory means each one
     * is persisted as soon as it resolves.
     * <br>
     * It also lets the UI update per tag instead of all at once, and keeps a single failure
     * from taking the rest of the batch with it.
     * <br>
     * Sequential on purpose: FindMy.py's synchronous account drives one asyncio event loop,
     * and calls into Python are serialised anyway (see PythonAppleService).
     * <br>
     * The sequencing itself lives in {@link RxFlows#oneAtATime} so it can be tested without
     * an Activity - see {@code RxFlowsTest}.
     */
    private Observable<Map<String, List<BeaconLocationReport>>> fetchOneAccessoryAtATime(
            final List<AccessoryRequest> requests, final int hoursToGoBack) {

        // **A tag nobody has searched for yet gets the whole week.** Read once for the batch,
        // then applied per accessory - see HOURS_TO_GO_BACK_FIRST_TIME. Everything else keeps
        // the window it asked for.
        return this.beaconRepo.neverScanned().flatMap(neverScanned -> RxFlows.oneAtATime(
                requests,
                request -> this.appleService.getLastReports(
                                List.of(request),
                                neverScanned.contains(request.getBeaconId())
                                        ? HOURS_TO_GO_BACK_FIRST_TIME
                                        : hoursToGoBack)
                        .flatMap(this.beaconRepo::storeFetchResult),
                this::setLongFetchProgress,
                (request, error) -> {
                    Log.e(TAG, "Failed to fetch reports for beaconId=" + request.getBeaconId()
                            + "; continuing with the remaining accessories", error);

                    // **This handler is where a stale session actually surfaces**, not the outer
                    // subscriber. Fetching is one accessory at a time and each failure is caught
                    // here and stepped over, so a session Apple has stopped accepting fails
                    // every accessory in turn and the stream completes as though it merely found
                    // nothing. Carrying on is right for one tag that would not answer; it is not
                    // right when the reason is the same for all of them and no retry will fix it.
                    this.runOnUiThread(this::askForACodeIfTheSessionNeedsOne);
                }));
    }

    private Observable<Map<String, List<BeaconLocationReport>>> fetchLastReportsFor(final String beaconId, final String pList, final int hoursToGoBack) {
        Log.i(TAG, "Preparing to fetch location reports for the last " + hoursToGoBack + " hours!");
        // Not Map.of - see BeaconRepository.plistFallback. A self-generated tag has no plist.
        return this.beaconRepo.toAccessoryRequests(BeaconRepository.plistFallback(beaconId, pList))
                .doOnSubscribe(__ -> this.markFetchStarted())
                .flatMap(requests -> this.appleService.getLastReports(requests, hoursToGoBack))
                .flatMap(this.beaconRepo::storeFetchResult)
                .doFinally(this::markFetchFinished);
    }

    /**
     * Starts the clock on the "still working" banner.
     * <br>
     * A tag whose export carried no KeyAlignmentRecord starts at index 0 from its pairing
     * date, so its first fetch searches the tag's whole life - tens of thousands of key
     * indices, at roughly 290 per request. That is minutes of sequential requests during
     * which nothing changes on screen, and it is indistinguishable from a hang.
     * <br>
     * It also matters that the user does not walk away: Python returns the updated
     * alignment for every accessory in one dict at the end of the batch, so quitting
     * part-way through discards the work for all of them and the next launch starts over.
     */
    private void markFetchStarted() {
        this.longFetchBannerHandler.post(() -> {
            if (this.bannerState.fetchStarted()) {
                this.longFetchBannerHandler.postDelayed(
                        this.showLongFetchBanner, SHOW_LONG_FETCH_BANNER_AFTER_MS);
            }
        });
    }

    private void markFetchFinished() {
        this.longFetchBannerHandler.post(() -> {
            if (!this.bannerState.fetchFinished()) {
                return;
            }
            this.longFetchBannerHandler.removeCallbacks(this.showLongFetchBanner);
            this.setLongFetchBannerVisible(false);
        });
    }

    /** Records how far through the batch we are, so the banner can say so. */
    private void setLongFetchProgress(int done, int total) {
        this.longFetchBannerHandler.post(() -> {
            this.bannerState.setProgress(done, total);

            TextView banner = this.findViewById(R.id.long_fetch_banner);
            if (banner != null && banner.getVisibility() == VISIBLE) {
                banner.setText(this.longFetchBannerText());
            }
        });
    }

    private String longFetchBannerText() {
        if (!this.bannerState.hasCount()) {
            return this.getString(R.string.resolving_tags_banner);
        }
        return this.getString(
                R.string.resolving_tags_banner_progress,
                this.bannerState.displayedPosition(),
                this.bannerState.total());
    }

    private void setLongFetchBannerVisible(boolean visible) {
        TextView banner = this.findViewById(R.id.long_fetch_banner);
        if (banner == null) {
            return;
        }
        if (visible) {
            banner.setText(this.longFetchBannerText());
        }
        banner.setVisibility(visible ? VISIBLE : GONE);
    }

    private boolean isAppleServiceInitialised() {
        return this.appleService != null;
    }

    private void enableMyLocation(boolean navigateToMyLocation) {
        // Check if permissions are granted, if so, enable the my location layer
        if (ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED) {
            Log.i(TAG, "Enabling 'my location' related UI features...");
            // 注意：抽象接口可能不支持setMyLocationEnabled，这里保留向后兼容
            if (this.map != null) {
                this.map.setMyLocationEnabled(true);
            }

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
        if (requestCode == RING_PERMISSION_REQUEST_CODE) {
            final String beaconId = this.ringPermissionRequestBeaconId;
            this.ringPermissionRequestBeaconId = null;

            // Re-checked against BlePermissions.granted rather than the grantResults array
            // directly - one place decides what "enough" means, matching DeviceInfoActivity's
            // own permission flow and BlePermissions' class doc.
            if (beaconId != null && BlePermissions.granted(this)) {
                Log.i(TAG, "BLE permission granted; starting continuous ping for beaconId=" + beaconId);
                this.startContinuousPing(beaconId);
            } else {
                Log.i(TAG, "BLE permission refused; not starting continuous ping");
                Toast.makeText(this, R.string.play_sound_permission_denied, LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == NEARBY_PERMISSION_REQUEST_CODE) {
            // Re-checked against BlePermissions.granted for the same reason as above: one place
            // decides what "enough" means.
            if (BlePermissions.granted(this)) {
                Log.i(TAG, "BLE permission granted; starting the nearby tag watch");
                this.startWatchingForNearbyTags();
            } else {
                Log.i(TAG, "BLE permission refused; not watching for nearby tags");
                // Said out loud because the refusal also keeps the ring button hidden (see
                // updateBeaconCards), and the request fires only once per activity - so with
                // no toast, someone who taps Deny is left with no visible trace that nearby
                // and ringing exist, and no in-app path back to them short of the system
                // settings.
                Toast.makeText(this, R.string.play_sound_permission_denied, LENGTH_LONG).show();
            }
            // The ring button on every card was hidden while this was undecided - see
            // updateBeaconCards - and needs to be shown or stay hidden depending on the answer.
            this.updateBeaconCards();
            return;
        }

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
    public boolean onMarkerClick(String markerId) {
        // 查找对应的beaconId
        Optional<String> beaconIdForMarker = Optional.ofNullable(markerId);

        if (beaconIdForMarker.isPresent()) {
            // Tapping a marker raises it as well, not only selecting its card. Without this,
            // tapping the one visible marker in a pile scrolls to its card but leaves the
            // marker underneath whichever one is drawn on top - so the tap looks ignored.
            this.markerFocus.focus(beaconIdForMarker.get());
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
