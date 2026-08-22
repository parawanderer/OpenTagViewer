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
    /**
     * Join a tag's rows into the shape the UI reads.
     *
     * <p><b>Driven by the owned beacons, because that is what a tag is.</b> A
     * {@link BeaconNamingRecord} is something an Apple device wrote <i>about</i> a tag - a name,
     * an emoji - and a tag that was never in an Apple account has none, because no iPad ever
     * named it. Iterating the naming records instead made those tags invisible to every screen
     * built on this, while the fetch path - which reads owned beacons directly - went on
     * collecting reports for them perfectly happily. An imported self-generated tag reported
     * "1 device imported" and then appeared nowhere.
     *
     * <p>For an Apple tag nothing changes: the importer inner-joins the two sets, so every owned
     * beacon has exactly one naming record and vice versa. Driving from this side is in fact the
     * safer of the two - the old direction could hand out a {@code BeaconData} whose
     * {@code ownedBeaconInfo} was null, which is the half nothing downstream can work without.
     *
     * @return one entry per owned beacon, with a null naming record where there is none.
     */
    public static List<BeaconData> combine(
            final List<OwnedBeacon> ownedBeacons,
            final List<BeaconNamingRecord> beaconNamingRecords,
            final List<UserBeaconOptions> userBeaconOptions) {

        Map<String, BeaconNamingRecord> idToNamingRecordMap = beaconNamingRecords.stream()
                .collect(Collectors.toMap((namingRec) -> namingRec.id, namingRec -> namingRec));

        Map<String, UserBeaconOptions> idToOptionsMap = userBeaconOptions.stream()
                .collect(Collectors.toMap((options) -> options.beaconId, options -> options));


        return ownedBeacons.stream()
                .map(beacon -> new BeaconData(
                        beacon.id,
                        beacon,
                        idToNamingRecordMap.getOrDefault(beacon.id, null),
                        idToOptionsMap.getOrDefault(beacon.id, null)
                ))
                .collect(Collectors.toList());
    }

    public static List<BeaconData> combine(final ImportData beaconData) {
        return combine(beaconData.getOwnedBeacons(), beaconData.getBeaconNamingRecords(), Collections.emptyList());
    }

    /**
     * Merge two sets of reports for one tag, dropping the ones that are the same sighting.
     *
     * <p><b>Duplicates inside {@code first} used to crash the screen.</b> {@code Collectors.toMap}
     * throws {@code IllegalStateException} on a repeated key, and the key here is deliberately
     * blind to {@code horizontalAccuracy} and {@code confidence} - see
     * {@link BeaconLocationReportHasher}, which excludes them because they are the fields Apple
     * is least consistent about. So two reports of the same sighting at the same instant,
     * differing only in a stated accuracy of 97 metres versus 92, are *meant* to collapse into
     * one - and instead took down the whole day's history with a stack trace.
     *
     * <p><b>The tighter accuracy wins, rather than whichever arrived last.</b> Order here is
     * Apple's, which is arbitrary, so "last wins" would make the accuracy shown for a sighting
     * change between fetches for no reason. Smaller is a better answer and a stable one.
     */
    public static List<BeaconLocationReport> combineAndSort(final String beaconId, final List<BeaconLocationReport> first, final List<BeaconLocationReport> second) {
        Map<String, BeaconLocationReport> distinctItems = first.stream().collect(Collectors.toMap(
                report -> BeaconLocationReportHasher.getSha256HashFor(beaconId, report),
                report -> report,
                BeaconCombinerUtil::theMorePreciseOf
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

    /**
     * Of two reports the hash considers the same sighting, the one that claims to know better.
     *
     * <p>{@code horizontalAccuracy} is a radius in metres, so smaller is tighter. Ties keep the
     * first, which only matters for making the result stable.
     */
    private static BeaconLocationReport theMorePreciseOf(
            final BeaconLocationReport a, final BeaconLocationReport b) {

        return b.getHorizontalAccuracy() < a.getHorizontalAccuracy() ? b : a;
    }
}
