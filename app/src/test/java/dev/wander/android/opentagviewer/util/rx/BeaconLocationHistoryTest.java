package dev.wander.android.opentagviewer.util.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;

/**
 * Tests for the location history the map draws from.
 * <br>
 * Both rules here fail silently rather than loudly, which is why they are worth pinning down:
 * a beacon with no location is skipped everywhere instead of being reported, and every caller
 * takes the newest report by reading the last element - correct only because the merge sorts.
 * A change to either shows the user a wrong or missing tag, not a stack trace.
 */
public class BeaconLocationHistoryTest {

    private static final String TAG_A = "46E881A1-3CD9-4965-AEA9-2D95414661E7";
    private static final String TAG_B = "049BC649-8733-4FA1-B31D-5BBDDE97E398";

    private BeaconLocationHistory history;

    @Before
    public void setUp() {
        this.history = new BeaconLocationHistory();
    }

    private static BeaconLocationReport at(final long timestamp, final double lat, final double lon) {
        return BeaconLocationReport.builder()
                .timestamp(timestamp)
                .latitude(lat)
                .longitude(lon)
                .horizontalAccuracy(10)
                .confidence(2)
                .status(0)
                .build();
    }

    private static BeaconLocationReport at(final long timestamp) {
        return at(timestamp, 51.5 + timestamp / 1_000_000.0, -0.12);
    }

    // --- the drawable rule ----------------------------------------------------------------

    @Test
    public void aBeaconWithNoReportsCannotBeDrawn() {
        // The normal state for a tag Apple has not answered for. It gets no marker and no
        // card, so it disappears from the UI rather than showing as an error.
        assertFalse(this.history.isDrawable(TAG_A));
        assertTrue(this.history.lastLocationOf(TAG_A).isEmpty());
    }

    @Test
    public void aBeaconWhoseFetchReturnedAnEmptyListCannotBeDrawn() {
        this.history.merge(TAG_A, List.of());

        // Distinct from "never fetched": the key exists but holds nothing.
        assertTrue(this.history.knows(TAG_A));
        assertFalse(this.history.isDrawable(TAG_A));
    }

    @Test
    public void aBeaconWithOneReportCanBeDrawn() {
        this.history.merge(TAG_A, List.of(at(1_000L)));

        assertTrue(this.history.isDrawable(TAG_A));
    }

    @Test
    public void oneBeaconsHistoryDoesNotMakeAnotherDrawable() {
        this.history.merge(TAG_A, List.of(at(1_000L)));

        // The bug that started this: two of three accessories were never fetched, and the
        // only symptom was their cards being absent.
        assertTrue(this.history.isDrawable(TAG_A));
        assertFalse(this.history.isDrawable(TAG_B));
    }

    // --- newest is last -------------------------------------------------------------------

    @Test
    public void theLastReportIsTheNewestOne() {
        this.history.merge(TAG_A, List.of(at(3_000L), at(1_000L), at(2_000L)));

        assertEquals(3_000L, this.history.lastLocationOf(TAG_A).orElseThrow().getTimestamp());
    }

    @Test
    public void theHistoryIsSortedOldestFirst() {
        this.history.merge(TAG_A, List.of(at(3_000L), at(1_000L)));
        this.history.merge(TAG_A, List.of(at(2_000L)));

        // Five callers read get(size - 1) to mean "newest". This is what makes that true.
        final List<BeaconLocationReport> reports = this.history.of(TAG_A);
        for (int i = 1; i < reports.size(); i++) {
            assertTrue("out of order at " + i,
                    reports.get(i - 1).getTimestamp() <= reports.get(i).getTimestamp());
        }
    }

    @Test
    public void aNewerReportArrivingLaterBecomesTheLastOne() {
        this.history.merge(TAG_A, List.of(at(1_000L)));
        this.history.merge(TAG_A, List.of(at(5_000L)));

        assertEquals(5_000L, this.history.lastLocationOf(TAG_A).orElseThrow().getTimestamp());
    }

    @Test
    public void anOlderReportArrivingLaterDoesNotBecomeTheLastOne() {
        this.history.merge(TAG_A, List.of(at(5_000L)));

        // A wider window backfills history; it must not make the map show a stale position.
        this.history.merge(TAG_A, List.of(at(1_000L)));

        assertEquals(5_000L, this.history.lastLocationOf(TAG_A).orElseThrow().getTimestamp());
        assertEquals(2, this.history.sizeOf(TAG_A));
    }

    // --- merging --------------------------------------------------------------------------

    @Test
    public void theFirstFetchIsKeptAsIs() {
        assertEquals(2, this.history.merge(TAG_A, List.of(at(1_000L), at(2_000L))));
        assertEquals(2, this.history.sizeOf(TAG_A));
    }

    @Test
    public void anOverlappingRefreshDoesNotDuplicateReports() {
        final BeaconLocationReport shared = at(2_000L);
        this.history.merge(TAG_A, List.of(at(1_000L), shared));

        // Refresh windows overlap on purpose so nothing is missed at the boundary, which
        // means the same report arrives again and has to collapse rather than accumulate.
        final int size = this.history.merge(TAG_A, List.of(shared, at(3_000L)));

        assertEquals(3, size);
        assertEquals(3, this.history.sizeOf(TAG_A));
    }

    @Test
    public void reportsAreDeduplicatedByContentRatherThanIdentity() {
        this.history.merge(TAG_A, List.of(at(2_000L, 51.5, -0.12)));
        this.history.merge(TAG_A, List.of(at(2_000L, 51.5, -0.12)));

        // A separate object with identical contents is the same sighting fetched twice.
        assertEquals(1, this.history.sizeOf(TAG_A));
    }

    @Test
    public void twoBeaconsKeepSeparateHistories() {
        this.history.merge(TAG_A, List.of(at(1_000L), at(2_000L)));
        this.history.merge(TAG_B, List.of(at(3_000L)));

        assertEquals(2, this.history.sizeOf(TAG_A));
        assertEquals(1, this.history.sizeOf(TAG_B));
        assertEquals(3_000L, this.history.lastLocationOf(TAG_B).orElseThrow().getTimestamp());
    }

    @Test
    public void anEmptyRefreshLeavesTheExistingHistoryAlone() {
        this.history.merge(TAG_A, List.of(at(1_000L), at(2_000L)));

        // Apple returning nothing this minute must not wipe the tag off the map.
        assertEquals(2, this.history.merge(TAG_A, List.of()));
        assertTrue(this.history.isDrawable(TAG_A));
    }

    @Test
    public void anUnknownBeaconReadsAsEmptyRatherThanNull() {
        assertEquals(List.of(), this.history.of(TAG_A));
        assertEquals(0, this.history.sizeOf(TAG_A));
        assertFalse(this.history.knows(TAG_A));
    }
}
