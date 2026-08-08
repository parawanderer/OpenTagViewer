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
}
