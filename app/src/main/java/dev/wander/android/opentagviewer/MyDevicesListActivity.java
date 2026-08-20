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

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import dev.wander.android.opentagviewer.util.export.HistoryZipWriter;
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

    private ActivityMyDevicesListBinding binding;

    private boolean devicesListChanged = false;

    /**
     * The tags whose history is being written, captured when the storage picker was opened.
     *
     * <p>Held separately from the adapter's selection because the picker is another app: this
     * one can be stopped and recreated while it is up, and the selection would not survive
     * that.
     */
    private List<BeaconInformation> pendingExport = new ArrayList<>();

    private final ActivityResultLauncher<String> createHistoryZipLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"),
            uri -> {
                if (uri == null) {
                    // Cancelled the picker. Not an error, and the selection is left alone so
                    // they can try again without reselecting everything.
                    return;
                }
                this.writeHistoryZip(uri, this.pendingExport);
            }
    );

    /**
     * Reading the account, which can end by asking for the file picker instead.
     *
     * <p>An account with nothing to recover from, or with no tags on it, has one useful answer -
     * import a bundle from somebody who owns them - so that screen hands the user straight back
     * here with a flag rather than making them find the other button themselves.
     */
    private final ActivityResultLauncher<Intent> fetchFromICloudLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            (ActivityResult result) -> {
                final Intent data = result.getData();
                if (data == null) {
                    return;
                }

                if (data.getBooleanExtra(
                        FetchFromICloudActivity.RESULT_WANTS_FILE_IMPORT, false)) {
                    this.handleStartImport();
                    return;
                }

                if (data.getBooleanExtra(FetchFromICloudActivity.RESULT_IMPORTED, false)) {
                    // Rebuilt rather than appended to. This screen accumulates into `beaconInfo`
                    // on load, so fetching again would list everything twice - and the tags that
                    // just arrived have to appear without the user backing out and returning.
                    this.devicesListChanged = true;
                    this.recreate();
                }
            }
    );

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

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_my_devices_list);
        WindowPaddingUtil.insertUITopPadding(this.binding.getRoot());
        this.binding.setHandleClickBack(this::handleEndActivity);

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        this.deviceListAdaptor = new DeviceListAdaptor(
                this,
                this.getResources(),
                this.beaconInfo,
                this.locations,
                this::onDeviceClicked,
                this::onDeviceLongPressed,
                this::onSelectionCountChanged);

        this.binding.setHandleClickCloseSelection(this::endSelection);
        this.binding.setHandleClickSelectionMenu(this::showSelectionMenu);
        this.showSelectionBar(false);

        RecyclerView recyclerView = findViewById(R.id.my_devices_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(deviceListAdaptor);

        findViewById(R.id.my_devices_empty_import_button)
                .setOnClickListener(v -> this.handleStartImport());

        findViewById(R.id.my_devices_empty_fetch_button)
                .setOnClickListener(v -> this.fetchFromICloudLauncher.launch(
                        new Intent(this, FetchFromICloudActivity.class)));

        findViewById(R.id.my_devices_empty_wiki_link)
                .setOnClickListener(v -> this.openExportGuide());

        this.getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Back leaves selection before it leaves the screen. Otherwise the only way
                // out of selection mode is a small button in the corner, and back does
                // something more drastic than the user was reaching for.
                if (deviceListAdaptor.isSelectionMode()) {
                    endSelection();
                    return;
                }
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
     * Long press starts selecting, with the pressed row already chosen.
     *
     * <p>This replaced an anchored popup whose one item was Remove. Remove now lives in the
     * contextual bar alongside the exports, which is where it belongs once more than one row
     * can be acted on at a time - and it means the gesture does the same thing here as it does
     * in every other list on the platform.
     */
    private void onDeviceLongPressed(final View anchor, final BeaconInformation device) {
        this.deviceListAdaptor.startSelectionWith(device.getBeaconId());
        this.onSelectionCountChanged(this.deviceListAdaptor.getSelectedBeaconIds().size());
        this.showSelectionBar(true);
    }

    private void onSelectionCountChanged(final int count) {
        this.binding.setSelectedCount(this.getString(R.string.x_selected, count));
    }

    /** Swaps the page title for the contextual bar, or back. */
    private void showSelectionBar(final boolean selecting) {
        this.binding.settingsTopToolbar.getRoot().setVisibility(selecting ? GONE : VISIBLE);
        this.binding.selectionToolbar.getRoot().setVisibility(selecting ? VISIBLE : GONE);
    }

    private void endSelection() {
        this.deviceListAdaptor.clearSelection();
        this.showSelectionBar(false);
    }

    /**
     * Asks where to put the zip.
     *
     * <p>{@code CreateDocument} rather than writing somewhere ourselves: no storage permission
     * is needed, and the file lands where the user chose rather than somewhere they have to go
     * looking for.
     */
    private void exportHistoryForSelection() {
        this.pendingExport = this.deviceListAdaptor.getSelectedBeacons();

        if (this.pendingExport.isEmpty()) {
            return;
        }

        this.createHistoryZipLauncher.launch(this.suggestedZipName());
    }

    /**
     * The named actions for whatever is selected.
     *
     * <p>A menu rather than a row of icons in the bar: three unlabelled glyphs make the reader
     * guess, and one of them destroys data. Anchored to the overflow button, so it opens where
     * it was asked for.
     *
     * <p>Export Tag is present and disabled rather than absent. Writing a bundle needs the
     * shared export package, which is not here yet - see docs/android-import-handover.md - and
     * a menu that grows an item between versions is harder to learn than one where the item is
     * visibly not ready. The XML disables it; this is where to stop doing that.
     */
    private void showSelectionMenu() {
        PopupMenu menu = new PopupMenu(this, this.binding.selectionToolbar.selectionMenuButton);
        menu.getMenuInflater().inflate(R.menu.device_selection_menu, menu.getMenu());

        menu.setOnMenuItemClickListener(item -> {
            final int id = item.getItemId();

            if (id == R.id.action_export_history) {
                this.exportHistoryForSelection();
                return true;
            }
            if (id == R.id.action_remove_devices) {
                this.confirmRemoveSelection();
                return true;
            }
            return false;
        });

        menu.show();
    }

    /**
     * Reads each selected tag's whole stored history and writes the zip.
     *
     * <p>Off the main thread throughout: this reads every location row the app holds, which for
     * somebody with months of history is not a small query.
     */
    private void writeHistoryZip(final Uri destination, final List<BeaconInformation> beacons) {
        var async = Observable.fromIterable(beacons)
                .concatMap(beacon -> this.beaconRepo
                        // The whole stored range. The app only holds what it has fetched, so
                        // this is already bounded; a date picker is issue #71.
                        .getLocationsFor(beacon.getBeaconId(), 0L, Long.MAX_VALUE)
                        .map(reports -> Pair.create(beacon.getName(), reports)))
                .toList()
                .map(pairs -> {
                    // LinkedHashMap: entry order follows the order shown on screen, which is
                    // the order the user picked them in.
                    Map<String, List<BeaconLocationReport>> byName = new LinkedHashMap<>();
                    for (var pair : pairs) {
                        byName.put(pair.first, pair.second);
                    }
                    return byName;
                })
                .subscribeOn(Schedulers.io())
                .subscribe(historyByName -> {
                    try (OutputStream out = this.getContentResolver().openOutputStream(destination)) {
                        if (out == null) {
                            throw new IOException("the picker returned nothing to write to");
                        }
                        new HistoryZipWriter(ZoneId.systemDefault()).write(out, historyByName);
                    }

                    this.runOnUiThread(() -> {
                        Toast.makeText(this.getApplicationContext(),
                                R.string.history_exported, LENGTH_LONG).show();
                        this.endSelection();
                    });
                }, error -> {
                    Log.e(TAG, "Failed to export location history", error);
                    this.runOnUiThread(() -> Toast.makeText(this.getApplicationContext(),
                            R.string.error_occurred_while_exporting_the_history, LENGTH_LONG).show());
                });
    }

    /** Dated, because somebody exporting twice should not be asked to overwrite. */
    private String suggestedZipName() {
        return "opentagviewer-history-"
                + DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
                        .format(Instant.now())
                + ".zip";
    }

    private void confirmRemoveSelection() {
        final List<BeaconInformation> selected = this.deviceListAdaptor.getSelectedBeacons();
        if (selected.isEmpty()) {
            return;
        }

        new MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
                .setTitle(selected.size() == 1 ? R.string.remove_device : R.string.remove_devices)
                .setIcon(R.drawable.delete_24px)
                .setMessage(selected.size() == 1
                        ? R.string.are_you_sure_you_want_to_remove_this_device_once_removed_it_will_need_to_be_reimported_to_get_it_back
                        : R.string.are_you_sure_you_want_to_remove_these_devices)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    for (BeaconInformation device : selected) {
                        this.removeDevice(device);
                    }
                    this.endSelection();
                })
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