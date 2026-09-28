package dev.wander.android.opentagviewer.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.wander.android.opentagviewer.source.ExtraSourcesApi;
import dev.wander.android.opentagviewer.source.ExtraSourcesSettings;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * "Nearby" for Google Find Hub trackers. They carry no Apple keys, so {@link NearbyTagWatcher}
 * cannot derive their addresses; instead they are recognised by their current EID in the FMDN
 * advertisement (service data 0xFEAA), with the EIDs from the googlefind service - the same
 * list the ring uses. The FMDN flags byte (battery) is encrypted, so no battery is reported.
 */
@SuppressLint("MissingPermission")   // callers check BlePermissions first
public final class FmdnNearbyWatcher {
    private static final String TAG = FmdnNearbyWatcher.class.getSimpleName();
    private static final ParcelUuid FMDN_SERVICE_DATA = ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb");

    /**
     * Emits a sighting each time one of the trackers advertises nearby.
     *
     * @param googleIdByBeaconId the Google trackers to watch for (beacon id -> googlefind id)
     */
    public Observable<NearbyTagSighting> watch(final Context context, final Map<String, String> googleIdByBeaconId) {
        return Observable.<Map<String, String>>fromCallable(() -> {
                    // EID (hex) -> beacon id. The service returns a +-6 h window, so one fetch
                    // covers a long map session; the watch is restarted on resume anyway.
                    final ExtraSourcesApi api = new ExtraSourcesApi(new ExtraSourcesSettings(context));
                    final Map<String, String> beaconByEid = new HashMap<>();
                    for (final Map.Entry<String, String> e : googleIdByBeaconId.entrySet()) {
                        try {
                            for (final String eid : api.ringMaterial(e.getValue()).eids) beaconByEid.put(eid, e.getKey());
                        } catch (final Exception ex) {
                            Log.w(TAG, "No EIDs for " + e.getKey(), ex);
                        }
                    }
                    return beaconByEid;
                })
                .subscribeOn(Schedulers.io())
                .flatMap(beaconByEid -> beaconByEid.isEmpty() ? Observable.<NearbyTagSighting>empty()
                        : scan(context, beaconByEid));
    }

    private static Observable<NearbyTagSighting> scan(final Context context, final Map<String, String> beaconByEid) {
        return Observable.create(emitter -> {
            final BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            final BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            final BluetoothLeScanner scanner = adapter == null || !adapter.isEnabled() ? null : adapter.getBluetoothLeScanner();
            if (scanner == null) {
                emitter.onComplete();
                return;
            }
            final ScanCallback callback = new ScanCallback() {
                @Override
                public void onScanResult(final int callbackType, final ScanResult result) {
                    final byte[] data = result.getScanRecord() == null ? null
                            : result.getScanRecord().getServiceData(FMDN_SERVICE_DATA);
                    if (data == null || data.length < 21) return;
                    final int frame = data[0] & 0xFF;
                    if (frame != 0x40 && frame != 0x41) return;
                    final String eid = FmdnRingCommand.hex(java.util.Arrays.copyOfRange(data, 1, 21));
                    final String beaconId = beaconByEid.get(eid);
                    if (beaconId != null && !emitter.isDisposed()) {
                        emitter.onNext(new NearbyTagSighting(beaconId, null, result.getRssi(), null,
                                data.length > 21 ? data[21] & 0xFF : 0, null, System.currentTimeMillis()));
                    }
                }

                @Override
                public void onScanFailed(final int errorCode) {
                    Log.w(TAG, "FMDN nearby scan failed: " + errorCode);
                    emitter.onComplete();
                }
            };
            final List<ScanFilter> filters = Collections.singletonList(
                    new ScanFilter.Builder().setServiceData(FMDN_SERVICE_DATA, new byte[0]).build());
            scanner.startScan(filters, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
            emitter.setCancellable(() -> scanner.stopScan(callback));
        });
    }
}
