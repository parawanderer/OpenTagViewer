package dev.wander.android.opentagviewer.db.repo;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;

/**
 * Deleted history does not stay readable in the database file.
 *
 * <p>Until a checkpoint, a deleted row's bytes stay in the main file's old pages and in the
 * write-ahead log, so anyone reading the files directly finds the positions somebody chose not to
 * keep (AGENTS.md rule 24). This reads both files the way they would, byte for byte, looking for
 * text that only the deleted reports carried.
 *
 * <p>A file rather than an in-memory database, because the file is the thing being asserted on.
 * Its own name, so it never touches the app's real database.
 */
@RunWith(AndroidJUnit4.class)
public class CompactingAfterDeletingHistoryTest {

    private static final String NAME = "compacting-after-deleting-history-test.db";
    private static final String A_TAG = "tag-a";

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final int A_MONTH = 31;

    /** Only the reports that get deleted carry this. */
    private static final String GONE = "DELETED-REPORT-MARKER";
    /** Only the report that is kept carries this, to show the search can find what is there. */
    private static final String KEPT = "KEPT-REPORT-MARKER";

    /** Padding, so a few hundred reports fill whole pages rather than share one. */
    private static final String PADDING = "x".repeat(1000);

    private final Context context = getInstrumentation().getTargetContext();
    private OpenTagViewerDatabase db;
    private LocationHistoryRetention retention;

    @Before
    public void openADatabaseFile() {
        this.context.deleteDatabase(NAME);
        this.db = Room.databaseBuilder(this.context, OpenTagViewerDatabase.class, NAME)
                // As the app has it on any phone with the memory for it.
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .allowMainThreadQueries()
                .build();
        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(A_TAG)
                .content("{}")
                .accessoryJson("{\"type\":\"accessory\"}")
                .version("0.0.2")
                .fromAccount(false)
                .isRemoved(false)
                .build());
        this.retention = new LocationHistoryRetention(this.db, () -> NOW);
    }

    @After
    public void deleteIt() {
        this.db.close();
        this.context.deleteDatabase(NAME);
    }

    @Test
    public void choosingAPeriodLeavesNothingOfTheDeletedReportsInTheFile() throws IOException {
        this.insertOldReports(300);
        this.insertTheNewest();
        assertTrue("the deleted reports were never in the file, so this proves nothing",
                this.fileContains(GONE));

        assertEquals(300, (int) this.retention.prune(A_MONTH).blockingGet());

        assertTrue("the search cannot find even the report that was kept",
                this.fileContains(KEPT));
        assertFalse("a deleted report is still readable in the database file",
                this.fileContains(GONE));
    }

    /**
     * The delete at every app start only checkpoints, and that alone has to be enough. It is
     * here that an unstepped checkpoint, or none, leaves the deleted rows readable.
     */
    @Test
    public void theDeleteAtStartLeavesNothingOfTheDeletedReportsInTheFile() throws IOException {
        this.insertOldReports(300);
        this.insertTheNewest();
        assertTrue("the deleted reports were never in the file, so this proves nothing",
                this.fileContains(GONE));

        assertEquals(300, (int) this.retention.pruneAtStart(A_MONTH).blockingGet());

        assertTrue("the search cannot find even the report that was kept",
                this.fileContains(KEPT));
        assertFalse("a deleted report is still readable in the database file",
                this.fileContains(GONE));
    }

    private void insertOldReports(final int howMany) {
        final LocationReport[] reports = new LocationReport[howMany];
        for (int i = 0; i < howMany; i++) {
            reports[i] = report(NOW - (100 * DAY) - i, GONE + "-" + i + "-" + PADDING);
        }
        this.db.locationReportDao().insertAll(reports);
    }

    private void insertTheNewest() {
        this.db.locationReportDao().insertAll(report(NOW - DAY, KEPT));
    }

    /** Whether the database file or its write-ahead log holds {@code text}, read as raw bytes. */
    private boolean fileContains(final String text) throws IOException {
        final File file = this.context.getDatabasePath(NAME);
        final byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        return contains(file, needle) || contains(new File(file.getPath() + "-wal"), needle);
    }

    private static boolean contains(final File file, final byte[] needle) throws IOException {
        if (!file.exists()) {
            return false;
        }
        final byte[] haystack = Files.readAllBytes(file.toPath());
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static LocationReport report(final long timestamp, final String description) {
        return LocationReport.builder()
                .hashId(A_TAG + "-" + timestamp)
                .beaconId(A_TAG)
                .publishedAt(timestamp)
                .description(description)
                .timestamp(timestamp)
                .confidence(1)
                .latitude(1.0)
                .longitude(-1.0)
                .horizontalAccuracy(10L)
                .status(0)
                .lastUpdate(timestamp)
                .provenance(LocationReport.PROVENANCE_APPLE)
                .build();
    }
}
