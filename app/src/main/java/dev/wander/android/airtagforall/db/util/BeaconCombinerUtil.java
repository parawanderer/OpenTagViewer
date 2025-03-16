package dev.wander.android.airtagforall.db.util;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.wander.android.airtagforall.data.model.BeaconLocationReport;
import dev.wander.android.airtagforall.db.repo.model.BeaconData;
import dev.wander.android.airtagforall.db.repo.model.ImportData;
import dev.wander.android.airtagforall.db.room.entity.BeaconNamingRecord;
import dev.wander.android.airtagforall.db.room.entity.OwnedBeacon;
import dev.wander.android.airtagforall.util.BeaconLocationReportHasher;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BeaconCombinerUtil {
    public static List<BeaconData> combine(final List<OwnedBeacon> ownedBeacons, final List<BeaconNamingRecord> beaconNamingRecords) {
        Map<String, OwnedBeacon> idToBeaconMap = ownedBeacons.stream()
                .collect(Collectors.toMap((beacon) -> beacon.id, beacon -> beacon));

        return beaconNamingRecords.stream()
                .map(namingRec -> new BeaconData(
                        namingRec.id,
                        idToBeaconMap.get(namingRec.id),
                        namingRec
                ))
                .collect(Collectors.toList());
    }

    public static List<BeaconData> combine(final ImportData beaconData) {
        return combine(beaconData.getOwnedBeacons(), beaconData.getBeaconNamingRecords());
    }

    public static List<BeaconLocationReport> combineAndSort(final String beaconId, final List<BeaconLocationReport> current, final List<BeaconLocationReport> copyIntoCurrent) {
        Map<String, BeaconLocationReport> distinctItems = current.stream().collect(Collectors.toMap(
                report -> BeaconLocationReportHasher.getSha256HashFor(beaconId, report),
                report -> report
        ));

        // this will override items that we consider "duplicates" with the item from list 2
        copyIntoCurrent.forEach(report -> distinctItems.put(
                BeaconLocationReportHasher.getSha256HashFor(beaconId, report),
                report
        ));

        return distinctItems.values().stream()
                .sorted(Comparator.comparingLong(BeaconLocationReport::getTimestamp))
                .collect(Collectors.toList());
    }
}
