package dev.wander.android.opentagviewer;

import android.util.Log;

import dev.wander.android.opentagviewer.ble.BleSoundTriggerPhase;
import dev.wander.android.opentagviewer.ble.BleSoundTriggerUpdate;
import dev.wander.android.opentagviewer.ble.NearbyTagSighting;
import dev.wander.android.opentagviewer.db.repo.BeaconRepository;
import dev.wander.android.opentagviewer.util.android.PhoneLocation;

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

    /**
     * Where the phone was when a tag was heard, or null throughout when the caller has no
     * business recording positions.
     */
    private final PhoneLocation phoneLocation;

    AccessorySightingPersister(
            final BeaconRepository beaconRepo, final PhoneLocation phoneLocation) {
        this.beaconRepo = beaconRepo;
        this.phoneLocation = phoneLocation;
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
        this.persistPosition(sighting);
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

    /**
     * Records where the phone was as the tag's position, when there is a fix to record.
     *
     * <p>Hearing the tag puts it within Bluetooth range of here, which is a far tighter claim
     * than a network report carries - see {@code BeaconRepository#recordLocalSighting}. Not
     * every sighting earns a row; the repository decides, because sightings arrive far faster
     * than positions are worth keeping.
     *
     * <p>No fix means no row, silently. Location may be off, the permission may have been
     * declined, or the phone may not have one yet, and none of those is a failure of the
     * sighting.
     */
    private void persistPosition(final NearbyTagSighting sighting) {
        final PhoneLocation.Fix fix = this.phoneLocation.lastKnown();
        if (fix == null) {
            return;
        }

        this.beaconRepo.recordLocalSighting(
                        sighting.getBeaconId(),
                        fix.getLatitude(),
                        fix.getLongitude(),
                        fix.getAccuracyMetres(),
                        sighting.getStatusByte(),
                        sighting.getSeenAtMs())
                .subscribe(written -> { }, error -> Log.w(TAG,
                        "Failed to persist a position for beaconId=" + sighting.getBeaconId(),
                        error));
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
