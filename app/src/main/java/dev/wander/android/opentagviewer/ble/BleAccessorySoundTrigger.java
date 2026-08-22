package dev.wander.android.opentagviewer.ble;

import android.content.Context;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.wander.android.opentagviewer.python.AccessoryMacResolver;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * The real {@link AccessorySoundTrigger}: resolves candidate MACs through Python, scans for one
 * of them, and triggers the accessory's GATT sound service once found.
 *
 * <p><b>What this has actually been run against, and what it has not.</b> The GATT protocol
 * logic in {@link BleGattSoundTrigger} is a port of a Kotlin prototype (a personal companion
 * project, TrackerHunter) that was exercised against real AirTags over BLE. This class - the
 * permission gate, the MAC resolution via the pinned FindMy.py fork, and wiring the scan result
 * into the trigger - has been read carefully but not run end-to-end on a device by whoever
 * wrote it. Per AGENTS.md rule 2: say so rather than claim otherwise.
 */
public class BleAccessorySoundTrigger implements AccessorySoundTrigger {

    /**
     * How long to scan before giving up. Long enough that an AirTag's ~1 second-ish advertising
     * interval is seen several times over, short enough that tapping the button and walking away
     * does not leave a scan running indefinitely.
     */
    private static final long SCAN_TIMEOUT_MS = 15_000L;

    private final AccessoryMacResolver macResolver;

    public BleAccessorySoundTrigger(final AccessoryMacResolver macResolver) {
        this.macResolver = macResolver;
    }

    @Override
    public Single<BleSoundTriggerResult> playSound(final Context context, final String accessoryJson) {
        return Single.defer(() -> {
            if (!BlePermissions.granted(context)) {
                return Single.just(new BleSoundTriggerResult(BleSoundTriggerStatus.MISSING_PERMISSION,
                        null, "Bluetooth scan/connect permission not granted"));
            }

            // Blocking - starts a Python interpreter. Safe here because the whole chain is
            // subscribed on Schedulers.io() below, same as PythonAppleService's calls.
            final List<String> macs = macResolver.currentMacAddresses(accessoryJson);
            if (macs.isEmpty()) {
                return Single.just(new BleSoundTriggerResult(BleSoundTriggerStatus.NO_CANDIDATE_MACS,
                        null, "Could not resolve a current MAC address for this accessory"));
            }
            final Set<String> candidates = new HashSet<>(macs);

            return NearbyAccessoryScanner.findNearby(context, candidates, SCAN_TIMEOUT_MS)
                    .flatMap(device -> BleGattSoundTrigger.trigger(context, device))
                    .onErrorReturn(BleAccessorySoundTrigger::asResult);
        }).subscribeOn(Schedulers.io());
    }

    private static BleSoundTriggerResult asResult(final Throwable error) {
        if (error instanceof NearbyAccessoryScanner.NotNearbyException) {
            return new BleSoundTriggerResult(BleSoundTriggerStatus.NOT_NEARBY, null, error.getMessage());
        }
        return new BleSoundTriggerResult(BleSoundTriggerStatus.FAILED, null, String.valueOf(error.getMessage()));
    }
}
