package dev.wander.android.opentagviewer.db.room;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.ZoneOffset;
import java.util.List;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.util.export.HistoryExportEntry;
import dev.wander.android.opentagviewer.util.export.HistoryZipWriter;
import dev.wander.android.opentagviewer.util.history.HistoryImportResult;
import dev.wander.android.opentagviewer.util.history.HistoryImportException;
import dev.wander.android.opentagviewer.util.history.HistoryImporter;

/** The restore interface joined to a real transactional Room database. */
@RunWith(AndroidJUnit4.class)
public class HistoryImportTest {
    private static final String TAG_A = "tag-a";
    private static final String TAG_B = "tag-b";
    private static final String UNKNOWN_TAG = "unknown-tag";
    private static final String REMOVED_TAG = "removed-tag";

    private OpenTagViewerDatabase db;

    @Before
    public void openDatabase() {
        this.db = Room.inMemoryDatabaseBuilder(
                        getInstrumentation().getTargetContext(), OpenTagViewerDatabase.class)
                .allowMainThreadQueries()
                .build();
        this.db.ownedBeaconDao().insertAll(own(TAG_A), own(TAG_B), own(REMOVED_TAG));
        this.db.ownedBeaconDao().setRemoved(REMOVED_TAG);
    }

    @After
    public void closeDatabase() {
        this.db.close();
    }

    @Test
    public void aSecondImportPreservesExistingRowsAndOnlyAddsTheOverlap() throws Exception {
        final HistoryImportResult first = importHistory(entry(TAG_A,
                report(1_000L, "first"), report(2_000L, "existing")));
        final HistoryImportResult second = importHistory(entry(TAG_A,
                report(2_000L, "replacement"), report(3_000L, "new")));

        assertCounts(first, 2, 2, 0, 0);
        assertCounts(second, 2, 1, 1, 0);
        final List<LocationReport> stored = this.db.locationReportDao()
                .getInTimeRange(TAG_A, 0, Long.MAX_VALUE);
        assertEquals(3, stored.size());
        assertEquals("existing", stored.get(1).description);

        final HistoryImportResult third = importHistory(entry(TAG_A,
                report(1_000L, "ignored"), report(2_000L, "ignored"),
                report(3_000L, "ignored")));
        assertCounts(third, 3, 0, 3, 0);
    }

    @Test
    public void equalTimestampsBelongIndependentlyToDifferentBeacons() throws Exception {
        final HistoryImportResult result = importHistory(
                entry(TAG_A, report(4_000L, "a")),
                entry(TAG_B, report(4_000L, "b")));

        assertCounts(result, 2, 2, 0, 0);
        assertEquals(4_000L, this.db.locationReportDao().getLastFor(TAG_A).timestamp);
        assertEquals(4_000L, this.db.locationReportDao().getLastFor(TAG_B).timestamp);
    }

    @Test
    public void firstRepeatedTimestampInTheArchiveWins() throws Exception {
        final HistoryImportResult result = importHistory(entry(TAG_A,
                report(5_000L, "first"), report(5_000L, "second")));

        assertCounts(result, 2, 1, 1, 0);
        assertEquals("first", this.db.locationReportDao().getLastFor(TAG_A).description);
    }

    @Test
    public void unknownAndRemovedTagsAreSkippedAndAnImportedTagCanBeRetried() throws Exception {
        final HistoryExportEntry unknown = entry(UNKNOWN_TAG, report(6_000L, "unknown"));
        final HistoryImportResult skipped = importHistory(
                unknown, entry(REMOVED_TAG, report(7_000L, "removed")));

        assertCounts(skipped, 2, 0, 0, 2);

        this.db.ownedBeaconDao().insertIfNew(own(UNKNOWN_TAG));
        final HistoryImportResult retried = importHistory(unknown);

        assertCounts(retried, 1, 1, 0, 0);
        assertEquals(6_000L,
                this.db.locationReportDao().getLastFor(UNKNOWN_TAG).timestamp);
    }

    @Test
    public void aDatabaseFailureRollsBackRowsAlreadyInsertedByTheImport() throws Exception {
        this.db.getOpenHelper().getWritableDatabase().execSQL(
                "CREATE TRIGGER reject_second_import_row "
                        + "BEFORE INSERT ON LocationReport "
                        + "WHEN NEW.timestamp = 9000 "
                        + "BEGIN SELECT RAISE(ABORT, 'deliberate import failure'); END");

        final HistoryImportException error = assertThrows(
                HistoryImportException.class,
                () -> importHistory(entry(TAG_A,
                        report(8_000L, "would have been inserted"),
                        report(9_000L, "forces rollback"))));

        assertEquals(HistoryImportException.Reason.DATABASE_FAILED, error.getReason());
        assertEquals(0, this.db.locationReportDao()
                .getInTimeRange(TAG_A, 0, Long.MAX_VALUE).size());
    }

    private HistoryImportResult importHistory(final HistoryExportEntry... entries)
            throws Exception {
        final ByteArrayOutputStream archive = new ByteArrayOutputStream();
        new HistoryZipWriter(ZoneOffset.UTC).write(archive, List.of(entries));
        return new HistoryImporter(this.db).importArchive(
                new ByteArrayInputStream(archive.toByteArray()));
    }

    private static HistoryExportEntry entry(
            final String beaconId, final BeaconLocationReport... reports) {
        return new HistoryExportEntry(beaconId, beaconId, List.of(reports));
    }

    private static BeaconLocationReport report(final long timestamp, final String description) {
        return BeaconLocationReport.builder()
                .timestamp(timestamp)
                .publishedAt(timestamp + 1)
                .latitude(1.25)
                .longitude(2.5)
                .horizontalAccuracy(10)
                .confidence(1)
                .status(0)
                .description(description)
                .build();
    }

    private static OwnedBeacon own(final String id) {
        return OwnedBeacon.builder()
                .id(id)
                .content("{}")
                .accessoryJson("{\"type\":\"accessory\"}")
                .version("0.0.2")
                .fromAccount(false)
                .isRemoved(false)
                .build();
    }

    private static void assertCounts(
            final HistoryImportResult actual,
            final int read,
            final int added,
            final int alreadyPresent,
            final int unknown) {
        assertEquals(read, actual.getRowsRead());
        assertEquals(added, actual.getRowsAdded());
        assertEquals(alreadyPresent, actual.getRowsAlreadyPresent());
        assertEquals(0, actual.getRowsMalformed());
        assertEquals(unknown, actual.getRowsSkippedUnknownBeacon());
    }
}
