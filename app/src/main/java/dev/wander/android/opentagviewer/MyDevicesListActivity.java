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
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
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
import java.util.Set;
import java.util.Map;
import java.util.stream.IntStream;

import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.databinding.ActivityMyDevicesListBinding;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.BeaconRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.util.TagOrder;
import dev.wander.android.opentagviewer.util.TagVisibility;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.ui.error.ErrorReportActivity;
import dev.wander.android.opentagviewer.ui.mydevices.DeviceListAdaptor;
import dev.wander.android.opentagviewer.ui.mydevices.ExportedBundleDialog;
import dev.wander.android.opentagviewer.util.export.TagExporter;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.util.android.PropertiesUtil;
import dev.wander.android.opentagviewer.util.android.WebLink;
import dev.wander.android.opentagviewer.util.export.HistoryZipWriter;
import dev.wander.android.opentagviewer.util.parse.BeaconDataParser;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class MyDevicesListActivity extends AppCompatActivity {
    private static final String TAG = MyDevicesListActivity.class.getSimpleName();

    private BeaconRepository beaconRepo;

    /** Read once, in {@code onCreate}: this screen is recreated when the setting changes. */
    private UserSettings userSettings;

    private final List<BeaconInformation> beaconInfo = new ArrayList<>();

    private final Map<String, BeaconLocationReport> locations = new HashMap<>();

    private DeviceListAdaptor deviceListAdaptor;

    private ActivityMyDevicesListBinding binding;

    /**
     * Whether anything changed here, so the map knows to re-read when this screen closes.
     *
     * <p><b>Saved and restored, because this screen recreates itself.</b> Finishing an account
     * read sets this and then calls {@code recreate()} to rebuild the list - which destroys the
     * activity and constructs a new one, where a plain field is false again. The tags were on
     * screen, the flag that says so was gone, and the map went on showing nothing until the app
     * was restarted. See {@link #KEY_DEVICES_CHANGED}.
     */
    private boolean devicesListChanged = false;

    /** Survives {@code recreate()}, which is the only reason this is in the instance state. */
    private static final String KEY_DEVICES_CHANGED = "devicesListChanged";

    /**
     * Whether this app has joined the account's keychain, once the store has said.
     *
     * <p>Null while that is still being read - a decryption on a background thread - and null is
     * treated as "not linked" for the menu, which shows the item. See {@link #showPageMenu()}.
     */
    private Boolean accountIsLinked;

    /** The in-flight read of that, so leaving does not land on a menu that has gone. */
    private Disposable membershipLookup;

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
    /**
     * Where to put a bundle of tags to share.
     *
     * <p>The document picker rather than a share sheet: the recipient gets this through whatever
     * they already use, and a file they chose the location of is one they can find again. It also
     * makes the two-step nature honest - the file goes one way, the code goes another, and they
     * must not travel together.
     */
    private final ActivityResultLauncher<String> createBundleZipLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"),
            uri -> {
                if (uri == null) {
                    // Cancelled. Not an error, and the selection is left alone so it can be
                    // tried again without picking every tag a second time.
                    return;
                }
                this.writeBundleZip(uri, this.pendingExport);
            });

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

        this.userSettings = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.getApplicationContext()))
                .getUserSettings();

        if (savedInstanceState != null) {
            this.devicesListChanged =
                    savedInstanceState.getBoolean(KEY_DEVICES_CHANGED, false);
        }

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
        this.binding.setHandleClickPageMenu(this::showPageMenu);
        this.showSelectionBar(false);

        RecyclerView recyclerView = findViewById(R.id.my_devices_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(deviceListAdaptor);
        this.attachDragToReorder(recyclerView);

        findViewById(R.id.my_devices_empty_import_button)
                .setOnClickListener(v -> this.handleStartImport());

        findViewById(R.id.my_devices_empty_fetch_button)
                .setOnClickListener(v -> this.openTheAccountFetch());

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

        this.rememberWhetherTheAccountIsLinked();
        this.fetchDeviceInfoAndRender();
    }

    @Override
    protected void onDestroy() {
        if (this.membershipLookup != null && !this.membershipLookup.isDisposed()) {
            this.membershipLookup.dispose();
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_DEVICES_CHANGED, this.devicesListChanged);
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

    /**
     * Re-read the last known locations whenever this screen comes back to the front.
     *
     * <p><b>The list was only ever loaded in {@code onCreate}.</b> Coming back from the device
     * page refreshed it only when that page said a device had been removed or changed, and
     * fetching history is neither - so a tag whose locations had just been found through the
     * history screen went on saying "No last location known" until something else recreated the
     * activity. @parawanderer found it by going device → history → back and seeing no change,
     * then reaching the same list through the map and seeing "3 days ago". The data had been
     * there the whole time; this screen had not looked again.
     *
     * <p>Locations only. Rebuilding the beacons as well would re-sort and re-bind every row
     * under somebody who is reading them, and their names and icons cannot change without the
     * device page saying so - which it already does.
     *
     * <p>Skipped while the first load is still in flight, since {@code onResume} runs
     * immediately after {@code onCreate} and there is nothing yet to refresh.
     */
    @Override
    protected void onResume() {
        super.onResume();

        if (this.beaconInfo.isEmpty()) {
            return;
        }

        var async = this.beaconRepo.getLastLocationsForAll()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(latest -> {
                    if (latest.equals(this.locations)) {
                        return;
                    }

                    this.locations.clear();
                    this.locations.putAll(latest);
                    this.deviceListAdaptor.notifyItemRangeChanged(0, this.beaconInfo.size());
                }, error -> Log.e(TAG, "Could not refresh the last known locations", error));
    }

    private void fetchDeviceInfoAndRender() {
        var asyncLocations = this.beaconRepo.getLastLocationsForAll();

        // The same rule the map applies, from the same place: the owner's own Apple devices are
        // left out unless the setting is on. See TagVisibility.
        var asyncBeacons = this.beaconRepo.getAllBeacons()
                .flatMap(BeaconDataParser::parseAsync)
                .map(all -> TagVisibility.visible(all, this.userSettings.shouldShowAppleDevices()))
                // Whatever the user dragged this into last time, and accessories before the
                // owner's own devices for anything they have not touched.
                .map(TagOrder::sorted);

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
    /**
     * Write the selected tags as a bundle somebody else can import.
     *
     * <p><b>Sharing, not backing up.</b> An owner signed into their own account does not need a
     * bundle - their tags arrive when they sign in. This is for giving a tag to another person,
     * and the act is irreversible: exported key material cannot be withdrawn, and the only way to
     * revoke it is to unpair the accessory.
     */
    private void exportTagsForSelection() {
        this.pendingExport = this.deviceListAdaptor.getSelectedBeacons();

        if (this.pendingExport.isEmpty()) {
            return;
        }

        this.createBundleZipLauncher.launch(this.suggestedBundleName());
    }

    /**
     * What the recipient sees as "exported by" on each tag's page.
     *
     * <p><b>A label, and deliberately not the Apple ID.</b> The shared package asks for one and
     * says so, and it is right: this string travels inside a file that goes to another person and
     * often onward from there, and the address it would otherwise carry is the one that signs in
     * to the account these tags belong to.
     *
     * <p>The desktop exporter uses the operating system's user name, which is the same kind of
     * thing. Android has no equivalent, so the device model stands in - it tells a recipient which
     * phone a bundle came from, which is the useful half, without naming anybody.
     */
    private static String exportedByLabel() {
        return android.os.Build.MODEL == null || android.os.Build.MODEL.isBlank()
                ? "OpenTagViewer for Android"
                : "OpenTagViewer on " + android.os.Build.MODEL;
    }

    /** Dated, so exporting twice does not ask about overwriting. */
    private String suggestedBundleName() {
        return "opentagviewer-tags-"
                + DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
                        .format(Instant.now())
                + ".zip";
    }

    /**
     * Reads each selected tag's stored records, builds the bundle, locks it, writes it.
     *
     * <p>Off the main thread throughout: a Python call and a zip write.
     *
     * <p><b>Three failures, three different screens</b>, and the difference matters. A tag that
     * cannot go in a bundle is something the user picked and can change, so it is named in a
     * toast. A file that will not write is the disk, and says so. Anything else is the app
     * failing at something it should be able to do - and that one goes to the report page,
     * because there is nothing for the user to change and no way for them to say what happened
     * without one.
     */
    private void writeBundleZip(final Uri destination, final List<BeaconInformation> beacons) {
        // **The write is a `map`, not something done inside `subscribe`.**
        //
        // Rx cannot deliver an exception thrown in the onNext consumer to the onError consumer -
        // it is already in the terminal handler - so it goes to RxJavaPlugins.onError and takes
        // the process with it. The first version of this had the write in the consumer, and an
        // export that failed crashed the app instead of showing anything at all. Inside the
        // stream, the same throw reaches onError and becomes a screen.
        var async = Observable.fromIterable(beacons)
                .concatMap(beacon -> this.beaconRepo.getById(beacon.getBeaconId())
                        .map(data -> new TagExporter.Pairing(
                                data.getOwnedBeaconInfo(),
                                data.getBeaconNamingRecord(),
                                beacon.getName())))
                .toList()
                .map(pairings -> {
                    try (OutputStream out =
                                 this.getContentResolver().openOutputStream(destination)) {
                        if (out == null) {
                            throw new IOException("the picker returned nothing to write to");
                        }
                        return TagExporter.writeTo(
                                out,
                                pairings,
                                "OpenTagViewer.android:" + BuildConfig.VERSION_NAME,
                                exportedByLabel(),
                                System.currentTimeMillis());
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(written -> {
                    if (written.getWarning() != null) {
                        Log.w(TAG, "The bundle was written without something: "
                                + written.getWarning());
                    }

                    this.endSelection();
                    final AlertDialog dialog =
                            ExportedBundleDialog.show(this, written.getPasscode());
                    ExportedBundleDialog.wireCopy(dialog, written.getPasscode());
                }, this::onBundleExportFailed);
    }

    /**
     * What to say when an export did not happen, and where to send them.
     *
     * <p>The report page is for the third case only. Offering it for a full disk, or for a tag
     * that was never going to work, is how a page that means "this is a bug" stops meaning
     * anything - see {@code ErrorReportActivity}.
     */
    private void onBundleExportFailed(final Throwable error) {
        Log.e(TAG, "Could not export the selected tags", error);

        // Already on the main thread - the stream observes there - so nothing here hops.
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TagExporter.NothingToExportException) {
                Toast.makeText(this,
                        this.getString(R.string.export_tags_nothing_to_export, cause.getMessage()),
                        LENGTH_LONG).show();
                return;
            }
            if (cause instanceof IOException) {
                Toast.makeText(this, R.string.export_tags_could_not_write, LENGTH_LONG).show();
                return;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }

        // **The cause, not the wrapper.** Rx wraps what a `map` throws, so `describe(error)` here
        // would put "RuntimeException" on the page and bury the sentence the reporter needs.
        this.startActivity(ErrorReportActivity.intentFor(
                this, ErrorReportActivity.describe(rootOf(error)),
                R.string.error_report_body_export));
    }

    /** The innermost cause, which is the one that says what actually happened. */
    private static Throwable rootOf(final Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

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
    /**
     * The two ways to get tags in, from a screen that already has some.
     *
     * <p><b>Both of these used to be reachable only from the empty state</b>, which is hidden the
     * moment anything is imported - so after a first import there was no way back to either. It
     * matters most for the account route: the app joins the keychain precisely so that a later
     * read costs one tap and no device passcode, and there was nothing to tap.
     */
    private void showPageMenu() {
        final PopupMenu menu = new PopupMenu(
                this, this.binding.settingsTopToolbar.pageMenuButton);
        menu.getMenuInflater().inflate(R.menu.my_devices_menu, menu.getMenu());

        // **Hidden once the account is linked**, because linking is what this item does. After
        // it, the app is a member of the keychain and re-reads without asking for anything, so
        // an item offering to link again describes work already done. Shown while the answer is
        // still unknown: the screen it leads to resumes as a member anyway, so the harmless
        // mistake is offering it once too often rather than hiding the only way in.
        menu.getMenu().findItem(R.id.action_fetch_from_account)
                .setVisible(!Boolean.TRUE.equals(this.accountIsLinked));

        menu.setOnMenuItemClickListener(item -> {
            final int id = item.getItemId();

            if (id == R.id.action_fetch_from_account) {
                this.openTheAccountFetch();
                return true;
            }
            if (id == R.id.action_import_from_file) {
                this.handleStartImport();
                return true;
            }
            return false;
        });

        menu.show();
    }

    private void rememberWhetherTheAccountIsLinked() {
        this.membershipLookup = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(this.getApplicationContext()),
                new AppCryptographyUtil())
                .get()
                .firstOrError()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        held -> this.accountIsLinked = held.isPresent(),
                        error -> Log.w(TAG, "Could not read whether the account is linked;"
                                + " the menu will go on offering to link it", error));
    }

    private void openTheAccountFetch() {
        this.fetchFromICloudLauncher.launch(new Intent(this, FetchFromICloudActivity.class));
    }

    /**
     * Dragging a row by its handle reorders the list, and the order is kept.
     *
     * <p><b>{@code setLongPressDragEnabled(false)} is the load-bearing line.</b> A long press on
     * a row already means "start selecting", and that is the gesture {@code ItemTouchHelper}
     * would otherwise claim for dragging. Leaving both on does not produce a conflict the
     * framework resolves - it produces a row that sometimes selects and sometimes picks up,
     * depending on how far the finger drifted, which is worse than either. So the handle is the
     * only way in; see {@code DeviceListAdaptor#bindDragHandle}.
     *
     * <p><b>Written once, when the finger lifts.</b> {@code onMove} fires for every row the
     * drag crosses, so persisting there would be a database write per row passed over, and a
     * half-finished arrangement stored if the drag were cancelled. {@code clearView} is the end
     * of the gesture.
     */
    private void attachDragToReorder(final RecyclerView recyclerView) {
        final ItemTouchHelper helper = new ItemTouchHelper(
                new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {

            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView view,
                                  @NonNull RecyclerView.ViewHolder dragged,
                                  @NonNull RecyclerView.ViewHolder target) {

                final int from = dragged.getBindingAdapterPosition();
                final int to = target.getBindingAdapterPosition();

                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                    return false;
                }

                // Through TagOrder rather than a swap here, so what a drag means is defined in
                // one place and tested on the JVM. The list is the adapter's - it was handed
                // the same object - so replacing its contents is what moves the rows.
                final List<BeaconInformation> reordered =
                        TagOrder.moved(MyDevicesListActivity.this.beaconInfo, from, to);
                MyDevicesListActivity.this.beaconInfo.clear();
                MyDevicesListActivity.this.beaconInfo.addAll(reordered);

                MyDevicesListActivity.this.deviceListAdaptor.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // Nothing swipes here. Required by the base class.
            }

            @Override
            public void clearView(@NonNull RecyclerView view,
                                  @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(view, viewHolder);
                MyDevicesListActivity.this.rememberTheArrangement();
            }
        });

        helper.attachToRecyclerView(recyclerView);
        this.deviceListAdaptor.setOnDragHandleTouched(helper::startDrag);
    }

    /**
     * Store the order now on screen.
     *
     * <p>Every visible tag gets a position, not only the ones that moved - see {@link TagOrder},
     * which explains why a partial arrangement cannot be reasoned about.
     */
    private void rememberTheArrangement() {
        // **The map has to be told, or the arrangement only appears after a restart.**
        //
        // MapsActivity holds its tags in memory and reads uiOrder off them, so those objects
        // carry whatever order was current when the screen loaded - going back to a map that
        // is still alive shows the old one, and the app looks like it forgot. This is the
        // existing "something about the tags changed" flag, which makes the map recreate on
        // return; the reload is what picks the new positions up.
        this.devicesListChanged = true;

        var async = this.beaconRepo.storeArrangement(TagOrder.positionsFor(this.beaconInfo))
                .subscribe(
                        () -> Log.d(TAG, "Stored the arrangement of " + this.beaconInfo.size()
                                + " tags"),
                        error -> Log.e(TAG, "Could not store the tag arrangement", error));
    }

    /**
     * Bring everything selected to the front of the list.
     *
     * <p>The bulk half of arranging. Dragging handles one tag well and several badly - it is one
     * finger and one row at a time - so the case it is worst at gets a menu item instead of a
     * more elaborate gesture. Selection mode already exists for exactly this shape of "do a
     * thing to these tags", and this collides with nothing.
     */
    private void moveSelectionToTop() {
        final Set<String> selected = this.deviceListAdaptor.getSelectedBeaconIds();
        if (selected.isEmpty()) {
            return;
        }

        final List<BeaconInformation> reordered = TagOrder.movedToTop(this.beaconInfo, selected);
        this.beaconInfo.clear();
        this.beaconInfo.addAll(reordered);

        this.deviceListAdaptor.notifyItemRangeChanged(0, this.beaconInfo.size());
        this.rememberTheArrangement();
        this.endSelection();

        // Otherwise the rows move under a list that is still scrolled to wherever the user was,
        // and the tags they just sent to the top are off screen above them.
        ((RecyclerView) findViewById(R.id.my_devices_list)).scrollToPosition(0);
    }

    private void showSelectionMenu() {
        PopupMenu menu = new PopupMenu(this, this.binding.selectionToolbar.selectionMenuButton);
        menu.getMenuInflater().inflate(R.menu.device_selection_menu, menu.getMenu());

        // **Nothing selected means nothing to do to it, and the menu has to say so.** Both of
        // these read the selection, find it empty and return - so with "0 selected" they were
        // offered in full, did nothing at all when tapped, and gave no reason. Disabled rather
        // than hidden, for the reason already written on Export Tags: a menu that changes shape
        // is harder to learn than one where an item is visibly not available yet.
        final boolean anythingSelected = !this.deviceListAdaptor.getSelectedBeacons().isEmpty();
        menu.getMenu().findItem(R.id.action_export_history).setEnabled(anythingSelected);
        menu.getMenu().findItem(R.id.action_export_tags).setEnabled(anythingSelected);
        menu.getMenu().findItem(R.id.action_remove_devices).setEnabled(anythingSelected);
        menu.getMenu().findItem(R.id.action_move_to_top).setEnabled(anythingSelected);

        menu.setOnMenuItemClickListener(item -> {
            final int id = item.getItemId();

            if (id == R.id.action_export_history) {
                this.exportHistoryForSelection();
                return true;
            }
            if (id == R.id.action_export_tags) {
                this.exportTagsForSelection();
                return true;
            }
            if (id == R.id.action_move_to_top) {
                this.moveSelectionToTop();
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

    /**
     * Remove, minus the tags this app does not own.
     *
     * <p><b>A tag read from the Apple account cannot be removed from here, and saying so is the
     * whole point of this.</b> Marking one removed appears to work and then undoes itself: the
     * row is a cache of the account, so the next refresh writes it back with
     * {@code is_removed = 0} and the tag returns with no explanation. Removing it for real means
     * removing it in Find My, which is the user's account to change and not this app's.
     *
     * <p>So the destructive button is offered only for what is actually the app's to remove, and
     * a selection that is entirely account tags gets an explanation instead of a dialog whose
     * confirm button would lie.
     */
    private void confirmRemoveSelection() {
        final List<BeaconInformation> selected = this.deviceListAdaptor.getSelectedBeacons();
        if (selected.isEmpty()) {
            return;
        }

        final List<BeaconInformation> removable = new ArrayList<>();
        int fromTheAccount = 0;
        for (final BeaconInformation device : selected) {
            if (device.isFromAccount()) {
                fromTheAccount++;
            } else {
                removable.add(device);
            }
        }

        if (removable.isEmpty()) {
            this.explainAccountTagsCannotBeRemoved(selected.size());
            return;
        }

        // Mixed: the message names what will survive, so nobody has to notice afterwards that
        // some of what they picked is still there.
        final CharSequence message = fromTheAccount == 0
                ? this.getString(removable.size() == 1
                        ? R.string.are_you_sure_you_want_to_remove_this_device_once_removed_it_will_need_to_be_reimported_to_get_it_back
                        : R.string.are_you_sure_you_want_to_remove_these_devices)
                : this.getString(R.string.remove_devices_but_keep_account_ones, fromTheAccount);

        new MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
                .setTitle(removable.size() == 1 ? R.string.remove_device : R.string.remove_devices)
                .setIcon(R.drawable.delete_24px)
                .setMessage(message)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    for (BeaconInformation device : removable) {
                        this.removeDevice(device);
                    }
                    this.endSelection();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** No confirm button, because there is nothing here for the app to do. */
    private void explainAccountTagsCannotBeRemoved(final int howMany) {
        new MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
                .setTitle(R.string.cannot_remove_account_tag_title)
                // Not the delete icon. Nothing is being deleted, and the theme-tinted icons are
                // the only ones legible in both modes - `apple.xml` is hardcoded black.
                .setIcon(R.drawable.help_center_24px)
                .setMessage(howMany == 1
                        ? R.string.cannot_remove_account_tag_message
                        : R.string.cannot_remove_account_tags_message)
                .setPositiveButton(R.string.ok, null)
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

        WebLink.open(this, url);
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