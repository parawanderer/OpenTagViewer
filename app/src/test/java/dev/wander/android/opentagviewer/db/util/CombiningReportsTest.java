package dev.wander.android.opentagviewer.db.util;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;

/**
 * Merging what Apple just sent with what the app already had.
 *
 * <p><b>This crashed a whole day of history, from a stack trace with nothing user-facing in
 * it.</b> The de-duplicating key deliberately ignores {@code horizontalAccuracy} and
 * {@code confidence} - the two fields Apple is least consistent about - so the same sighting
 * reported twice with accuracies of 97 and 92 metres is *meant* to collapse. Instead
 * {@code Collectors.toMap} threw {@code IllegalStateException: Duplicate key} and the history
 * screen reported the day as a failure.
 *
 * <p>Pure, so it belongs here: no Android, no database, no network.
 */
public class CombiningReportsTest {

    private static final String TAG = "a-tag";

    /** Same instant, same place - Apple simply disagreed with itself about the radius. */
    @Test
    public void thesameSightingTwiceWithDifferentAccuracyIsOneReport() {
        final List<BeaconLocationReport> remote = Arrays.asList(
                reportAt(1787219960000L, 52.307505, 4.945841, 97),
                reportAt(1787219960000L, 52.307505, 4.945841, 92));

        final List<BeaconLocationReport> merged =
                BeaconCombinerUtil.combineAndSort(TAG, remote, List.of());

        assertEquals("two reports of one sighting must collapse, not crash", 1, merged.size());
        assertEquals("the tighter accuracy is the better answer",
                92, merged.get(0).getHorizontalAccuracy());
    }

    /**
     * <b>And it does not depend on which order Apple sent them in.</b>
     *
     * <p>The reason the rule is "smaller wins" rather than "last wins": ordering here is
     * Apple's and is arbitrary, so a last-wins merge would show 97 metres on one refresh and 92
     * on the next, for the same sighting, with nothing having changed.
     */
    @Test
    public void whichOneSurvivesDoesNotDependOnTheOrderTheyArrived() {
        final BeaconLocationReport loose = reportAt(1787219960000L, 52.307505, 4.945841, 97);
        final BeaconLocationReport tight = reportAt(1787219960000L, 52.307505, 4.945841, 92);

        final long oneWay = BeaconCombinerUtil
                .combineAndSort(TAG, Arrays.asList(loose, tight), List.of())
                .get(0).getHorizontalAccuracy();
        final long theOther = BeaconCombinerUtil
                .combineAndSort(TAG, Arrays.asList(tight, loose), List.of())
                .get(0).getHorizontalAccuracy();

        assertEquals(oneWay, theOther);
        assertEquals(92, oneWay);
    }

    /** Genuinely different sightings are all kept, and come back oldest first. */
    @Test
    public void differentSightingsAreAllKeptInTimeOrder() {
        final List<BeaconLocationReport> remote = Arrays.asList(
                reportAt(3000L, 52.1, 4.1, 50),
                reportAt(1000L, 52.2, 4.2, 50),
                reportAt(2000L, 52.3, 4.3, 50));

        final List<Long> times = BeaconCombinerUtil
                .combineAndSort(TAG, remote, List.of())
                .stream().map(BeaconLocationReport::getTimestamp).collect(Collectors.toList());

        assertEquals(Arrays.asList(1000L, 2000L, 3000L), times);
    }

    /**
     * <b>The stored copy still wins over the fetched one</b>, which is the existing behaviour and
     * not something the crash fix should have quietly changed.
     */
    @Test
    public void thesecondListStillOverridesTheFirst() {
        final List<BeaconLocationReport> remote = List.of(
                reportAt(1000L, 52.1, 4.1, 90));
        final List<BeaconLocationReport> local = List.of(
                reportAt(1000L, 52.1, 4.1, 30));

        final List<BeaconLocationReport> merged =
                BeaconCombinerUtil.combineAndSort(TAG, remote, local);

        assertEquals(1, merged.size());
        assertEquals(30, merged.get(0).getHorizontalAccuracy());
    }

    /** A duplicate within the *second* list is fine too - it is a plain put, not a collector. */
    @Test
    public void duplicatesInTheSecondListDoNotCrashEither() {
        final List<BeaconLocationReport> local = Arrays.asList(
                reportAt(1000L, 52.1, 4.1, 90),
                reportAt(1000L, 52.1, 4.1, 30));

        assertEquals(1, BeaconCombinerUtil.combineAndSort(TAG, List.of(), local).size());
    }

    @Test
    public void twoemptyListsAreAnEmptyResult() {
        assertEquals(0, BeaconCombinerUtil.combineAndSort(TAG, List.of(), List.of()).size());
    }

    private static BeaconLocationReport reportAt(
            final long timestamp, final double latitude, final double longitude,
            final long horizontalAccuracy) {

        return BeaconLocationReport.builder()
                .publishedAt(timestamp)
                .description("")
                .timestamp(timestamp)
                .confidence(0)
                .latitude(latitude)
                .longitude(longitude)
                .horizontalAccuracy(horizontalAccuracy)
                .status(144)
                .build();
    }
}
