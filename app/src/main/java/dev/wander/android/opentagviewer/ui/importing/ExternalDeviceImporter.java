package dev.wander.android.opentagviewer.ui.importing;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.repo.BeaconRepository;
import dev.wander.android.opentagviewer.db.repo.model.ImportData;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.source.ExternalAccessory;
import dev.wander.android.opentagviewer.source.ExtraSourcesApi;
import dev.wander.android.opentagviewer.source.ExtraSourcesSettings;
import dev.wander.android.opentagviewer.source.SourceLinks;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Adds devices that do not come from an Apple export: an OpenHaystack key from a
 * Macless-Haystack style {@code .keys} file, or a tracker of the Google Find Hub account; and
 * links a Google tracker to an existing device so both networks' reports land on one marker.
 *
 * <p>Stored as ordinary {@code OwnedBeacons} rows through {@link BeaconRepository#addNewImport},
 * so the list, the map and the history treat them like any imported tag.
 */
public final class ExternalDeviceImporter {
    private static final String TAG = ExternalDeviceImporter.class.getSimpleName();
    private static final String VERSION = "extra-sources-1";

    private final Activity activity;
    private final Runnable onChanged;

    public ExternalDeviceImporter(final Activity activity, final Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
    }

    // ---- OpenHaystack key ----

    /** Call with the document the user picked (a .keys file). */
    public void importKeysFile(@Nullable final Uri uri) {
        if (uri == null) return;
        final List<String> privateKeys;
        try (InputStream in = this.activity.getContentResolver().openInputStream(uri)) {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final byte[] chunk = new byte[4096];
            int n;
            while (in != null && (n = in.read(chunk)) > 0 && buf.size() < 65536) buf.write(chunk, 0, n);
            privateKeys = ExternalAccessory.privateKeysFromKeysFile(buf.toString("UTF-8"));
        } catch (final Exception e) {
            Log.w(TAG, "Not a usable .keys file", e);
            Toast.makeText(this.activity, R.string.keys_file_invalid, Toast.LENGTH_LONG).show();
            return;
        }

        this.askName(this.activity.getString(R.string.openhaystack_default_name), name -> {
            // The id is derived from the first key, so importing the same file twice updates the row
            final String id = "openhaystack-" + sha256Hex(privateKeys.get(0)).substring(0, 16);
            this.insert(id, ExternalAccessory.newOpenHaystack(id, name, privateKeys), "openhaystack-keys");
        });
    }

    // ---- Google Find Hub ----

    /** Lists the trackers of the Google account and adds the chosen one as a device. */
    public void importFromGoogle() {
        this.chooseGoogleTracker(R.string.add_from_google, device ->
                this.insert("google-" + device.id, ExternalAccessory.newGoogle(device.id, device.name), "google-find-hub"));
    }

    /** Shows this device's reports from a Google tracker as well (or stops, with "None"). */
    public void linkGoogleTracker(final String beaconId) {
        this.chooseGoogleTracker(R.string.link_google_tracker, device -> {
            new SourceLinks(this.activity).setGoogleId(beaconId, device == null ? null : device.id);
            this.onChanged.run();
        }, true);
    }

    private interface Chosen<T> {
        void accept(T value);
    }

    private void chooseGoogleTracker(final int titleRes, final Chosen<ExtraSourcesApi.GoogleDevice> chosen) {
        this.chooseGoogleTracker(titleRes, chosen, false);
    }

    private void chooseGoogleTracker(final int titleRes, final Chosen<ExtraSourcesApi.GoogleDevice> chosen,
                                     final boolean offerNone) {
        final ExtraSourcesSettings settings = new ExtraSourcesSettings(this.activity);
        if (!settings.isGoogleConfigured()) {
            Toast.makeText(this.activity, R.string.google_not_configured, Toast.LENGTH_LONG).show();
            return;
        }
        final var async = Observable.fromCallable(() -> new ExtraSourcesApi(settings).googleDevices())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(devices -> {
                    final int extra = offerNone ? 1 : 0;
                    final String[] names = new String[devices.size() + extra];
                    if (offerNone) names[0] = this.activity.getString(R.string.google_tracker_none);
                    for (int i = 0; i < devices.size(); i++) names[i + extra] = devices.get(i).name;
                    new MaterialAlertDialogBuilder(this.activity)
                            .setTitle(titleRes)
                            .setItems(names, (dialog, which) ->
                                    chosen.accept(which < extra ? null : devices.get(which - extra)))
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }, error -> {
                    Log.w(TAG, "Could not list Google trackers", error);
                    Toast.makeText(this.activity,
                            this.activity.getString(R.string.google_list_failed, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
    }

    // ---- shared ----

    private void askName(final String suggestion, final Chosen<String> chosen) {
        final TextInputLayout layout = new TextInputLayout(this.activity);
        layout.setHint(this.activity.getString(R.string.device_name));
        final EditText input = new TextInputEditText(layout.getContext());
        input.setSingleLine(true);
        input.setText(suggestion);
        layout.addView(input);
        final FrameLayout frame = new FrameLayout(this.activity);
        final int pad = Math.round(22 * this.activity.getResources().getDisplayMetrics().density);
        frame.setPadding(pad, pad / 2, pad, 0);
        frame.addView(layout);

        new MaterialAlertDialogBuilder(this.activity)
                .setTitle(R.string.add_openhaystack_key)
                .setView(frame)
                .setPositiveButton(R.string.add, (d, w) -> {
                    final String name = input.getText().toString().trim();
                    chosen.accept(name.isEmpty() ? suggestion : name);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void insert(final String id, final String accessoryJson, final String via) {
        final long now = System.currentTimeMillis();
        final Import anImport = Import.builder()
                .version(VERSION)
                .importedAt(now)
                .exportedAt(now)
                .sourceUser(null)
                .exportedVia(via)
                .build();
        final OwnedBeacon beacon = OwnedBeacon.builder()
                .id(id)
                .version(VERSION)
                .content(null)
                .alignmentPlist(null)
                .accessoryJson(accessoryJson)
                .build();
        try {
            final var async = new BeaconRepository(OpenTagViewerDatabase.getInstance(this.activity.getApplicationContext()))
                    .addNewImport(new ImportData(anImport, List.of(beacon), List.of()))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(done -> {
                        Toast.makeText(this.activity, R.string.device_added, Toast.LENGTH_SHORT).show();
                        this.onChanged.run();
                    }, error -> {
                        Log.e(TAG, "Could not store " + id, error);
                        Toast.makeText(this.activity, R.string.device_add_failed, Toast.LENGTH_LONG).show();
                    });
        } catch (final Exception e) {
            Log.e(TAG, "Could not store " + id, e);
            Toast.makeText(this.activity, R.string.device_add_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static String sha256Hex(final String text) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));
            final StringBuilder sb = new StringBuilder();
            for (final byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
