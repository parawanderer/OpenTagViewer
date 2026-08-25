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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.wander.android.opentagviewer.python.AccessoryMacResolver;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Reports the user's own tags as this phone hears them, for as long as somebody is subscribed.
 *
 * <p><b>Who runs it decides what it costs.</b> A screen subscribes in {@code onResume} and
 * disposes in {@code onPause}, so the radio is on only while somebody is looking - that is the
 * default, and it keeps the app a display feature. {@code NearbyScanService} runs the same class
 * continuously when the user turns background scanning on, which is a recording feature and is
 * why it is opt-in and carries a permanent notification.
 *
 * <p>The difference reaches this class as {@link #scanMode}, and the two callers sit at
 * opposite ends of it. A screen is open because somebody is looking for a tag right now, so it
 * scans at {@code SCAN_MODE_LOW_LATENCY} - the radio listening continuously, which is what makes
 * the signal meter move as you walk toward something. That is affordable precisely because a
 * screen is open for minutes, not days.
 *
 * <p>The service takes {@code SCAN_MODE_BALANCED} instead: a quarter of the radio time, running
 * all day. Cheaper still exists, and was tried - low power left gaps of over a minute with a tag
 * in a pocket, which the left-behind rule then has to see through, and every gap it cannot costs
 * a full-power verification burst of its own.
 *
 * <p>{@code SCAN_MODE_BALANCED} - a middle ground between the low-latency mode
 * {@link NearbyAccessoryScanner} uses and this class's own original {@code SCAN_MODE_LOW_POWER}.
 * Low-power's short scan window and multi-second sleep between them meant several of a tag's
 * own advertisements arrived in a burst whenever a window happened to line up, then nothing for
 * several seconds until the next one - honest about what low-power scanning actually looks
 * like, but a person watching this screen is specifically looking for a tag right now, the same
 * reason {@link NearbyAccessoryScanner} justifies its own higher power draw. Still not
 * low-latency: this runs for as long as a screen stays open rather than for a few bounded
 * seconds after a tap, so it keeps some of the duty cycle low-latency forgoes entirely.
 */
public class NearbyTagWatcher {
    private static final String TAG = NearbyTagWatcher.class.getSimpleName();

    /** Injectable so a test can drive the whole pipeline without a radio. */
    interface Clock {
        long nowMs();
    }

    /**
     * Told, off the scan callback thread and throttled, when a sighting matches one of the
     * caller's own tags - so a passive scan can feed alignment self-correction the same way the
     * ring button's explicit scan does. Real: {@code BeaconRepository#recordAccessorySighting}.
     *
     * <p>Without this, a tag only ever heard through this class - never rung, and refreshed by
     * the periodic Apple-network fetch only as often as that runs - has no way to correct a
     * stored alignment that has drifted since the last fetch. It stays inside
     * {@code currentMacAddresses}' 12 hour margin for a while and then, once the drift exceeds
     * that, simply stops being found - with nothing failing anywhere to say why.
     *
     * <p><b>Handed the whole sighting, not just the address it was heard at.</b> Alignment only
     * needs the address, but the same advertisement also carries the tag's battery level, and
     * that is worth keeping past the moment it was heard - see
     * {@code BeaconRepository#storeLastSighting}. Both writes belong to the same event and are
     * throttled by the same rule, so there is one callback carrying everything the advertisement
     * said rather than a second listener firing on its own schedule.
     */
    public interface SightingListener {
        void onSighting(NearbyTagSighting sighting, String mac);
    }

    /**
     * How often {@link SightingListener#onSighting} fires for the same beacon.
     *
     * <p>A tag in range advertises every one to three seconds, and each one is a candidate
     * correction - reporting every single one would start a Python interpreter that often. A
     * correction that already matches the stored alignment is a no-op on the far side anyway,
     * so nothing is lost by not attempting most of them.
     */
    static final long SIGHTING_LISTENER_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);

    private final AccessoryMacResolver macResolver;
    private final NearbyTagIndex index;
    private final Clock clock;

    @Nullable
    private final SightingListener sightingListener;

    /** Written and read on the Bluetooth scan callback thread, but also constructed and first
     * touched elsewhere - concurrent map so there is no thread this is unsafe from. */
    private final Map<String, Long> lastListenerCallMs = new ConcurrentHashMap<>();

    /** Guards {@link #maybeRebuildIndex} so a stale index triggers one rebuild, not one per
     * advertisement that arrives while the first is still running. */
    private final AtomicBoolean indexRebuildInFlight = new AtomicBoolean(false);

    /**
     * How hard the radio listens.
     *
     * <p>{@code SCAN_MODE_BALANCED} for a screen, {@code SCAN_MODE_LOW_POWER} for the service.
     * The difference is the duty cycle: low power leaves longer gaps between listening windows,
     * so a tag takes longer to be noticed - acceptable when nobody is watching the screen, and
     * not acceptable when they are.
     */
    private final int scanMode;

    public NearbyTagWatcher(final AccessoryMacResolver macResolver) {
        this(macResolver, null);
    }

    public NearbyTagWatcher(
            final AccessoryMacResolver macResolver, @Nullable final SightingListener listener) {
        this(macResolver, listener, ScanSettings.SCAN_MODE_BALANCED);
    }

    public NearbyTagWatcher(
            final AccessoryMacResolver macResolver,
            @Nullable final SightingListener listener,
            final int scanMode) {
        this(macResolver, listener, scanMode, new NearbyTagIndex(), System::currentTimeMillis);
    }

    NearbyTagWatcher(final AccessoryMacResolver macResolver,
                     @Nullable final SightingListener sightingListener,
                     final int scanMode,
                     final NearbyTagIndex index,
                     final Clock clock) {
        this.macResolver = macResolver;
        this.sightingListener = sightingListener;
        this.scanMode = scanMode;
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
                    // Checked per scan result, of anything, not only our own tags: once the
                    // index is stale, our own tag's advertisements are exactly the ones that
                    // no longer match, so they cannot be the trigger.
                    maybeRebuildIndex(accessoryJsonByBeaconId);

                    final NearbyTagSighting sighting = sightingFrom(result);
                    if (sighting == null) {
                        return;
                    }
                    if (!emitter.isDisposed()) {
                        emitter.onNext(sighting);
                    }
                    maybeNotifySightingListener(sighting, result.getDevice().getAddress());
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

            // Filtered in hardware, not only in software. An unfiltered scan delivered every
            // BLE frame of every device in earshot to the callback - tens per second in an
            // ordinary flat, nearly all of them discarded by sightingFrom. The controller can
            // do that discarding itself: Apple's company ID plus the offline-finding type byte
            // is exactly the check FindMyAdvertisement.parse starts with, so nothing that would
            // have matched is lost, and the callback now fires only for Find My frames.
            final List<ScanFilter> findMyFramesOnly = List.of(new ScanFilter.Builder()
                    .setManufacturerData(FindMyAdvertisement.APPLE_COMPANY_ID,
                            new byte[]{FindMyAdvertisement.TYPE_OFFLINE_FINDING},
                            new byte[]{(byte) 0xFF})
                    .build());
            final ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(this.scanMode)
                    .build();

            scanner.startScan(findMyFramesOnly, settings, callback);

            // Restarted well before the platform's 30 minute mark: Android silently downgrades
            // any scan running longer than that to SCAN_MODE_OPPORTUNISTIC, which only delivers
            // results while some other app happens to be scanning - a screen left open for half
            // an hour would go quietly deaf, the same presentation as every other failure this
            // class has had to chase. One stop/start pair per 20 minutes is far inside the
            // 5-starts-per-30-seconds budget.
            final Disposable scanRefresh = Observable
                    .interval(SCAN_RESTART_INTERVAL_MS, SCAN_RESTART_INTERVAL_MS,
                            TimeUnit.MILLISECONDS, Schedulers.io())
                    .subscribe(tick -> {
                        try {
                            scanner.stopScan(callback);
                            scanner.startScan(findMyFramesOnly, settings, callback);
                            Log.d(TAG, "Restarted the nearby scan before the platform's "
                                    + "long-scan downgrade");
                        } catch (final Exception e) {
                            // Bluetooth went away between the stop and the start. Complete, so
                            // the caller's ordinary retry takes over rather than this looking
                            // like a scan that is still running.
                            Log.w(TAG, "Could not restart the nearby scan", e);
                            if (!emitter.isDisposed()) {
                                emitter.onComplete();
                            }
                        }
                    });

            emitter.setCancellable(() -> {
                Log.d(TAG, "Stopped watching for nearby tags");
                scanRefresh.dispose();
                scanner.stopScan(callback);
            });
        }).subscribeOn(Schedulers.io());
    }

    /**
     * How often the running scan is stopped and started again - under Android's 30 minute
     * limit, past which a continuous scan is silently downgraded to opportunistic delivery.
     */
    static final long SCAN_RESTART_INTERVAL_MS = TimeUnit.MINUTES.toMillis(20);

    /**
     * Rebuilds the index in the background once it has gone stale, mid-subscription.
     *
     * <p><b>Without this, a watch outliving the key rollover goes quietly deaf.</b> The index
     * is checked and rebuilt when {@link #watch} subscribes, but a screen left open longer than
     * {@link NearbyTagIndex#MAX_AGE_MS} used to keep matching against rolled-past addresses for
     * as long as the subscription lived - the tag next to the phone simply stopped appearing,
     * with nothing failing anywhere, until an onPause/onResume bounce built a fresh watcher.
     * Exactly the failure mode the expiry rule exists to prevent, made unreachable by only
     * consulting it once.
     *
     * <p>Cheap on the hot path: a stale check is two long compares, and the rebuild itself -
     * blocking Python, one interpreter call per tag - is handed to {@link Schedulers#io()}
     * behind a single-flight guard. Until it completes, matching continues against the old
     * index, which can only miss what it would have missed anyway.
     */
    private void maybeRebuildIndex(final Map<String, String> accessoryJsonByBeaconId) {
        if (!this.index.isStale(this.clock.nowMs())) {
            return;
        }
        if (!this.indexRebuildInFlight.compareAndSet(false, true)) {
            return;
        }
        Schedulers.io().scheduleDirect(() -> {
            try {
                this.index.rebuild(accessoryJsonByBeaconId, this.macResolver, this.clock.nowMs());
                Log.d(TAG, "Rebuilt the nearby index mid-watch: " + this.index.size()
                        + " candidate address(es) for " + accessoryJsonByBeaconId.size()
                        + " tag(s)");
            } finally {
                this.indexRebuildInFlight.set(false);
            }
        });
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
        final NearbyTagIndex.Match match = this.index.matchFor(result.getDevice().getAddress());
        if (match == null) {
            return null;
        }

        return new NearbyTagSighting(match.getBeaconId(), match.getKeyIndex(), result.getRssi(),
                advertisement.getBatteryLevel(), advertisement.getStatusByte(),
                advertisement.getState(), this.clock.nowMs());
    }

    /**
     * Calls {@link #sightingListener}, throttled per beacon, off the calling thread.
     *
     * <p>Off-thread because the real listener persists to Room through a Python call - see the
     * interface doc - and this runs from {@code onScanResult}, which must not block.
     */
    void maybeNotifySightingListener(final NearbyTagSighting sighting, final String mac) {
        if (this.sightingListener == null) {
            return;
        }
        final long nowMs = this.clock.nowMs();
        final Long lastCallMs = this.lastListenerCallMs.get(sighting.getBeaconId());
        if (lastCallMs != null && nowMs - lastCallMs < SIGHTING_LISTENER_INTERVAL_MS) {
            return;
        }
        this.lastListenerCallMs.put(sighting.getBeaconId(), nowMs);

        Schedulers.io().scheduleDirect(() -> this.sightingListener.onSighting(sighting, mac));
    }
}
