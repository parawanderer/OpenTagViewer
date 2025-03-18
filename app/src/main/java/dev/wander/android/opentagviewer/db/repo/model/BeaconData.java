package dev.wander.android.opentagviewer.db.repo.model;

import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BeaconData {
    private final String beaconId;
    private final OwnedBeacon ownedBeaconInfo;
    private final BeaconNamingRecord beaconNamingRecord;
}
