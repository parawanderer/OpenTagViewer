package dev.wander.android.opentagviewer.util.rx;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.db.util.BeaconCombinerUtil;

/**
 * The location history the map is currently holding, keyed by beacon.
 * <br>
 * Free of Android types so the rules can be tested. Two of them have cost real debugging time:
 * <ul>
 *   <li><b>A beacon with no location cannot be drawn.</b> It gets no marker and no card, so it
 *       vanishes from the UI rather than appearing as an error. When a bug cancelled two of
 *       three accessory fetches, this rule is what turned it into "I only have 1 card".</li>
 *   <li><b>The newest report is the last element.</b> Five separate callers read
 *       {@code locations.get(size - 1)}, which is only correct because the merge sorts
 *       ascending by timestamp. Nothing enforced that, and a re-sort would have broken every
 *       one of them at once - showing a stale position, not an empty one.</li>
 * </ul>
 * Backed by a concurrent map: fetch results land on RxJava's IO and computation schedulers
 * while the UI reads on the main thread.
 * <br>
 * Beacon metadata deliberately stays in {@code MapsActivity}: it carries geocoding results and
 * a map position, both Android types, and dragging those in here would make this untestable
 * for no gain.
 */
public final class BeaconLocationHistory {

    private final Map<String, List<BeaconLocationReport>> byBeaconId = new ConcurrentHashMap<>();

    /**
     * Merges newly fetched reports into the history for one beacon, de-duplicating and sorting
     * oldest-first.
     * <br>
     * A refresh asks for a window that overlaps what is already held - on purpose, so nothing
     * is missed at the boundary - so the same report arrives repeatedly and has to be
     * collapsed by content rather than appended.
     *
     * @return the size of the merged history, for logging
     */
    public int merge(final String beaconId, final List<BeaconLocationReport> reports) {
        final List<BeaconLocationReport> existing =
                this.byBeaconId.getOrDefault(beaconId, Collections.emptyList());

        // Sorted even on the first fetch. Storing Apple's response as-is - which is what this
        // did - left the ordering up to whatever the server happened to return, and every
        // caller reads the last element as the newest. An unsorted first response therefore
        // put a stale position on the map, silently and only for freshly imported tags.
        final List<BeaconLocationReport> merged =
                BeaconCombinerUtil.combineAndSort(beaconId, existing, reports);
        this.byBeaconId.put(beaconId, merged);
        return merged.size();
    }

    /**
     * The most recent report for a beacon, or empty if it has none.
     * <br>
     * Empty is the ordinary case for a tag Apple has not answered for yet, not a failure. It
     * simply cannot be placed on a map.
     */
    public Optional<BeaconLocationReport> lastLocationOf(final String beaconId) {
        final List<BeaconLocationReport> reports = this.byBeaconId.get(beaconId);
        if (reports == null || reports.isEmpty()) {
            return Optional.empty();
        }
        // Last, not first: merge() sorts ascending by timestamp.
        return Optional.of(reports.get(reports.size() - 1));
    }

    /** Whether this beacon can be drawn at all: no location means no marker and no card. */
    public boolean isDrawable(final String beaconId) {
        return this.lastLocationOf(beaconId).isPresent();
    }

    public List<BeaconLocationReport> of(final String beaconId) {
        return this.byBeaconId.getOrDefault(beaconId, Collections.emptyList());
    }

    public int sizeOf(final String beaconId) {
        return this.of(beaconId).size();
    }

    public boolean knows(final String beaconId) {
        return this.byBeaconId.containsKey(beaconId);
    }
}
