package dev.wander.android.opentagviewer.ble;

import android.util.Log;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.python.AccessoryMacResolver;

/**
 * Which beacon a scanned BLE address belongs to, for every tag at once.
 *
 * <p><b>Why this exists rather than resolving per scan result.</b>
 * {@code AccessoryMacResolver.currentMacAddresses} starts a Python interpreter and runs an EC
 * derivation. A scan in an ordinary flat produces tens of results per second, and a screen left
 * open produces them for as long as it is open, so resolving per result would be one interpreter
 * start per advertisement of anything, ours or not. Resolving once for every tag and matching
 * against a map turns that into a hash lookup.
 *
 * <p><b>Why it expires.</b> The addresses are rolling keys: an accessory moves to the next one
 * roughly every 15 minutes, and a fetch that updates a tag's alignment changes which addresses
 * are predicted at all. A map built once and kept would quietly stop matching, which presents as
 * "the tag is never nearby" rather than as anything failing.
 *
 * <p>No Android and no Bluetooth in here, so the expiry rule and the matching are covered by a
 * JVM test; the clock is a parameter for the same reason.
 *
 * <p><b>Written and read on different threads.</b> {@link #rebuild} runs on an Rx io thread
 * (it is blocking Python), while {@link #matchFor} runs on the Bluetooth stack's scan
 * callback thread, once per advertisement of anything. Hence the volatile reference that is
 * swapped whole rather than a map mutated in place: a reader sees either the old index or the
 * new one, never a half-built or momentarily empty in-between - a race here would not crash,
 * it would drop matches, which presents as "the tag is never nearby".
 */
public final class NearbyTagIndex {
    private static final String TAG = NearbyTagIndex.class.getSimpleName();


    /**
     * How long a built index is trusted.
     *
     * <p>Under the 15 minute rollover interval on purpose. Rebuilding slightly too often costs
     * one Python call per tag; rebuilding too late costs sightings, and a missed sighting is
     * indistinguishable from an absent tag.
     */
    static final long MAX_AGE_MS = TimeUnit.MINUTES.toMillis(10);

    private volatile Map<String, Match> matchByMac = Map.of();

    /**
     * Which tag an address belongs to, and the index its key was derived at.
     *
     * <p><b>The index is carried, not acted on.</b> Only Python can tell a primary key from a
     * secondary one, and that is what decides whether an index may be trusted - see
     * {@code AccessoryMacResolver#recordSeen}. Passing it on as a hint lets the correction check
     * one index instead of re-deriving a 48-hour window, which is the difference between three
     * key derivations and around 1150.
     */
    public static final class Match {
        private final String beaconId;
        private final int keyIndex;

        Match(final String beaconId, final int keyIndex) {
            this.beaconId = beaconId;
            this.keyIndex = keyIndex;
        }

        public String getBeaconId() {
            return this.beaconId;
        }

        public int getKeyIndex() {
            return this.keyIndex;
        }
    }
    private volatile long builtAtMs = Long.MIN_VALUE;

    /** True when this has never been built, or was built long enough ago to be doubted. */
    public boolean isStale(final long nowMs) {
        return this.builtAtMs == Long.MIN_VALUE || nowMs - this.builtAtMs >= MAX_AGE_MS;
    }

    /**
     * Resolves every tag's current candidate addresses and replaces the index with them.
     *
     * <p>Blocking, once per tag. Call it off the main thread.
     *
     * @param accessoryJsonByBeaconId the persisted accessory JSON per beacon. An entry the
     *                                resolver cannot answer for - unreadable JSON, or a
     *                                candidate window too wide to be worth deriving - is
     *                                skipped rather than failing the rebuild: such a tag should
     *                                cost only its own sightings, not everyone else's.
     */
    public void rebuild(
            final Map<String, String> accessoryJsonByBeaconId,
            final AccessoryMacResolver resolver,
            final long nowMs) {
        final Map<String, Match> rebuilt = new HashMap<>();

        for (final Map.Entry<String, String> entry : accessoryJsonByBeaconId.entrySet()) {
            // Only the address is wanted here; the key index each maps to is not this class's
            // business - see AccessoryMacResolver#recordSeen on why only Python may act on it.
            final Map<String, Integer> candidates = resolver.currentMacAddresses(entry.getValue());

            // **Null is a documented answer, not a broken one, and it must not stop the loop.**
            // The interface permits it for an accessory the resolver cannot read, and for one
            // whose candidate window is too wide to derive - which is what an owner's own Apple
            // device looks like, since a phone has no rolling-key alignment.
            //
            // Latent rather than observed: the Chaquopy implementation maps Python's None to an
            // empty map, so no build has actually thrown here. A different implementation, or a
            // test double, may return null as the signature allows - and then one entry would
            // cost every other entry its sightings, which is what the parameter note forbids.
            if (candidates == null) {
                Log.d(TAG, "No candidate addresses for beaconId=" + entry.getKey()
                        + "; leaving it out of the index rather than dropping the rest");
                continue;
            }

            for (final Map.Entry<String, Integer> candidate : candidates.entrySet()) {
                if (candidate.getKey() != null && candidate.getValue() != null) {
                    // Upper-cased on the way in so lookups need no normalisation per scan
                    // result, which is the hot path. Android reports uppercase and FindMy.py
                    // produces uppercase, but neither promises it forever.
                    rebuilt.put(candidate.getKey().toUpperCase(Locale.ROOT),
                            new Match(entry.getKey(), candidate.getValue()));
                }
            }
        }

        // Swapped whole, not mutated in place - see the class doc on the reader thread.
        this.matchByMac = rebuilt;
        this.builtAtMs = nowMs;
    }

    /** The tag this address belongs to and the index it came from, or null if it is not ours. */
    @Nullable
    public Match matchFor(@Nullable final String scannedAddress) {
        if (scannedAddress == null) {
            return null;
        }
        return this.matchByMac.get(scannedAddress.toUpperCase(Locale.ROOT));
    }

    /** How many addresses are currently being watched for, across all tags. For logging. */
    public int size() {
        return this.matchByMac.size();
    }
}
