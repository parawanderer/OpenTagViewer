package dev.wander.android.opentagviewer.db.repo.model;

import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.db.room.entity.UserBeaconOptions;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BeaconData {
    private final String beaconId;
    private final OwnedBeacon ownedBeaconInfo;
    /**
     * What an Apple device wrote about this tag - its name and emoji.
     *
     * <p>Null for a tag that was never in an Apple account, because nothing ever named it. The
     * name for one of those comes out of the accessory JSON instead; see
     * {@link dev.wander.android.opentagviewer.util.parse.CustomAccessoryParser}.
     */
    private final BeaconNamingRecord beaconNamingRecord;
    /**
     * Optional, only if configured
     */
    private final UserBeaconOptions userBeaconOptions;
}
