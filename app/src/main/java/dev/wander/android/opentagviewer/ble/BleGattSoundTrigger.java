package dev.wander.android.opentagviewer.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.rxjava3.core.Single;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Play-sound GATT trigger for Find My / DULT-compatible accessories.
 *
 * <p>Tries three known protocols, in the same priority order as AirGuard
 * (https://github.com/seemoo-lab/AirGuard, Apache-2.0) uses - the UUIDs and opcodes are verified
 * against that project's current source ({@code database/models/device/types/AppleFindMy.kt} for
 * the DULT and AirTag-specific paths, {@code GoogleFindMyNetwork.kt} for confirming Google's own
 * sound service is byte-for-byte the same one DULT defines).
 *
 * <p>Ported from a Kotlin prototype (a personal companion project, TrackerHunter) that already
 * exercised this against real AirTags; this is the same state machine expressed as a Java
 * {@link Single} instead of a coroutine, to match this app's RxJava3 convention. See
 * {@code BleAccessorySoundTrigger} for the honesty about what has and has not actually been run.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BleGattSoundTrigger {
    private static final String TAG = BleGattSoundTrigger.class.getSimpleName();

    private static final UUID DULT_SERVICE =
            UUID.fromString("15190001-12F4-C226-88ED-2AC5579F2A85");
    private static final UUID DULT_CHARACTERISTIC =
            UUID.fromString("8E0C0001-1D68-FB92-BF61-48377421680E");
    private static final byte[] DULT_START_OPCODE = {0x00, 0x03};

    private static final String FINDMY_SERVICE_SHORT = "fd44";
    private static final UUID FINDMY_CHARACTERISTIC =
            UUID.fromString("4F860003-943B-49EF-BED4-2F730304427A");
    private static final byte[] FINDMY_START_OPCODE = {0x01, 0x00, 0x03};

    private static final UUID AIRTAG_SERVICE =
            UUID.fromString("7DFC9000-7D1C-4951-86AA-8D9728F8D66C");
    private static final UUID AIRTAG_CHARACTERISTIC =
            UUID.fromString("7DFC9001-7D1C-4951-86AA-8D9728F8D66C");
    private static final byte[] AIRTAG_PLAY_VALUE = {(byte) 0xAF};

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /**
     * Connects to {@code device}, tries all three protocols, and completes once the first
     * matching one's start command has been written (or all three failed). Does not wait for the
     * sound to finish playing.
     *
     * <p>Emits exactly once. Disposing the returned {@link Single} before it emits disconnects
     * and closes the GATT connection rather than leaving it open in the background.
     */
    @SuppressLint("MissingPermission")
    public static Single<BleSoundTriggerResult> trigger(
            final Context context, final BluetoothDevice device) {
        return Single.create(emitter -> {
            final AtomicBoolean resumed = new AtomicBoolean(false);
            final BluetoothGatt[] gattRef = new BluetoothGatt[1];

            final BluetoothGattCallback callback = new BluetoothGattCallback() {
                private BluetoothGattCharacteristic pendingCharacteristic;
                private byte[] pendingOpcode;
                private String pendingProtocolName;

                private void finish(final BleSoundTriggerResult result) {
                    // Guards against a callback landing twice (e.g. a disconnect that follows a
                    // successful write) - only the first one reaches the emitter, matching
                    // Single's exactly-once contract.
                    if (!resumed.compareAndSet(false, true)) return;
                    if (!emitter.isDisposed()) emitter.onSuccess(result);
                }

                @Override
                public void onConnectionStateChange(
                        final BluetoothGatt gatt, final int status, final int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "Connected to " + device.getAddress()
                                + ", discovering services");
                        gatt.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "Disconnected from " + device.getAddress());
                        gatt.close();
                        // A disconnect after a successful write is the normal AirTag completion
                        // signal, not a failure - finish() already resumed by then, so this call
                        // is a no-op (see the AtomicBoolean guard above).
                        finish(new BleSoundTriggerResult(BleSoundTriggerStatus.FAILED, null,
                                "Connection closed before a sound command was sent"));
                    }
                }

                @Override
                public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finish(new BleSoundTriggerResult(BleSoundTriggerStatus.FAILED, null,
                                "Service discovery failed (status=" + status + ")"));
                        gatt.disconnect();
                        return;
                    }

                    final BluetoothGattCharacteristic dult = characteristicOf(gatt, DULT_SERVICE, DULT_CHARACTERISTIC);
                    final BluetoothGattCharacteristic findMy = findMyCharacteristic(gatt);
                    final BluetoothGattCharacteristic airtag = characteristicOf(gatt, AIRTAG_SERVICE, AIRTAG_CHARACTERISTIC);

                    if (dult != null) {
                        enableNotifyThenWrite(gatt, dult, DULT_START_OPCODE, "DULT");
                    } else if (findMy != null) {
                        enableNotifyThenWrite(gatt, findMy, FINDMY_START_OPCODE, "FindMy (fd44)");
                    } else if (airtag != null) {
                        pendingProtocolName = "AirTag";
                        writeCharacteristicCompat(gatt, airtag, AIRTAG_PLAY_VALUE);
                    } else {
                        finish(new BleSoundTriggerResult(BleSoundTriggerStatus.NO_SOUND_SERVICE, null,
                                "No known sound service found (checked DULT, FindMy, AirTag)"));
                        gatt.disconnect();
                    }
                }

                private void enableNotifyThenWrite(
                        final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic,
                        final byte[] opcode, final String protocolName) {
                    pendingProtocolName = protocolName;
                    pendingCharacteristic = characteristic;
                    pendingOpcode = opcode;

                    gatt.setCharacteristicNotification(characteristic, true);
                    final BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
                    if (descriptor == null) {
                        // No CCCD - just write directly, matching the AirTag path.
                        writeCharacteristicCompat(gatt, characteristic, opcode);
                        return;
                    }
                    writeDescriptorCompat(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                }

                @Override
                public void onDescriptorWrite(
                        final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor, final int status) {
                    if (pendingCharacteristic == null || pendingOpcode == null) return;
                    Log.d(TAG, "CCCD write status=" + status + ", writing start opcode to "
                            + pendingCharacteristic.getUuid());
                    writeCharacteristicCompat(gatt, pendingCharacteristic, pendingOpcode);
                }

                @Override
                public void onCharacteristicWrite(
                        final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic,
                        final int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        final String protocol = pendingProtocolName == null ? "unknown" : pendingProtocolName;
                        Log.i(TAG, "Sound triggered via " + protocol + " on " + device.getAddress());
                        finish(new BleSoundTriggerResult(BleSoundTriggerStatus.SUCCESS, protocol, null));
                        // AirTag disconnects on its own once the sound finishes; DULT/FindMy
                        // don't, so force it here - gives every trigger() call a bounded
                        // lifetime.
                        if (!"AirTag".equals(protocol)) {
                            gatt.disconnect();
                        }
                    } else {
                        finish(new BleSoundTriggerResult(BleSoundTriggerStatus.FAILED, null,
                                "Write failed (status=" + status + ")"));
                        gatt.disconnect();
                    }
                }
            };

            gattRef[0] = device.connectGatt(context, false, callback);

            emitter.setCancellable(() -> {
                if (gattRef[0] != null) {
                    gattRef[0].disconnect();
                    gattRef[0].close();
                }
            });
        });
    }

    private static BluetoothGattCharacteristic characteristicOf(
            final BluetoothGatt gatt, final UUID service, final UUID characteristic) {
        final BluetoothGattService svc = gatt.getService(service);
        return svc == null ? null : svc.getCharacteristic(characteristic);
    }

    /** The FindMy/DULT service UUID is vendor-suffixed; matched on its distinguishing prefix. */
    private static BluetoothGattCharacteristic findMyCharacteristic(final BluetoothGatt gatt) {
        for (final BluetoothGattService service : gatt.getServices()) {
            if (service.getUuid().toString().toLowerCase(Locale.ROOT).contains(FINDMY_SERVICE_SHORT)) {
                return service.getCharacteristic(FINDMY_CHARACTERISTIC);
            }
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    private static void writeCharacteristicCompat(
            final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic, final byte[] value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } else {
            characteristic.setValue(value);
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            gatt.writeCharacteristic(characteristic);
        }
    }

    @SuppressLint("MissingPermission")
    private static void writeDescriptorCompat(
            final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor, final byte[] value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value);
        } else {
            descriptor.setValue(value);
            gatt.writeDescriptor(descriptor);
        }
    }
}
