package dev.wander.android.opentagviewer.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.wander.android.opentagviewer.source.ExternalAccessory;
import dev.wander.android.opentagviewer.source.ExtraSourcesApi;
import dev.wander.android.opentagviewer.source.ExtraSourcesSettings;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Rings a Google Find Hub tracker the way its owner's Find Hub app does: the tracker is
 * recognised by its current EID in the FMDN advertisement (service data 0xFEAA), then its Beacon
 * Actions characteristic is read for a nonce and written a ring request authenticated with the
 * ring key (see {@link FmdnRingCommand}). The ring key and EIDs come from the googlefind service,
 * which holds the tracker's identity key; the ring key rings and nothing else.
 *
 * <p>Same contract as {@link BleAccessorySoundTrigger}: progress updates, exactly one DONE, never
 * an error.
 */
@SuppressLint("MissingPermission")   // checked through BlePermissions first
public final class FmdnRingTrigger implements AccessorySoundTrigger {
    private static final String TAG = FmdnRingTrigger.class.getSimpleName();
    private static final String PROTOCOL = "FMDN";

    private static final ParcelUuid FMDN_SERVICE_DATA = ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb");
    private static final UUID FAST_PAIR_SERVICE = UUID.fromString("0000fe2c-0000-1000-8000-00805f9b34fb");
    private static final UUID BEACON_ACTIONS = UUID.fromString("fe2c1238-8366-4814-8eb0-01de32100bea");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long SCAN_TIMEOUT_S = 30;
    private static final long GATT_TIMEOUT_S = 20;
    /** One ring lasts this long (deciseconds); continuous ringing re-rings before it ends. */
    private static final int RING_DECISECONDS = 300;
    private static final long CONTINUOUS_PAUSE_S = 20;

    @Override
    public Observable<BleSoundTriggerUpdate> playSound(final Context context, final String accessoryJson) {
        return this.run(context, accessoryJson, FmdnRingCommand.COMPONENTS_ALL);
    }

    @Override
    public Observable<BleSoundTriggerUpdate> playSoundContinuously(final Context context, final String accessoryJson) {
        return this.playSound(context, accessoryJson)
                .repeatWhen(completed -> completed.delay(CONTINUOUS_PAUSE_S, TimeUnit.SECONDS));
    }

    @Override
    public Completable stopSound(final Context context, final String accessoryJson) {
        return this.run(context, accessoryJson, FmdnRingCommand.COMPONENTS_STOP).ignoreElements().onErrorComplete();
    }

    private static BleSoundTriggerUpdate done(final BleSoundTriggerStatus status, final String message) {
        return BleSoundTriggerUpdate.done(new BleSoundTriggerResult(
                status, status == BleSoundTriggerStatus.SUCCESS ? PROTOCOL : null, message));
    }

    private Observable<BleSoundTriggerUpdate> run(final Context context, final String accessoryJson,
                                                  final byte components) {
        return Observable.<BleSoundTriggerUpdate>defer(() -> {
            if (!BlePermissions.granted(context)) {
                return Observable.just(done(BleSoundTriggerStatus.MISSING_PERMISSION,
                        "Bluetooth scan/connect permission not granted"));
            }
            final String googleId = ExternalAccessory.googleId(accessoryJson);
            if (googleId == null) {
                return Observable.just(done(BleSoundTriggerStatus.NO_CANDIDATE_MACS, "Not a Google Find Hub tracker"));
            }

            final ExtraSourcesApi.RingMaterial material;
            try {
                material = new ExtraSourcesApi(new ExtraSourcesSettings(context)).ringMaterial(googleId);
            } catch (final Exception e) {
                Log.w(TAG, "Could not get ring material for " + googleId, e);
                return Observable.just(done(BleSoundTriggerStatus.NO_CANDIDATE_MACS,
                        "Could not get the ring key from the Google Find Hub service: " + e.getMessage()));
            }

            return Observable.just(BleSoundTriggerUpdate.progress(BleSoundTriggerPhase.SCANNING))
                    .concatWith(findByEid(context, material.eids).toObservable()
                            .concatMap(device -> Observable.just(BleSoundTriggerUpdate.progress(BleSoundTriggerPhase.CONNECTING))
                                    .concatWith(ring(context, device, material.ringKey, components)
                                            .map(update -> update.withMatchedMac(device.getAddress())))))
                    .onErrorReturn(e -> e instanceof NotNearby
                            ? done(BleSoundTriggerStatus.NOT_NEARBY, e.getMessage())
                            : done(BleSoundTriggerStatus.FAILED, String.valueOf(e.getMessage())));
        }).subscribeOn(Schedulers.io());
    }

    private static final class NotNearby extends Exception {
        NotNearby(final String message) {
            super(message);
        }
    }

    /** Scans for an FMDN advertisement carrying one of the EIDs. */
    private static Single<BluetoothDevice> findByEid(final Context context, final List<String> eids) {
        return Single.<BluetoothDevice>create(emitter -> {
            final BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            final BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            final BluetoothLeScanner scanner = adapter == null || !adapter.isEnabled() ? null : adapter.getBluetoothLeScanner();
            if (scanner == null) {
                emitter.tryOnError(new IllegalStateException("Bluetooth is off"));
                return;
            }
            final AtomicBoolean found = new AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicInteger seen = new java.util.concurrent.atomic.AtomicInteger();
            Log.i(TAG, "Scanning for an FMDN tracker matching one of " + eids.size() + " EIDs");
            final ScanCallback callback = new ScanCallback() {
                @Override
                public void onScanResult(final int callbackType, final ScanResult result) {
                    final byte[] data = result.getScanRecord() == null ? null
                            : result.getScanRecord().getServiceData(FMDN_SERVICE_DATA);
                    if (data != null && seen.incrementAndGet() <= 40) {
                        Log.d(TAG, "FEAA " + FmdnRingCommand.hex(data) + " from " + result.getDevice().getAddress()
                                + (FmdnRingCommand.matchesEid(data, eids) ? " MATCH" : ""));
                    }
                    if (FmdnRingCommand.matchesEid(data, eids) && found.compareAndSet(false, true)) {
                        emitter.onSuccess(result.getDevice());
                    }
                }

                @Override
                public void onScanFailed(final int errorCode) {
                    emitter.tryOnError(new IllegalStateException("Bluetooth scan failed (" + errorCode + ")"));
                }
            };
            // Any FMDN advertisement; the EID is compared in the callback
            final ScanFilter filter = new ScanFilter.Builder().setServiceData(FMDN_SERVICE_DATA, new byte[0]).build();
            scanner.startScan(Collections.singletonList(filter),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
            emitter.setCancellable(() -> scanner.stopScan(callback));
        }).timeout(SCAN_TIMEOUT_S, TimeUnit.SECONDS, Single.error(new NotNearby("Tracker not found nearby")));
    }

    /** Connect, read the nonce, write the ring (or stop) request. */
    private static Observable<BleSoundTriggerUpdate> ring(final Context context, final BluetoothDevice device,
                                                          final byte[] ringKey, final byte components) {
        return Observable.<BleSoundTriggerUpdate>create(emitter -> {
            final AtomicBoolean finished = new AtomicBoolean(false);
            final BluetoothGatt[] gattRef = new BluetoothGatt[1];

            final BluetoothGattCallback callback = new BluetoothGattCallback() {
                private BluetoothGattCharacteristic actions;
                private int variant = 0;

                private void finish(final BleSoundTriggerUpdate update, final BluetoothGatt gatt) {
                    if (!finished.compareAndSet(false, true)) return;
                    if (!emitter.isDisposed()) {
                        emitter.onNext(update);
                        emitter.onComplete();
                    }
                    gatt.disconnect();
                }

                @Override
                public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
                    Log.i(TAG, "Connection state " + newState + " (status=" + status + ") " + device.getAddress());
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        gatt.close();
                        this.finish(done(BleSoundTriggerStatus.FAILED,
                                "Connection closed before the ring request was sent"), gatt);
                    }
                }

                @Override
                public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
                    final BluetoothGattService fastPair = gatt.getService(FAST_PAIR_SERVICE);
                    this.actions = fastPair == null ? null : fastPair.getCharacteristic(BEACON_ACTIONS);
                    if (this.actions == null) {
                        // Some trackers expose Beacon Actions outside the Fast Pair service
                        for (final BluetoothGattService svc : gatt.getServices()) {
                            final BluetoothGattCharacteristic c = svc.getCharacteristic(BEACON_ACTIONS);
                            if (c != null) { this.actions = c; break; }
                        }
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Service discovery status=" + status);
                    }
                    if (this.actions == null) {
                        final StringBuilder found = new StringBuilder();
                        for (final BluetoothGattService svc : gatt.getServices()) found.append(svc.getUuid()).append(' ');
                        Log.w(TAG, "No Beacon Actions char on " + device.getAddress() + "; services: " + found);
                        this.finish(done(BleSoundTriggerStatus.NO_SOUND_SERVICE,
                                "No FMDN Beacon Actions characteristic"), gatt);
                        return;
                    }
                    if (!emitter.isDisposed()) {
                        emitter.onNext(BleSoundTriggerUpdate.progress(BleSoundTriggerPhase.TRIGGERING));
                    }
                    final BluetoothGattDescriptor cccd = this.actions.getDescriptor(CCCD);
                    if (cccd != null) {
                        gatt.setCharacteristicNotification(this.actions, true);
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    } else {
                        gatt.readCharacteristic(this.actions);
                    }
                }

                @Override
                public void onCharacteristicChanged(final BluetoothGatt gatt, final BluetoothGattCharacteristic c, final byte[] value) {
                    Log.i(TAG, "Beacon Actions notification: " + FmdnRingCommand.hex(value));
                }

                @Override
                public void onDescriptorWrite(final BluetoothGatt gatt, final BluetoothGattDescriptor d, final int status) {
                    Log.i(TAG, "CCCD write status=" + status);
                    gatt.readCharacteristic(this.actions);
                }

                @Override
                public void onCharacteristicRead(final BluetoothGatt gatt, final BluetoothGattCharacteristic c,
                                                 final byte[] value, final int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        this.finish(done(BleSoundTriggerStatus.FAILED, "Reading the nonce failed (status=" + status + ")"), gatt);
                        return;
                    }
                    Log.i(TAG, "Beacon Actions read: " + FmdnRingCommand.hex(value == null ? new byte[0] : value));
                    try {
                        final byte[] request = FmdnRingCommand.ringWrite(ringKey, value, components,
                                RING_DECISECONDS, FmdnRingCommand.VOLUME_DEFAULT,
                                FmdnRingCommand.VARIANTS[this.variant]);
                        Log.i(TAG, "Writing: " + FmdnRingCommand.hex(request));
                        gatt.writeCharacteristic(this.actions, request, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    } catch (final IllegalArgumentException e) {
                        this.finish(done(BleSoundTriggerStatus.FAILED, e.getMessage()), gatt);
                    }
                }

                @Override
                public void onCharacteristicWrite(final BluetoothGatt gatt, final BluetoothGattCharacteristic c, final int status) {
                    Log.i(TAG, "Ring request (components=" + components + ", variant "
                            + FmdnRingCommand.VARIANTS[this.variant] + ") written to "
                            + device.getAddress() + ", status=" + status);
                    // 0x80 is the spec's "unauthenticated": the tracker is still connected, so try
                    // the next phrasing with a fresh nonce
                    if (status == 0x80 && this.variant + 1 < FmdnRingCommand.VARIANTS.length) {
                        this.variant++;
                        gatt.readCharacteristic(this.actions);
                        return;
                    }
                    this.finish(status == BluetoothGatt.GATT_SUCCESS
                            ? done(BleSoundTriggerStatus.SUCCESS, null)
                            // The tracker rejects a bad one-time key with an ATT error
                            : done(BleSoundTriggerStatus.FAILED, "The tracker refused the ring request (status=" + status + ")"), gatt);
                }
            };

            gattRef[0] = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            emitter.setCancellable(() -> {
                if (gattRef[0] != null) {
                    gattRef[0].disconnect();
                    gattRef[0].close();
                }
            });
        }).timeout(GATT_TIMEOUT_S, TimeUnit.SECONDS,
                Observable.just(done(BleSoundTriggerStatus.FAILED, "The tracker did not answer in time")));
    }
}
