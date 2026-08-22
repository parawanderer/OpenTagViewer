package dev.wander.android.opentagviewer.ble;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.python.AccessoryMacResolver;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * The real {@link AccessorySoundTrigger}: resolves candidate MACs through Python, scans for one
 * of them, and triggers the accessory's GATT sound service once found.
 *
 * <p><b>What this has actually been run against, and what it has not.</b> The GATT protocol
 * logic in {@link BleGattSoundTrigger} is a port of a Kotlin prototype (a personal companion
 * project, TrackerHunter) that was exercised against real AirTags over BLE. This class's own
 * orchestration - the permission gate, the retry count, the continuous-repeat wiring - has a
 * JVM test suite ({@code BleAccessorySoundTriggerTest}) exercising it against fakes, but has not
 * been run end-to-end on a device by whoever wrote it. Per AGENTS.md rule 2: say so rather than
 * claim otherwise.
 *
 * <p><b>Why the BLE pieces are constructor-injected rather than static calls to
 * {@link NearbyAccessoryScanner}/{@link BleGattSoundTrigger}/{@link BlePermissions}.</b> Those
 * three need Android hardware to run for real, which a JVM test cannot arrange - same reasoning
 * as {@code AppDependencies} injecting {@code HardwareDescriber} instead of calling Chaquopy
 * directly. This class's own logic (which status is worth retrying, how many times, what a
 * missing permission or an empty candidate set short-circuits to) is what the seams exist to
 * test, without needing Bluetooth or a device to do it.
 *
 * <p><b>Why {@code <D>} at all, rather than just {@code BluetoothDevice}.</b> A found device is
 * opaque to every line of logic in this class - it is looked at nowhere, only handed from the
 * scanner seam to the GATT seam. Fixing it to {@code BluetoothDevice} would mean the test suite
 * needs one, and the real SDK class has no public constructor and no test double in this project
 * (no Robolectric here - see AGENTS.md's JVM-vs-instrumented split). A type parameter lets the
 * test use a plain {@code String} as a stand-in and this class stays none the wiser; production
 * code fixes {@code D} to {@code BluetoothDevice} once, in the public constructor's inferred type.
 */
public class BleAccessorySoundTrigger<D> implements AccessorySoundTrigger {

    /** Whether the required Bluetooth permission(s) are granted. Real: {@link BlePermissions#granted}. */
    interface PermissionCheck {
        boolean granted(Context context);
    }

    /** Scans for one of the candidate MACs. Real: {@link NearbyAccessoryScanner#findNearby}. */
    interface Scanner<D> {
        Single<D> findNearby(Context context, Set<String> candidateMacs, long timeoutMs);
    }

    /** Runs the GATT handshake against a found device. Real: {@link BleGattSoundTrigger#trigger}. */
    interface GattTrigger<D> {
        Observable<BleSoundTriggerUpdate> trigger(Context context, D device);
    }

    /**
     * How long to scan before giving up. Long enough that an AirTag's ~1 second-ish advertising
     * interval is seen several times over, short enough that tapping the button and walking away
     * does not leave a scan running indefinitely.
     */
    private static final long SCAN_TIMEOUT_MS = 15_000L;

    /**
     * How many GATT attempts one found device gets before this counts as failed and the caller
     * decides what to do next (for {@link #playSoundContinuously}, that means re-scanning).
     *
     * <p>BLE connection setup is failure-prone in ways that mean nothing about the accessory
     * itself - a stale radio state, a busy Bluetooth stack, a connection that timed out for no
     * reason a retry wouldn't also hit. Only worth it for {@link BleSoundTriggerStatus#FAILED}:
     * {@link BleSoundTriggerStatus#NO_SOUND_SERVICE} means the connection worked and nothing
     * this app recognises was there, which retrying the same device will not change.
     */
    private static final int GATT_ATTEMPTS = 3;

    /** Pause between attempts, so a retry isn't fired at a radio still settling from the
     * previous attempt's disconnect. */
    private static final long GATT_RETRY_DELAY_MS = 800L;

    /**
     * How long {@link #playSoundContinuously} waits after one attempt (found or not) before the
     * next. Short enough to feel responsive while walking toward a tag; long enough that a
     * successful AirTag chirp (a few seconds) has time to finish before the next scan starts.
     */
    private static final long CONTINUOUS_PING_PAUSE_MS = 4_000L;

    private final AccessoryMacResolver macResolver;
    private final PermissionCheck permissionCheck;
    private final Scanner<D> scanner;
    private final GattTrigger<D> gattTrigger;
    private final int gattAttempts;
    private final long gattRetryDelayMs;
    private final long continuousPingPauseMs;

    /**
     * The real thing: {@code D} fixed to {@link BluetoothDevice}, and every seam wired to its
     * real Android implementation. A static factory rather than a public constructor because a
     * plain constructor on a generic class cannot pin {@code D} for its caller - the seam method
     * references here are concretely {@code BluetoothDevice}-typed, so the constructor itself
     * has to be the one that says so.
     */
    public static BleAccessorySoundTrigger<BluetoothDevice> forRealBluetooth(
            final AccessoryMacResolver macResolver) {
        return new BleAccessorySoundTrigger<>(macResolver, BlePermissions::granted,
                NearbyAccessoryScanner::findNearby, BleGattSoundTrigger::trigger,
                GATT_ATTEMPTS, GATT_RETRY_DELAY_MS, CONTINUOUS_PING_PAUSE_MS);
    }

    /** Package-private: only {@code BleAccessorySoundTriggerTest} constructs one of these with fakes. */
    BleAccessorySoundTrigger(
            final AccessoryMacResolver macResolver,
            final PermissionCheck permissionCheck,
            final Scanner<D> scanner,
            final GattTrigger<D> gattTrigger,
            final int gattAttempts,
            final long gattRetryDelayMs,
            final long continuousPingPauseMs) {
        this.macResolver = macResolver;
        this.permissionCheck = permissionCheck;
        this.scanner = scanner;
        this.gattTrigger = gattTrigger;
        this.gattAttempts = gattAttempts;
        this.gattRetryDelayMs = gattRetryDelayMs;
        this.continuousPingPauseMs = continuousPingPauseMs;
    }

    @Override
    public Observable<BleSoundTriggerUpdate> playSound(final Context context, final String accessoryJson) {
        return Observable.<BleSoundTriggerUpdate>defer(() -> {
            if (!this.permissionCheck.granted(context)) {
                return Observable.just(BleSoundTriggerUpdate.done(new BleSoundTriggerResult(
                        BleSoundTriggerStatus.MISSING_PERMISSION, null,
                        "Bluetooth scan/connect permission not granted")));
            }

            // Blocking - starts a Python interpreter. Safe here because the whole chain is
            // subscribed on Schedulers.io() below, same as PythonAppleService's calls.
            final List<String> macs = this.macResolver.currentMacAddresses(accessoryJson);
            if (macs.isEmpty()) {
                return Observable.just(BleSoundTriggerUpdate.done(new BleSoundTriggerResult(
                        BleSoundTriggerStatus.NO_CANDIDATE_MACS, null,
                        "Could not resolve a current MAC address for this accessory")));
            }
            final Set<String> candidates = new HashSet<>(macs);

            return Observable.just(BleSoundTriggerUpdate.progress(BleSoundTriggerPhase.SCANNING))
                    .concatWith(this.scanner.findNearby(context, candidates, SCAN_TIMEOUT_MS)
                            .toObservable()
                            .flatMap(device -> this.triggerWithRetry(context, device, this.gattAttempts))
                            .onErrorReturn(BleAccessorySoundTrigger::asDoneUpdate));
        }).subscribeOn(Schedulers.io());
    }

    /**
     * {@link GattTrigger#trigger}, retried up to {@code attemptsLeft} times as long as each
     * failure is {@link BleSoundTriggerStatus#FAILED} - see {@link #GATT_ATTEMPTS}. Only the
     * final attempt's DONE reaches the caller; earlier failed attempts are swallowed in favour
     * of a fresh {@link BleSoundTriggerPhase#CONNECTING} and another try.
     */
    private Observable<BleSoundTriggerUpdate> triggerWithRetry(
            final Context context, final D device, final int attemptsLeft) {
        return this.gattTrigger.trigger(context, device)
                .concatMap(update -> {
                    final boolean isRetryableFailure = update.getPhase() == BleSoundTriggerPhase.DONE
                            && update.getResult().getStatus() == BleSoundTriggerStatus.FAILED;
                    if (!isRetryableFailure || attemptsLeft <= 1) {
                        return Observable.just(update);
                    }

                    return Observable.timer(this.gattRetryDelayMs, TimeUnit.MILLISECONDS)
                            .flatMap(tick -> Observable
                                    .just(BleSoundTriggerUpdate.progress(BleSoundTriggerPhase.CONNECTING))
                                    .concatWith(Observable.defer(() ->
                                            this.triggerWithRetry(context, device, attemptsLeft - 1))));
                });
    }

    @Override
    public Observable<BleSoundTriggerUpdate> playSoundContinuously(
            final Context context, final String accessoryJson) {
        // playSound completes after its DONE item; repeatWhen re-subscribes it once the delayed
        // completion signal fires, which is what turns "do it once" into "do it again after a
        // pause", forever, until the subscriber disposes.
        return this.playSound(context, accessoryJson)
                .repeatWhen(completed -> completed.delay(this.continuousPingPauseMs, TimeUnit.MILLISECONDS));
    }

    private static BleSoundTriggerUpdate asDoneUpdate(final Throwable error) {
        if (error instanceof NearbyAccessoryScanner.NotNearbyException) {
            return BleSoundTriggerUpdate.done(new BleSoundTriggerResult(
                    BleSoundTriggerStatus.NOT_NEARBY, null, error.getMessage()));
        }
        return BleSoundTriggerUpdate.done(new BleSoundTriggerResult(
                BleSoundTriggerStatus.FAILED, null, String.valueOf(error.getMessage())));
    }
}
