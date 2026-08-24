package dev.wander.android.opentagviewer;

import android.util.Log;

import dev.wander.android.opentagviewer.ble.BleSoundTriggerPhase;
import dev.wander.android.opentagviewer.ble.BleSoundTriggerUpdate;
import dev.wander.android.opentagviewer.ble.NearbyTagSighting;
import dev.wander.android.opentagviewer.db.repo.BeaconRepository;

/**
 * The one place a Bluetooth sighting is written down, for both screens and both kinds of
 * sighting: the alignment it proves, and the battery level it reported.
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
final class AccessorySightingPersister {
    private static final String TAG = AccessorySightingPersister.class.getSimpleName();

    private final BeaconRepository beaconRepo;

    AccessorySightingPersister(final BeaconRepository beaconRepo) {
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
    void onSighting(final NearbyTagSighting sighting, final String mac) {
        this.persist(sighting.getBeaconId(), mac, sighting.getSeenAtMs());
        this.persistLastSighting(sighting);
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
        this.persist(beaconId, update.getResult().getMatchedMac(), System.currentTimeMillis());
    }

    private void persist(final String beaconId, final String mac, final long seenAtMs) {
        this.beaconRepo.recordAccessorySighting(beaconId, mac, seenAtMs)
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
