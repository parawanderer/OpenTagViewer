package dev.wander.android.opentagviewer.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Scans for a BLE advertisement whose address matches one of an accessory's currently-expected
 * MAC addresses - see {@link BleAccessoryMatcher} - and resolves with the first one seen.
 *
 * <p>Unfiltered scan rather than a {@code ScanFilter} on the address, deliberately: the address
 * that matters is the one Android reports on the {@link ScanResult}, and a filter is matched
 * against the *raw advertisement bytes* the platform saw before it decided what address to
 * report - the two need not agree on every OEM's stack. Matching after the fact in
 * {@link BleAccessoryMatcher} is the same trade AirGuard and the TrackerHunter prototype this
 * was ported from both made, for the same reason.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NearbyAccessoryScanner {

    /** A scan finished without seeing any of the candidate addresses. */
    public static final class NotNearbyException extends Exception {
        NotNearbyException() {
            super("No candidate MAC address was seen advertising within the scan window");
        }
    }

    @SuppressLint("MissingPermission")
    public static Single<BluetoothDevice> findNearby(
            final Context context, final Set<String> candidateMacs, final long timeoutMs) {
        return Observable.<BluetoothDevice>create(emitter -> {
            final BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            final BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            final BluetoothLeScanner scanner =
                    adapter == null ? null : adapter.getBluetoothLeScanner();

            if (scanner == null) {
                emitter.onError(new IllegalStateException(
                        "No BLE scanner available (Bluetooth off, or unsupported)"));
                return;
            }

            final ScanCallback callback = new ScanCallback() {
                @Override
                public void onScanResult(final int callbackType, final ScanResult result) {
                    final BluetoothDevice device = result.getDevice();
                    if (BleAccessoryMatcher.matches(device.getAddress(), candidateMacs)
                            && !emitter.isDisposed()) {
                        emitter.onNext(device);
                        emitter.onComplete();
                    }
                }

                @Override
                public void onScanFailed(final int errorCode) {
                    if (!emitter.isDisposed()) {
                        emitter.onError(new IllegalStateException(
                                "BLE scan failed (errorCode=" + errorCode + ")"));
                    }
                }
            };

            // LOW_LATENCY over the default balanced mode: this only ever runs for the few
            // seconds after the user explicitly asked to trigger a sound, not continuously in
            // the background, so there is no battery budget to protect here.
            final ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, callback);

            emitter.setCancellable(() -> scanner.stopScan(callback));
        })
                .firstOrError()
                .timeout(timeoutMs, TimeUnit.MILLISECONDS)
                .onErrorResumeNext(error -> error instanceof java.util.concurrent.TimeoutException
                        ? Single.error(new NotNearbyException())
                        : Single.error(error));
    }
}
