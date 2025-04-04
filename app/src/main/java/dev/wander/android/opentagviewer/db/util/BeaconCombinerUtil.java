package dev.wander.android.opentagviewer.db.util;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.db.repo.model.BeaconData;
import dev.wander.android.opentagviewer.db.repo.model.ImportData;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.db.room.entity.UserBeaconOptions;
import dev.wander.android.opentagviewer.util.BeaconLocationReportHasher;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BeaconCombinerUtil {
    public static List<BeaconData> combine(
            final List<OwnedBeacon> ownedBeacons,
            final List<BeaconNamingRecord> beaconNamingRecords,
            final List<UserBeaconOptions> userBeaconOptions) {

        Map<String, OwnedBeacon> idToBeaconMap = ownedBeacons.stream()
                .collect(Collectors.toMap((beacon) -> beacon.id, beacon -> beacon));

        Map<String, UserBeaconOptions> idToOptionsMap = userBeaconOptions.stream()
                .collect(Collectors.toMap((options) -> options.beaconId, options -> options));


        return beaconNamingRecords.stream()
                .map(namingRec -> new BeaconData(
                        namingRec.id,
                        idToBeaconMap.get(namingRec.id),
                        namingRec,
                        idToOptionsMap.getOrDefault(namingRec.id, null)
                ))
                .collect(Collectors.toList());
    }

    public static List<BeaconData> combine(final ImportData beaconData) {
        return combine(beaconData.getOwnedBeacons(), beaconData.getBeaconNamingRecords(), Collections.emptyList());
    }

    public static List<BeaconLocationReport> combineAndSort(final String beaconId, final List<BeaconLocationReport> first, final List<BeaconLocationReport> second) {
        Map<String, BeaconLocationReport> distinctItems = first.stream().collect(Collectors.toMap(
                report -> BeaconLocationReportHasher.getSha256HashFor(beaconId, report),
                report -> report
        ));

        // this will override items that we consider "duplicates" with the item from list 2
        second.forEach(report -> distinctItems.put(
                BeaconLocationReportHasher.getSha256HashFor(beaconId, report),
                report
        ));

        return distinctItems.values().stream()
                .sorted(Comparator.comparingLong(BeaconLocationReport::getTimestamp))
                .collect(Collectors.toList());
    }
}
