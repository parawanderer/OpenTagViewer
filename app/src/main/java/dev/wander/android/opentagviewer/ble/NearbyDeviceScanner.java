package dev.wander.android.opentagviewer.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Reports every Find My device in earshot, for as long as somebody is subscribed.
 *
 * <p><b>The generic counterpart of {@link NearbyTagWatcher}.</b> The watcher hears the same
 * radio traffic and throws most of it away - anything whose address is not in the user's own
 * tag index - because answering "is this mine" is its whole job. This class answers the other
 * question, "what is out there at all", so it emits every frame that parses as a Find My
 * advertisement. It shares the watcher's scan shape deliberately apart from the filter: the
 * watcher can hardware-filter on the 0x12 record at offset zero because its own tags always
 * lead with it, while this scanner filters on Apple's company ID alone and sorts the TLV
 * chain in software - a Find My frame behind another Apple record must still be seen. Same
 * restart before the platform's long-scan downgrade, same contract of completing rather than
 * erroring when Bluetooth or permission is missing.
 *
 * <p>What it does not share is anything expensive. No index, no MAC resolver, no Python -
 * a frame goes from the radio to the emitter as pure byte parsing, which is also why this can
 * sit on the scan callback thread without an offload: {@link FindMyFrameKey#parse} is a handful
 * of array copies, not a derivation.
 *
 * <p>Emits once per advertisement, not once per device - the caller wants live signal strength
 * and fresh timestamps, the same contract the watcher's sightings carry.
 */
public class NearbyDeviceScanner {
    private static final String TAG = NearbyDeviceScanner.class.getSimpleName();

    /** Injectable so a test can drive timestamps without a clock. */
    interface Clock {
        long nowMs();
    }

    /** How hard the radio listens; low latency while a screen is open, see the watcher. */
    private volatile int scanMode;

    private final Clock clock;

    public NearbyDeviceScanner() {
        this(ScanSettings.SCAN_MODE_LOW_LATENCY, System::currentTimeMillis);
    }

    public NearbyDeviceScanner(final int scanMode) {
        this(scanMode, System::currentTimeMillis);
    }

    NearbyDeviceScanner(final int scanMode, final Clock clock) {
        this.scanMode = scanMode;
        this.clock = clock;
    }

    /**
     * Emits a {@link NearbyDeviceSighting} for every Find My frame heard.
     *
     * <p>Never errors on an ordinary failure, matching {@link NearbyTagWatcher#watch}: no
     * permission, Bluetooth off, or a failed scan all complete the stream, because the caller
     * has nothing to do about them that it does not already do.
     */
    @SuppressLint("MissingPermission")
    public Observable<NearbyDeviceSighting> scan(final Context context) {
        return Observable.<NearbyDeviceSighting>create(emitter -> {
            if (!BlePermissions.granted(context)) {
                Log.d(TAG, "Not scanning nearby devices: BLE permission not granted");
                emitter.onComplete();
                return;
            }

            final BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            final BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            final BluetoothLeScanner scanner =
                    adapter == null ? null : adapter.getBluetoothLeScanner();
            if (scanner == null) {
                Log.d(TAG, "Not scanning nearby devices: Bluetooth is off or unsupported");
                emitter.onComplete();
                return;
            }

            final ScanCallback callback = new ScanCallback() {
                @Override
                public void onScanResult(final int callbackType, final ScanResult result) {
                    final NearbyDeviceSighting sighting = sightingFrom(result);
                    if (sighting == null || emitter.isDisposed()) {
                        return;
                    }
                    emitter.onNext(sighting);
                }

                @Override
                public void onScanFailed(final int errorCode) {
                    Log.w(TAG, "Nearby device scan failed (errorCode=" + errorCode + ")");
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                }
            };

            // Company ID only, no data mask: a mask can only match a fixed offset, but the
            // Find My TLV does not have to lead Apple's TLV chain - Nearby Info and Handoff
            // records routinely sit in front of it. Software parsing takes over the 0x12
            // check in sightingFrom, so frames a mask cannot express still reach the list.
            // The trade is callback traffic: every Apple advertisement lands here, including
            // AirPods chatter - acceptable while a screen is open, wrong for a background
            // service.
            final List<ScanFilter> findMyFramesOnly = List.of(new ScanFilter.Builder()
                    .setManufacturerData(FindMyAdvertisement.APPLE_COMPANY_ID, new byte[0])
                    .build());
            scanner.startScan(findMyFramesOnly, settingsFor(this.scanMode), callback);

            // Same downgrade the watcher restarts ahead of: past 30 minutes a continuous scan
            // silently becomes opportunistic, which for a "what is around me" screen reads as
            // "suddenly nothing is around". One stop/start pair per 20 minutes stays far inside
            // the platform's start-rate budget.
            final Disposable scanRefresh = Observable
                    .interval(SCAN_RESTART_INTERVAL_MS, SCAN_RESTART_INTERVAL_MS,
                            TimeUnit.MILLISECONDS, Schedulers.io())
                    .subscribe(tick -> {
                        try {
                            scanner.stopScan(callback);
                            scanner.startScan(
                                    findMyFramesOnly, settingsFor(this.scanMode), callback);
                        } catch (final Exception bluetoothWentAway) {
                            Log.w(TAG, "Could not restart the nearby device scan",
                                    bluetoothWentAway);
                            if (!emitter.isDisposed()) {
                                emitter.onComplete();
                            }
                        }
                    });

            emitter.setCancellable(() -> {
                Log.d(TAG, "Stopped scanning for nearby devices");
                scanRefresh.dispose();
                try {
                    scanner.stopScan(callback);
                } catch (final Exception bluetoothWentAway) {
                    // The adapter ended the scan itself; there is nothing left to stop, and a
                    // throw here has no subscriber to deliver to - it kills the process.
                    Log.d(TAG, "The nearby device scan had already ended",
                            bluetoothWentAway);
                }
            });
        }).subscribeOn(Schedulers.io());
    }

    private static ScanSettings settingsFor(final int mode) {
        return new ScanSettings.Builder().setScanMode(mode).build();
    }

    /** Kept in sync with {@link NearbyTagWatcher#SCAN_RESTART_INTERVAL_MS} for the same reason. */
    static final long SCAN_RESTART_INTERVAL_MS = TimeUnit.MINUTES.toMillis(20);

    /**
     * One scan result turned into a sighting, or null if it is not Find My.
     *
     * <p>Package-private so a JVM test can feed raw frames without a radio.
     */
    @Nullable
    NearbyDeviceSighting sightingFrom(final ScanResult result) {
        final ScanRecord record = result.getScanRecord();
        if (record == null || result.getDevice() == null) {
            return null;
        }

        return NearbyDeviceSighting.fromAdvertisement(
                result.getDevice().getAddress(),
                record.getManufacturerSpecificData(FindMyAdvertisement.APPLE_COMPANY_ID),
                result.getRssi(),
                this.clock.nowMs());
    }
}
