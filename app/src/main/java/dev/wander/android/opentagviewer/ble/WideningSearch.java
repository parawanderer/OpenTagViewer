package dev.wander.android.opentagviewer.ble;

import android.util.Log;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.python.AccessoryMacResolver;

/**
 * Looks further back for a tag nobody has heard, a little at a time, without being asked.
 *
 * <p><b>The problem it exists for.</b> The addresses worth scanning for are worked out from the
 * tag's stored key alignment, extrapolated forward at one index every fifteen minutes. That is
 * right for a tag that has been running, which is the ordinary case even after months out of
 * contact, because the index follows the tag's own clock and not the network. It is wrong for a
 * tag whose true index has drifted away from the extrapolation: one that spent time without
 * power, or whose stored alignment was pushed too high. Such a tag is then searched for at
 * addresses it will never use, and from the outside is indistinguishable from a tag that is
 * simply gone. That is the failure this closes.
 *
 * <p><b>Why widening rather than a button.</b> A manual "search harder" action would put the
 * question to the person least able to answer it: they cannot tell a tag that is missing from
 * one that is out of step, which is exactly what they came to the app to find out. So the search
 * widens on its own, and the only thing the user ever sees is that the tag turns up.
 *
 * <p><b>Why a little at a time.</b> Deriving costs about two to three seconds per thousand
 * indices on an idle phone, and was measured at sixty to a hundred while the app was starting up
 * and competing with itself. Covering a hundred days in one go is therefore either half a minute
 * or several, depending entirely on when it is attempted. In chunks it is a fixed, small cost
 * that can be spent when there is room for it, and the whole range is covered within the hour
 * either way.
 *
 * <p><b>Progress needs no state of its own.</b> {@link DerivedAddressStore} records the range it
 * holds, so the bottom of that range is exactly how far the search has got. Restarting the app,
 * or the service being killed, costs nothing and resumes where it left off.
 *
 * <p>No Android in here, so the rule is covered by a JVM test.
 */
public final class WideningSearch {
    private static final String TAG = WideningSearch.class.getSimpleName();

    /**
     * How many indices one round derives.
     *
     * <p>Small enough that a single round is affordable even on a device where derivation is
     * running an order of magnitude slower than measured: at the worst rate seen, a hundred
     * seconds per thousand indices, this is still under a minute of work that nothing is
     * waiting on.
     */
    static final int CHUNK_INDICES = 500;

    /**
     * How far back the search is willing to go, as indices below the top of the current window.
     *
     * <p>A hundred days at four indices an hour. Past that the balance tips: the derivation is
     * still cheap in chunks, but a tag that has been out of step for longer than that is more
     * likely gone than out of step, and the addresses are worth less than the space they take.
     */
    static final int TARGET_INDICES = 9_600;

    /**
     * How recently a tag must have been heard to be left alone.
     *
     * <p>Deliberately long. Widening a tag that is merely quiet for a minute would spend the
     * derivation on the tags least in need of it, and a tag in the same room is heard many times
     * inside this window.
     */
    static final long HEARD_RECENTLY_MS = TimeUnit.MINUTES.toMillis(10);

    /**
     * The least time between rounds.
     *
     * <p>The point is that this never competes with anything. A round a minute covers a hundred
     * days in about twenty minutes, which is far quicker than the situation it is for.
     */
    static final long BETWEEN_ROUNDS_MS = TimeUnit.MINUTES.toMillis(1);

    /**
     * How long after the watch starts before the first round.
     *
     * <p>The measured worst case for derivation was during app startup - sixty to a hundred
     * seconds per thousand indices, against two to three when idle - so the one rule this must
     * follow is to stay out of that window. Everything else it does is cheap; doing it at the
     * wrong moment is not.
     */
    static final long WARM_UP_MS = TimeUnit.MINUTES.toMillis(2);

    private final AccessoryMacResolver resolver;
    private final DerivedAddressStore store;

    private long startedAtMs = Long.MIN_VALUE;
    private long lastRoundMs = Long.MIN_VALUE;

    public WideningSearch(final AccessoryMacResolver resolver, final DerivedAddressStore store) {
        this.resolver = resolver;
        this.store = store;
    }

    /** Notes when the watch began, which is what the warm-up is measured from. */
    public void started(final long nowMs) {
        this.startedAtMs = nowMs;
    }

    /** Whether a round is due: past the warm-up, and not too soon after the last one. */
    public boolean isDue(final long nowMs) {
        if (this.startedAtMs == Long.MIN_VALUE || nowMs - this.startedAtMs < WARM_UP_MS) {
            return false;
        }
        return this.lastRoundMs == Long.MIN_VALUE || nowMs - this.lastRoundMs >= BETWEEN_ROUNDS_MS;
    }

    /**
     * Derives one chunk further back for one tag that has not been heard, if any needs it.
     *
     * <p>One tag per round rather than all of them, so the cost of a round does not depend on
     * how many tags somebody owns.
     *
     * @param lastHeardMsByBeaconId when each tag was last heard; absent means never.
     * @return the beacon whose range grew, or null if there was nothing to do.
     */
    @Nullable
    public String widenOne(
            final Map<String, String> accessoryJsonByBeaconId,
            final Map<String, Long> lastHeardMsByBeaconId,
            final long nowMs) {

        this.lastRoundMs = nowMs;

        for (final Map.Entry<String, String> entry : accessoryJsonByBeaconId.entrySet()) {
            final String beaconId = entry.getKey();

            final Long lastHeard = lastHeardMsByBeaconId.get(beaconId);
            if (lastHeard != null && nowMs - lastHeard < HEARD_RECENTLY_MS) {
                continue;
            }

            if (this.widen(beaconId, entry.getValue())) {
                return beaconId;
            }
        }

        return null;
    }

    /** True when this tag's stored range actually grew. */
    private boolean widen(final String beaconId, final String accessoryJson) {
        final DerivedAddressStore.Derived held = this.store.load(beaconId);
        if (held == null) {
            // Nothing derived yet at all. The ordinary index rebuild creates it, and widening
            // something that does not exist would race with that for no gain.
            return false;
        }

        final AccessoryMacResolver.IndexRange window = this.resolver.candidateWindow(accessoryJson);
        if (window == null) {
            return false;
        }

        final int floor = Math.max(0, window.getHi() - TARGET_INDICES);
        if (held.getLo() <= floor) {
            // As far back as this is willing to look. Not a failure: a tag still unheard here has
            // been out of step for longer than the addresses are worth keeping for.
            return false;
        }

        final int to = held.getLo() - 1;
        final int from = Math.max(floor, held.getLo() - CHUNK_INDICES);

        final Map<String, Integer> derived = this.resolver.addressesBetween(
                accessoryJson, from, to);
        if (derived == null || derived.isEmpty()) {
            // An empty answer for a non-empty range means the derivation failed. Advancing the
            // stored range past it anyway would record indices as covered that were never
            // derived, and nothing would ever go back for them.
            Log.d(TAG, "Nothing derived for beaconId=" + beaconId + " over " + from + ".." + to
                    + "; leaving the stored range where it is");
            return false;
        }

        final Map<String, Integer> widened = new HashMap<>(held.getAddresses());
        widened.putAll(derived);

        this.store.save(beaconId, from, held.getHi(), widened);

        Log.i(TAG, "Widened the search for beaconId=" + beaconId + " down to " + from
                + " (" + widened.size() + " address(es), floor " + floor + ")");
        return true;
    }

    /** The tags worth widening for right now, for a caller that wants to log or test the choice. */
    static Set<String> notHeardRecently(
            final Set<String> beaconIds,
            final Map<String, Long> lastHeardMsByBeaconId,
            final long nowMs) {

        final Set<String> missing = new java.util.HashSet<>();
        for (final String beaconId : beaconIds) {
            final Long lastHeard = lastHeardMsByBeaconId.get(beaconId);
            if (lastHeard == null || nowMs - lastHeard >= HEARD_RECENTLY_MS) {
                missing.add(beaconId);
            }
        }
        return missing;
    }
}
