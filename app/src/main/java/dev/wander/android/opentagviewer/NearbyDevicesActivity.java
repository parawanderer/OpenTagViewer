package dev.wander.android.opentagviewer;

import static android.view.View.inflate;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static dev.wander.android.opentagviewer.util.android.TextChangedWatcherFactory.justWatchOnChanged;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.ble.BlePermissions;
import dev.wander.android.opentagviewer.ble.ForeignTagNames;
import dev.wander.android.opentagviewer.ble.NearbyDeviceBook;
import dev.wander.android.opentagviewer.ble.NearbyDeviceScanner;
import dev.wander.android.opentagviewer.ble.NearbyDeviceSighting;
import dev.wander.android.opentagviewer.databinding.ActivityNearbyDevicesBinding;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.ui.nearbydevices.NearbyDeviceListAdaptor;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Every Find My device the radio can hear, not only the user's own tags.
 *
 * <p><b>The screen {@link NearbyDeviceScanner} exists for.</b> It subscribes while it is on top
 * - the radio listens only while somebody is looking, which is what keeps a display feature
 * from being a recording - and shows one row per key prefix through {@link NearbyDeviceBook},
 * named via {@link ForeignTagNames} when the user has renamed it.
 *
 * <p>The list is refreshed on two triggers, and needs both. New sightings arrive from the scan
 * subscription, but the other half of a live list is *departures*: a tag that leaves range
 * sends nothing, so a periodic pass rebuilds the snapshot for the pruning and the "last seen"
 * age even while the air is quiet.
 */
public class NearbyDevicesActivity extends AppCompatActivity {
    private static final String TAG = NearbyDevicesActivity.class.getSimpleName();

    private static final int NEARBY_PERMISSION_REQUEST_CODE = 0x4E42;

    /**
     * How often the list is rebuilt while open, independent of arriving frames. One second is
     * what makes the "last seen" column read like a clock rather than a frozen snapshot, and it
     * costs a sort over a handful of rows.
     */
    private static final long REFRESH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1);

    private final NearbyDeviceScanner scanner = new NearbyDeviceScanner();
    private final NearbyDeviceBook book = new NearbyDeviceBook();
    private ForeignTagNames names;

    private ActivityNearbyDevicesBinding binding;
    private NearbyDeviceListAdaptor adaptor;

    private Disposable scanSubscription;
    private Disposable refreshSubscription;

    /** Asked at most once per screen, for the same reason MapsActivity bounds its own request. */
    private boolean blePermissionRequested = false;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.names = new ForeignTagNames(this);

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_nearby_devices);
        WindowPaddingUtil.insetForSystemBars(this.binding.getRoot());
        this.binding.setHandleClickBack(this::finish);

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        this.adaptor = new NearbyDeviceListAdaptor(
                this.getResources(),
                this.names::nameFor,
                this::onDeviceClicked,
                this::onDeviceLongPressed);

        final RecyclerView list = findViewById(R.id.nearby_devices_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(this.adaptor);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!BlePermissions.granted(this)) {
            if (!this.blePermissionRequested) {
                this.blePermissionRequested = true;
                Log.d(TAG, "Requesting BLE permission(s) to scan nearby devices");
                ActivityCompat.requestPermissions(
                        this, BlePermissions.required(), NEARBY_PERMISSION_REQUEST_CODE);
            }
            this.showEmptyState(R.string.nearby_devices_needs_permission);
            return;
        }

        this.scanSubscription = this.scanner.scan(this.getApplicationContext())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onSighting,
                        error -> Log.e(TAG, "Nearby device scan failed", error));

        this.refreshSubscription = Observable
                .interval(REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS, Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(tick -> this.rebuildList(),
                        error -> Log.e(TAG, "Nearby device refresh failed", error));
    }

    @Override
    protected void onPause() {
        if (this.scanSubscription != null) {
            this.scanSubscription.dispose();
            this.scanSubscription = null;
        }
        if (this.refreshSubscription != null) {
            this.refreshSubscription.dispose();
            this.refreshSubscription = null;
        }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode,
            @NonNull final String[] permissions,
            @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != NEARBY_PERMISSION_REQUEST_CODE) {
            return;
        }
        // Granted: the next onResume starts the scan. Denied: the empty state already explains
        // what is missing - re-prompting here would loop.
        if (BlePermissions.granted(this)) {
            this.onResume();
        }
    }

    private void onSighting(final NearbyDeviceSighting sighting) {
        this.book.hear(sighting);
        this.rebuildList();
    }

    private void rebuildList() {
        final List<NearbyDeviceSighting> rows =
                this.book.snapshot(System.currentTimeMillis());

        if (rows.isEmpty()) {
            this.showEmptyState(R.string.nearby_devices_empty);
            return;
        }

        this.binding.nearbyDevicesList.setVisibility(VISIBLE);
        this.binding.nearbyDevicesEmptyState.setVisibility(GONE);
        this.adaptor.setRows(rows);
        this.adaptor.notifyDataSetChanged();
    }

    private void showEmptyState(final int textRes) {
        this.binding.nearbyDevicesList.setVisibility(GONE);
        this.binding.nearbyDevicesEmptyState.setVisibility(VISIBLE);
        this.binding.nearbyDevicesEmptyText.setText(textRes);
    }

    /** Reserved for the radar branch: clicking a row will open a pointing view for this key. */
    private void onDeviceClicked(final NearbyDeviceSighting sighting) {
        Log.d(TAG, "Clicked nearby device " + sighting.getKeyPrefix());
    }

    private void onDeviceLongPressed(final View anchor, final NearbyDeviceSighting sighting) {
        this.showRenameDialog(sighting);
    }

    /**
     * The same name dialog the tag page uses, pointed at a prefix instead of a naming record:
     * a name for a foreign tag lives only in this app, on the only identity it broadcasts.
     */
    private void showRenameDialog(final NearbyDeviceSighting sighting) {
        final View view = inflate(this, R.layout.edit_device_name_dialog, null);
        final TextInputEditText textInput = view.findViewById(R.id.device_name_input);
        final String existing = this.names.nameFor(sighting.getKeyPrefix());
        if (existing != null) {
            textInput.setText(existing);
        }

        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.device_name)
                .setIcon(R.drawable.edit_24px)
                .setView(view)
                .setPositiveButton(R.string.confirm, (d, which) -> {
                    this.names.rename(sighting.getKeyPrefix(),
                            Objects.requireNonNull(textInput.getText()).toString());
                    this.rebuildList();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();

        // No empty-field guard here, unlike the tag page's copy: a foreign tag has no upstream
        // name to overwrite, so a blank field is the only "unnamed" there is - confirm clears.
    }
}
