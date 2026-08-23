package dev.wander.android.opentagviewer.ble;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
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
 * (it is blocking Python), while {@link #beaconIdFor} runs on the Bluetooth stack's scan
 * callback thread, once per advertisement of anything. Hence the volatile reference that is
 * swapped whole rather than a map mutated in place: a reader sees either the old index or the
 * new one, never a half-built or momentarily empty in-between - a race here would not crash,
 * it would drop matches, which presents as "the tag is never nearby".
 */
public final class NearbyTagIndex {

    /**
     * How long a built index is trusted.
     *
     * <p>Under the 15 minute rollover interval on purpose. Rebuilding slightly too often costs
     * one Python call per tag; rebuilding too late costs sightings, and a missed sighting is
     * indistinguishable from an absent tag.
     */
    static final long MAX_AGE_MS = TimeUnit.MINUTES.toMillis(10);

    private volatile Map<String, String> beaconIdByMac = Map.of();
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
     * @param accessoryJsonByBeaconId the persisted accessory JSON per beacon. An entry whose
     *                                JSON is null or unreadable is skipped rather than failing
     *                                the rebuild: a tag that has not been backfilled yet should
     *                                cost only its own sightings, not everyone else's.
     */
    public void rebuild(
            final Map<String, String> accessoryJsonByBeaconId,
            final AccessoryMacResolver resolver,
            final long nowMs) {
        final Map<String, String> rebuilt = new HashMap<>();

        for (final Map.Entry<String, String> entry : accessoryJsonByBeaconId.entrySet()) {
            final List<String> macs = resolver.currentMacAddresses(entry.getValue());
            for (final String mac : macs) {
                if (mac != null) {
                    // Upper-cased on the way in so lookups need no normalisation per scan
                    // result, which is the hot path. Android reports uppercase and FindMy.py
                    // produces uppercase, but neither promises it forever.
                    rebuilt.put(mac.toUpperCase(Locale.ROOT), entry.getKey());
                }
            }
        }

        // Swapped whole, not mutated in place - see the class doc on the reader thread.
        this.beaconIdByMac = rebuilt;
        this.builtAtMs = nowMs;
    }

    /** The beacon this address belongs to, or null if it is not one of ours. */
    @Nullable
    public String beaconIdFor(@Nullable final String scannedAddress) {
        if (scannedAddress == null) {
            return null;
        }
        return this.beaconIdByMac.get(scannedAddress.toUpperCase(Locale.ROOT));
    }

    /** How many addresses are currently being watched for, across all tags. For logging. */
    public int size() {
        return this.beaconIdByMac.size();
    }
}
