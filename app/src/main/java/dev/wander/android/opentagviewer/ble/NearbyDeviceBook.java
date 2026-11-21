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
     * How long a prefix may go unheard before its row counts as offline.
     *
     * <p>Deliberately shorter than any rotation: a vanished prefix is almost always out of
     * range or quieted, and holding it live for 15 minutes would list departed tags as
     * present. Silence is the only signal disappearance has, so staleness is judged by this
     * threshold rather than by anything the scan reports.
     */
    public static final long GONE_AFTER_MS = 30_000;

    /**
     * How long an unheard row stays on screen before the book forgets it.
     *
     * <p>Boundless retention would fill the list with every tag ever heard on a long day,
     * so the book only bridges the gap a user actually notices. Ten minutes covers "walked
     * to the car and came back" without immortalising a commute.
     */
    public static final long RETAIN_GONE_MS = 10 * 60_000;

    private final Map<String, NearbyDeviceSighting> latestByPrefix = new LinkedHashMap<>();

    /** Records a sighting under its prefix; the newest one wins the row. */
    public void hear(final NearbyDeviceSighting sighting) {
        final NearbyDeviceSighting previous = this.latestByPrefix.get(sighting.getKeyPrefix());
        if (previous == null || sighting.getSeenAtMs() >= previous.getSeenAtMs()) {
            this.latestByPrefix.put(sighting.getKeyPrefix(), sighting);
        }
    }

    /**
     * The current rows: live devices by signal strength first, then offline devices by
     * most-recently-heard.
     *
     * <p>Prunes prefixes unheard for {@link #RETAIN_GONE_MS} first; rows past
     * {@link #GONE_AFTER_MS} are kept and reported via {@link #isGone} so the list can draw
     * them dimmed instead of dropping them - a tag that walked out of range is still
     * interesting for a while.
     */
    public List<NearbyDeviceSighting> snapshot(final long nowMs) {
        this.latestByPrefix.values().removeIf(s -> nowMs - s.getSeenAtMs() > RETAIN_GONE_MS);

        final List<NearbyDeviceSighting> live = new ArrayList<>();
        final List<NearbyDeviceSighting> gone = new ArrayList<>();
        for (final NearbyDeviceSighting sighting : this.latestByPrefix.values()) {
            (isGone(sighting, nowMs) ? gone : live).add(sighting);
        }
        live.sort(Comparator.comparingInt(NearbyDeviceSighting::getRssi).reversed());
        gone.sort(Comparator.comparingLong(NearbyDeviceSighting::getSeenAtMs).reversed());
        live.addAll(gone);
        return live;
    }

    /** Whether a sighting is stale enough to draw as offline rather than live. */
    public static boolean isGone(final NearbyDeviceSighting sighting, final long nowMs) {
        return nowMs - sighting.getSeenAtMs() > GONE_AFTER_MS;
    }

    /** The newest sighting for one prefix, or null. */
    @Nullable
    public NearbyDeviceSighting latest(final String keyPrefix) {
        return this.latestByPrefix.get(keyPrefix);
    }
}
