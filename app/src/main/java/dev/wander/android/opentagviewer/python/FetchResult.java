package dev.wander.android.opentagviewer.python;

import java.util.List;
import java.util.Map;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Wraps the result of a {@link PythonAppleService} fetch call. With FindMy 0.9.x
 * the {@code FindMyAccessory} object is stateful — its rolling-key alignment is
 * updated each fetch and must be persisted back to the DB to keep key drift
 * from re-emerging. So a fetch returns both the location reports per beacon AND
 * the freshly-serialized accessory JSON per beacon, keyed by beaconId.
 */
@AllArgsConstructor
@Getter
public class FetchResult {
    private final Map<String, List<BeaconLocationReport>> reports;
    private final Map<String, String> updatedAccessoryJson;

    /**
     * The accessories whose search covered months of history and found nothing at all.
     *
     * <p><b>Not the same as "no reports".</b> A tag with no key alignment record searches from
     * its pairing date, so a young one searches a small range and an empty answer means very
     * little - it may not have been near an iPhone this week. Only a wide search that stayed
     * wide says the tag has been silent for months, and Python is where the width is known.
     */
    private final java.util.Set<String> exhaustedWideSearch;

    /**
     * The accessories whose search was an expensive one - a wide key window.
     *
     * <p><b>What separates "nothing new" from "nothing at all".</b> An aligned tag costs a
     * request or two and an empty answer means only that it has not moved since the window
     * began, which is the ordinary state of a tag that reported an hour ago. Counting that as a
     * failure made healthy tags accrue strikes and start being asked less often, which is the
     * opposite of what the backoff is for.
     */
    private final java.util.Set<String> wideSearch;
}
