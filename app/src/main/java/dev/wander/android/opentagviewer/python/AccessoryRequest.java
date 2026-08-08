package dev.wander.android.opentagviewer.python;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Java→Python carrier for a single beacon's fetch input. Chaquopy exposes the
 * getter methods to Python as attribute access (e.g. {@code req.getBeaconId()})
 * so the Python side can read fields without having to import any Java types.
 *
 * <p>Replaces the older {@code Pair<String, String>} (beaconId → plistXml) shape
 * that the 0.7.x bridge used. With FindMy 0.9.x we also need the persisted
 * accessory JSON (which carries the rolling-key alignment state — the issue #30
 * fix) so it can be passed in and updated state can be returned.
 */
@AllArgsConstructor
@Getter
public class AccessoryRequest {
    private final String beaconId;
    private final String accessoryJson;
}
