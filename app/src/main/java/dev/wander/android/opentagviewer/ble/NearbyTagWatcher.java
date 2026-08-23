package dev.wander.android.opentagviewer.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Map;

import dev.wander.android.opentagviewer.python.AccessoryMacResolver;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Reports the user's own tags as this phone hears them, for as long as somebody is subscribed.
 *
 * <p><b>Scanning is tied to a screen being open, not to a service.</b> Nothing here runs in the
 * background: the caller subscribes in {@code onResume} and disposes in {@code onPause}, so the
 * radio is only on while a person is actually looking at the result. That keeps this a display
 * feature rather than a tracking one - no foreground service, no ongoing notification, and a
 * scan alongside a lit screen costs little next to the screen itself.
 *
 * <p>Recording sightings for later, which is the other obvious thing to do with a scan, is
 * deliberately not this class's job. That is a different feature with different consequences:
 * it needs to run when nobody is watching, and a locally-sourced position is a different claim
 * from one Apple's network made, which the location history has no way to express today.
 *
 * <p>{@code SCAN_MODE_LOW_POWER} rather than the low-latency mode
 * {@link NearbyAccessoryScanner} uses. That one runs for a few seconds after an explicit tap and
 * wants an answer now; this one runs for as long as a screen is open and only needs to notice a
 * tag within a few seconds.
 */
public class NearbyTagWatcher {
    private static final String TAG = NearbyTagWatcher.class.getSimpleName();

    /** Injectable so a test can drive the whole pipeline without a radio. */
    interface Clock {
        long nowMs();
    }

    private final AccessoryMacResolver macResolver;
    private final NearbyTagIndex index;
    private final Clock clock;

    public NearbyTagWatcher(final AccessoryMacResolver macResolver) {
        this(macResolver, new NearbyTagIndex(), System::currentTimeMillis);
    }

    NearbyTagWatcher(final AccessoryMacResolver macResolver, final NearbyTagIndex index,
                     final Clock clock) {
        this.macResolver = macResolver;
        this.index = index;
        this.clock = clock;
    }

    /**
     * Emits a {@link NearbyTagSighting} every time one of the given tags is heard.
     *
     * <p>Emits repeatedly for the same tag, once per advertisement, rather than once per tag:
     * the caller wants a live signal strength and a fresh timestamp, not a one-off announcement.
     *
     * <p>Never errors on an ordinary failure. Missing permission or a Bluetooth adapter that is
     * off simply produce no sightings, because there is nothing for a caller to do about either
     * beyond what it already does for the ring button, and a screen must not break because the
     * radio is off.
     *
     * @param accessoryJsonByBeaconId the persisted accessory JSON per beacon, for the tags worth
     *                                watching for.
     */
    @SuppressLint("MissingPermission")
    public Observable<NearbyTagSighting> watch(
            final Context context, final Map<String, String> accessoryJsonByBeaconId) {
        return Observable.<NearbyTagSighting>create(emitter -> {
            if (!BlePermissions.granted(context)) {
                Log.d(TAG, "Not watching for nearby tags: BLE permission not granted");
                emitter.onComplete();
                return;
            }
            if (accessoryJsonByBeaconId.isEmpty()) {
                emitter.onComplete();
                return;
            }

            // Blocking, one interpreter start per tag - hence subscribeOn(io) below, and hence
            // the index rather than resolving per scan result. See NearbyTagIndex.
            if (this.index.isStale(this.clock.nowMs())) {
                this.index.rebuild(accessoryJsonByBeaconId, this.macResolver, this.clock.nowMs());
                Log.d(TAG, "Watching " + this.index.size() + " candidate address(es) for "
                        + accessoryJsonByBeaconId.size() + " tag(s)");
            }

            final BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            final BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            final BluetoothLeScanner scanner =
                    adapter == null ? null : adapter.getBluetoothLeScanner();
            if (scanner == null) {
                Log.d(TAG, "Not watching for nearby tags: Bluetooth is off or unsupported");
                emitter.onComplete();
                return;
            }

            final ScanCallback callback = new ScanCallback() {
                @Override
                public void onScanResult(final int callbackType, final ScanResult result) {
                    final NearbyTagSighting sighting = sightingFrom(result);
                    if (sighting != null && !emitter.isDisposed()) {
                        emitter.onNext(sighting);
                    }
                }

                @Override
                public void onScanFailed(final int errorCode) {
                    // Not an error onto the subscriber: see the method contract. A screen that
                    // cannot scan shows no badges, which is the same as seeing nothing.
                    Log.w(TAG, "Nearby tag scan failed (errorCode=" + errorCode + ")");
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                }
            };

            scanner.startScan(null,
                    new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                            .build(),
                    callback);

            emitter.setCancellable(() -> {
                Log.d(TAG, "Stopped watching for nearby tags");
                scanner.stopScan(callback);
            });
        }).subscribeOn(Schedulers.io());
    }

    /**
     * One scan result turned into a sighting, or null if it is not one of ours.
     *
     * <p>Package-private and separated from the scan callback so the decision - is this Find My
     * at all, is it a tag we own, what did it say - is reachable by a test without a radio.
     */
    @Nullable
    NearbyTagSighting sightingFrom(final ScanResult result) {
        final ScanRecord record = result.getScanRecord();
        if (record == null) {
            return null;
        }

        final FindMyAdvertisement advertisement = FindMyAdvertisement.parse(
                record.getManufacturerSpecificData(FindMyAdvertisement.APPLE_COMPANY_ID));
        if (advertisement == null) {
            return null;
        }

        // Most Find My advertisements in any scan belong to strangers; only ours resolve.
        final String beaconId = this.index.beaconIdFor(result.getDevice().getAddress());
        if (beaconId == null) {
            return null;
        }

        return new NearbyTagSighting(beaconId, result.getRssi(), advertisement.getBatteryLevel(),
                advertisement.getState(), this.clock.nowMs());
    }
}
