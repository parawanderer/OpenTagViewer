package dev.wander.android.airtagforall.db.repo.model;

import java.util.List;

import dev.wander.android.airtagforall.db.room.entity.BeaconNamingRecord;
import dev.wander.android.airtagforall.db.room.entity.Import;
import dev.wander.android.airtagforall.db.room.entity.OwnedBeacon;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public class ImportData {
    private final Import anImport;
    private final List<OwnedBeacon> ownedBeacons;
    private final List<BeaconNamingRecord> beaconNamingRecords;
}
