package dev.wander.android.opentagviewer;

import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.ble.BleSoundTriggerPhase;
import dev.wander.android.opentagviewer.ble.BleSoundTriggerUpdate;
import dev.wander.android.opentagviewer.ble.NearbyTagSighting;
import dev.wander.android.opentagviewer.db.repo.BeaconRepository;

/**
 * The one place a Bluetooth sighting is written down, for every caller and both kinds of
 * sighting: the alignment it proves and the battery level it reported.
 *
 * <p><b>Not the position.</b> A sighting means the tag is with whoever is holding the phone, so
 * writing a position for every one of them records where the <i>user</i> went, not where the tag
 * is - and does it while the answer is "still here". {@code NearbyScanService} writes a position
 * at the two moments that carry information instead: when a tag turns up, and when it stops
 * being heard.
 *
 * <p>Public rather than package-private since {@code NearbyScanService} joined the two screens
 * as a caller. The point of the class is that there is exactly one of these, and a service in
 * another package needing its own copy of the policy would be the failure it exists to prevent.
 *
 * <p><b>One class because the policy used to live in four hand-copied methods</b> - a
 * {@code correctAlignmentFromSighting} and a {@code keepWhatTheSightingProved} in each of
 * {@code MapsActivity} and {@code DeviceInfoActivity} - and a change to how sightings are
 * persisted already had to be applied to all four in lockstep once. Missing one would have
 * silently diverged alignment self-correction between the map and the device screen.
 *
 * <p><b>Persists only - deliberately no reread of the screen's model and no watch restart.</b>
 * A first attempt at that reset every card's already-computed geocoding on the map on every
 * correction, with nothing to refill it. The running session keeps matching against the
 * alignment it started with until the next load or periodic fetch picks the correction up;
 * a narrower per-beacon patch is still open.
 *
 * <p>Failure is logged and swallowed: a sighting that cannot be persisted costs the next scan
 * a wider search, nothing else, and it must never turn a successful ring into an error.
 */
public final class AccessorySightingPersister {
    private static final String TAG = AccessorySightingPersister.class.getSimpleName();

    private final BeaconRepository beaconRepo;

    public AccessorySightingPersister(final BeaconRepository beaconRepo) {
        this.beaconRepo = beaconRepo;
    }

    /**
     * A passive sighting from a {@code NearbyTagWatcher} - shaped to be used directly as its
     * {@code SightingListener}.
     *
     * <p>Two writes from the one advertisement, and they answer different questions. The address
     * says which key the tag is broadcasting, which corrects alignment; the status byte says what
     * its battery was, which is worth keeping long after the tag has gone quiet, because for a
     * user with no Apple device nothing else will ever report it - see
     * {@code BeaconRepository#storeLastSighting}.
     */
    public void onSighting(final NearbyTagSighting sighting, final String mac) {
        this.maybeCorrectAlignment(sighting, mac);
        this.persistLastSighting(sighting);
    }

    /**
     * How often one tag's alignment is worth re-deriving.
     *
     * <p><b>The keys only move every fifteen minutes, so correcting faster than that buys
     * nothing</b> - the second call within one rotation re-derives the same answer and writes
     * nothing. It is also the most expensive thing on this path by a wide margin: the candidate
     * window spans 48 hours, which is around 1150 key derivations, measured at 1.15s on desktop
     * and several times that under Chaquopy.
     *
     * <p>Running it on the sighting callback's own once-a-minute cadence put the app at 135% CPU
     * with two tags in range, continuously, and Android eventually killed it for not answering
     * input. Battery and position stay on the faster cadence: they cost a row each.
     */
    private static final long ALIGNMENT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15);

    /** When each tag's alignment was last re-derived. Written from the Rx io scheduler. */
    private final Map<String, Long> lastAlignmentMs = new ConcurrentHashMap<>();

    private void maybeCorrectAlignment(final NearbyTagSighting sighting, final String mac) {
        final Long last = this.lastAlignmentMs.get(sighting.getBeaconId());
        if (last != null && sighting.getSeenAtMs() - last < ALIGNMENT_INTERVAL_MS) {
            return;
        }
        this.lastAlignmentMs.put(sighting.getBeaconId(), sighting.getSeenAtMs());

        this.persist(sighting.getBeaconId(), mac, sighting.getSeenAtMs(),
                sighting.getKeyIndex());
    }

    /**
     * A sighting proven by a ring attempt: the scan matched, whatever the GATT exchange did
     * afterwards. Ignores progress updates and outcomes where nothing was found.
     */
    void keepWhatTheSightingProved(final String beaconId, final BleSoundTriggerUpdate update) {
        if (update.getPhase() != BleSoundTriggerPhase.DONE
                || update.getResult().getMatchedMac() == null) {
            return;
        }
        // No hint from the ring path: BleSoundTriggerResult carries the address only, on
        // purpose, and this runs once per button press rather than on a scan cadence.
        this.persist(beaconId, update.getResult().getMatchedMac(), System.currentTimeMillis(),
                null);
    }

    private void persist(final String beaconId, final String mac, final long seenAtMs,
                         final Integer hintIndex) {
        this.beaconRepo.recordAccessorySighting(beaconId, mac, seenAtMs, hintIndex)
                .subscribe(() -> { }, error -> Log.w(TAG,
                        "Failed to persist a sighting for beaconId=" + beaconId, error));
    }

    private void persistLastSighting(final NearbyTagSighting sighting) {
        this.beaconRepo.storeLastSighting(
                        sighting.getBeaconId(),
                        sighting.getBatteryLevel(),
                        sighting.getStatusByte(),
                        sighting.getSeenAtMs())
                .subscribe(() -> { }, error -> Log.w(TAG,
                        "Failed to persist a sighting reading for beaconId="
                                + sighting.getBeaconId(), error));
    }
}
