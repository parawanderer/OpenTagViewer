package dev.wander.android.opentagviewer;

import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.ble.BleSoundTriggerPhase;
import dev.wander.android.opentagviewer.ble.BleSoundTriggerUpdate;
import dev.wander.android.opentagviewer.ble.NearbyTagSighting;
import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.db.repo.BeaconRepository;
import dev.wander.android.opentagviewer.util.android.PhoneLocation;

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

    /**
     * Where the phone is, or null for a caller that records position some other way.
     *
     * <p><b>The screens pass one, {@code NearbyScanService} passes null.</b> Not an oversight:
     * the service already writes a position on the two edges it cares about, arriving and going
     * quiet, precisely so that it is not reading a location on every advertisement while nobody
     * is looking. A screen is the opposite situation - somebody has the app open and is watching
     * a tag be found - and the fix is cheap there because the app is in the foreground, which is
     * the only state its location permission covers anyway.
     *
     * <p>Without this the map kept showing the last thing Apple's network said, while the same
     * screen was reporting the tag as audible right now. Two answers to "where is it", and the
     * worse one was the one being drawn.
     */
    @Nullable
    private final PhoneLocation phoneLocation;

    /**
     * Told when a position was actually written, so a screen can show it without waiting.
     *
     * <p><b>Because a row nobody redraws is a row nobody sees.</b> The map draws from what the
     * last network fetch handed it, so a position written between fetches sat in the database
     * being correct and invisible, and the screen went on showing Apple's older answer for the
     * same tag. Fired only for a write that happened - a sighting dropped by the 25 metre rule
     * changes nothing on screen and is not worth a redraw.
     *
     * <p>Called on the Rx io thread. A listener that touches views has to get itself onto the
     * main thread.
     */
    public interface LocalPositionListener {
        void onWritten(String beaconId, BeaconLocationReport report);
    }

    @Nullable
    private final LocalPositionListener localPositionListener;

    public AccessorySightingPersister(final BeaconRepository beaconRepo) {
        this(beaconRepo, null, null);
    }

    public AccessorySightingPersister(final BeaconRepository beaconRepo,
                                      @Nullable final PhoneLocation phoneLocation) {
        this(beaconRepo, phoneLocation, null);
    }

    public AccessorySightingPersister(final BeaconRepository beaconRepo,
                                      @Nullable final PhoneLocation phoneLocation,
                                      @Nullable final LocalPositionListener localPositionListener) {
        this.beaconRepo = beaconRepo;
        this.phoneLocation = phoneLocation;
        this.localPositionListener = localPositionListener;
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
        this.maybeRecordWhereItWasHeard(sighting);
    }

    /**
     * Writes where this phone was when it heard the tag, if that is worth keeping.
     *
     * <p><b>Unthrottled here on purpose.</b> Two things already limit it: the fix comes from a
     * cache that only asks the platform once a minute, and
     * {@code BeaconRepository#recordLocalSighting} drops anything that has not moved 25 metres or
     * waited a quarter of an hour. Adding a third rule here would only make the real one harder
     * to find.
     *
     * <p>Silent when there is no fix. A phone indoors with no recent location has nothing to say
     * about where the tag is, and a report at a guessed position is worse than no report.
     */
    private void maybeRecordWhereItWasHeard(final NearbyTagSighting sighting) {
        if (this.phoneLocation == null) {
            return;
        }

        final PhoneLocation.Fix fix = this.phoneLocation.lastKnown();
        if (fix == null) {
            return;
        }

        this.beaconRepo.recordLocalSighting(
                        sighting.getBeaconId(), fix.getLatitude(), fix.getLongitude(),
                        Math.round(fix.getAccuracyMetres()), sighting.getStatusByte(),
                        sighting.getSeenAtMs())
                .subscribe(written -> {
                    if (written.isPresent() && this.localPositionListener != null) {
                        this.localPositionListener.onWritten(
                                sighting.getBeaconId(), written.get());
                    }
                }, error -> Log.w(TAG,
                        "Could not record where beaconId=" + sighting.getBeaconId()
                                + " was heard", error));
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
