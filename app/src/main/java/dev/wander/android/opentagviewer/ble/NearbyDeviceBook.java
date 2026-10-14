package dev.wander.android.opentagviewer.ble;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The running table behind the nearby-devices list: the newest sighting per key prefix.
 *
 * <p>A Find My tag in range advertises every one to three seconds. Showing each one would be a
 * blur of duplicate rows, so the list wants one row per identity - the key prefix - holding the
 * most recent sighting, which carries the freshest RSSI, state and timestamp for display. Older
 * sightings are not kept: branch 3's rotation heuristic wants a log with history, but this book
 * is for what is on screen *now*, and a screen that is open for minutes remembers a lot.
 *
 * <p>Pure Java - no Android, no clock: {@link NearbyDeviceSighting} carries its own timestamp,
 * and the caller supplies "now" when it asks how stale the book is. That keeps the whole
 * aggregation rule under a JVM test.
 */
public final class NearbyDeviceBook {

    /**
     * How long a prefix may go unheard before its row counts as gone.
     *
     * <p>Deliberately shorter than any rotation: a vanished prefix is almost always out of
     * range or quieted, and holding it for 15 minutes would list departed tags as present.
     * Rows that cross this are pruned by {@link #snapshot}, not by the scan - silence is the
     * only signal disappearance has.
     */
    public static final long GONE_AFTER_MS = 30_000;

    private final Map<String, NearbyDeviceSighting> latestByPrefix = new LinkedHashMap<>();

    /** Records a sighting under its prefix; the newest one wins the row. */
    public void hear(final NearbyDeviceSighting sighting) {
        final NearbyDeviceSighting previous = this.latestByPrefix.get(sighting.getKeyPrefix());
        if (previous == null || sighting.getSeenAtMs() >= previous.getSeenAtMs()) {
            this.latestByPrefix.put(sighting.getKeyPrefix(), sighting);
        }
    }

    /**
     * The current rows, strongest signal first.
     *
     * <p>Prunes prefixes unheard for {@link #GONE_AFTER_MS} first: a tag that left should fall
     * out of the list, not linger with a stale RSSI implying it is still here.
     */
    public List<NearbyDeviceSighting> snapshot(final long nowMs) {
        this.latestByPrefix.values().removeIf(s -> nowMs - s.getSeenAtMs() > GONE_AFTER_MS);

        final List<NearbyDeviceSighting> rows = new ArrayList<>(this.latestByPrefix.values());
        rows.sort(Comparator.comparingInt(NearbyDeviceSighting::getRssi).reversed());
        return rows;
    }

    /** The newest sighting for one prefix, or null. */
    @Nullable
    public NearbyDeviceSighting latest(final String keyPrefix) {
        return this.latestByPrefix.get(keyPrefix);
    }
}
