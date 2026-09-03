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
        /**
         * Where this address came from, or null when that is not known.
         *
         * <p>Null for an address read back from {@link DerivedAddressStore}: the index a
         * secondary key is reported at depends on where the deriving range began, so it is an
         * artefact of how the work was split rather than a fact worth keeping. It travels on as
         * a hint, and a missing hint simply costs Python one wide check.
         */
        @Nullable
        private final Integer keyIndex;

        Match(final String beaconId, @Nullable final Integer keyIndex) {
            this.beaconId = beaconId;
            this.keyIndex = keyIndex;
        }

        public String getBeaconId() {
            return this.beaconId;
        }

        @Nullable
        public Integer getKeyIndex() {
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
        this.rebuild(accessoryJsonByBeaconId, resolver, nowMs, null);
    }

    /**
     * As {@link #rebuild(Map, AccessoryMacResolver, long)}, keeping what it derives in {@code
     * store} and deriving only what is missing from it.
     *
     * @param store where derived addresses are kept across launches, or null to derive
     *              everything every time, which is what a test without a filesystem wants.
     */
    public void rebuild(
            final Map<String, String> accessoryJsonByBeaconId,
            final AccessoryMacResolver resolver,
            final long nowMs,
            @Nullable final DerivedAddressStore store) {
        final Map<String, Match> rebuilt = new HashMap<>();

        for (final Map.Entry<String, String> entry : accessoryJsonByBeaconId.entrySet()) {
            // Only the address is wanted here; the key index each maps to is not this class's
            // business - see AccessoryMacResolver#recordSeen on why only Python may act on it.
            final Map<String, Integer> candidates =
                    store == null
                            ? resolver.currentMacAddresses(entry.getValue())
                            : addressesFor(entry.getKey(), entry.getValue(), resolver, store);

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
                // A null value is an address whose index is not known, which is ordinary for one
                // recovered from the store. Only a null address is useless.
                if (candidate.getKey() != null) {
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

    /**
     * How wide a stored range is allowed to grow before it is started over.
     *
     * <p>The window creeps upward with the clock, about a hundred indices a day, so the union of
     * everything ever derived grows without limit for a tag that is kept for years. At this width
     * it is roughly a year of history and a few megabytes; past it, the oldest part is certainly
     * dead and is not worth carrying. Starting over costs one derivation of the current window.
     */
    static final int MAX_STORED_INDICES = 40_000;

    /**
     * The addresses for one tag, derived only where the stored copy does not already have them.
     *
     * <p><b>Extended rather than replaced, and a gap in between is derived rather than skipped.</b>
     * The stored range and the wanted window normally overlap, since the window moves by one
     * index every fifteen minutes. When they do not, the app has simply not been opened for a
     * few days, and deriving from the top of what is held up to the top of the window covers the
     * gap and the window together - so the result is contiguous and nothing is recorded as
     * covered that was never derived. Requiring them to touch, and starting over when they did
     * not, threw away a range that had been widened over hours because somebody left the app
     * closed for four days, which is exactly the case the widening exists for.
     */
    private static Map<String, Integer> addressesFor(
            final String beaconId,
            final String accessoryJson,
            final AccessoryMacResolver resolver,
            final DerivedAddressStore store) {

        final AccessoryMacResolver.IndexRange window = resolver.candidateWindow(accessoryJson);
        if (window == null || window.width() == 0) {
            // Unreadable, or an accessory with no rolling keys at all. Ask the way that has
            // always answered for those, and keep nothing.
            return resolver.currentMacAddresses(accessoryJson);
        }

        final DerivedAddressStore.Derived held = store.load(beaconId);

        if (held != null && held.covers(window.getLo(), window.getHi())) {
            // The whole point: nothing is derived at all, on the launch where deriving is most
            // expensive because everything else is starting up at the same time.
            Log.d(TAG, "Reusing " + held.getAddresses().size() + " stored address(es) for"
                    + " beaconId=" + beaconId + " covering " + held.getLo() + ".." + held.getHi());
            return held.getAddresses();
        }

        // Only the total width can rule out extending: everything else is a gap, and a gap is
        // derived along with the window rather than being a reason to discard what is held.
        final boolean extendable = held != null
                && Math.max(held.getHi(), window.getHi())
                        - Math.min(held.getLo(), window.getLo()) < MAX_STORED_INDICES;

        final Map<String, Integer> addresses =
                extendable ? new HashMap<>(held.getAddresses()) : new HashMap<>();

        final int haveLo = extendable ? held.getLo() : Integer.MAX_VALUE;
        final int haveHi = extendable ? held.getHi() : Integer.MIN_VALUE;

        if (!extendable) {
            addresses.putAll(resolver.addressesBetween(
                    accessoryJson, window.getLo(), window.getHi()));
        } else {
            if (window.getLo() < haveLo) {
                addresses.putAll(resolver.addressesBetween(
                        accessoryJson, window.getLo(), haveLo - 1));
            }
            if (window.getHi() > haveHi) {
                addresses.putAll(resolver.addressesBetween(
                        accessoryJson, haveHi + 1, window.getHi()));
            }
        }

        final int storedLo = extendable ? Math.min(haveLo, window.getLo()) : window.getLo();
        final int storedHi = extendable ? Math.max(haveHi, window.getHi()) : window.getHi();

        store.save(beaconId, storedLo, storedHi, addresses);
        return addresses;
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
