package dev.wander.android.opentagviewer.db.repo;

import android.database.Cursor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteDatabase;

import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;

/**
 * Makes deleted rows unreadable in the database file, not just in queries.
 *
 * <p>Android's SQLite is built with {@code secure_delete} and {@code auto_vacuum} on, so a delete
 * zeroes the row and gives freed pages back. <b>That happens in the write-ahead log, though.</b>
 * Until a checkpoint, the main file still holds the pages as they were before the delete, and the
 * log still holds the frames that wrote the rows in the first place. Anyone reading the files
 * directly finds the positions somebody removed or chose not to keep. Measured on the emulator:
 * after deleting, the rows are readable in both files; after a checkpoint, in neither.
 *
 * <p><b>The checkpoint has to be stepped.</b> {@code query("PRAGMA wal_checkpoint(TRUNCATE)")}
 * returns a cursor that has not run yet; closing it unread runs nothing. That is how this was
 * first written, and it left every deleted row in place. {@code CompactingAfterDeletingHistoryTest}
 * reads the files byte for byte so that cannot come back quietly.
 *
 * <p>Both methods are best effort: if another connection is busy they fail or stop short, and the
 * data is still deleted as far as the app is concerned. Not worth failing what the user asked
 * for over. The next one finishes the job.
 */
final class DatabaseCompaction {
    private static final String TAG = DatabaseCompaction.class.getSimpleName();

    private DatabaseCompaction() {
    }

    /**
     * Rewrite the whole file from the live rows, then checkpoint. For deletes somebody asked for:
     * a removed tag, a shorter history period.
     *
     * <p>The {@code VACUUM} is for a phone whose SQLite does not have {@code secure_delete} on,
     * where the checkpoint alone would leave the deleted rows in free space. It rewrites the whole
     * file, which is why the delete at every app start does not do it.
     */
    static void now(@NonNull final OpenTagViewerDatabase db, @NonNull final String after) {
        try {
            db.getOpenHelper().getWritableDatabase().execSQL("VACUUM");
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not compact the database after " + after, e);
        }
        checkpoint(db, after);
    }

    /**
     * Fold the write-ahead log back into the file and truncate it. Cheap: it writes only the
     * pages that changed. For the delete at app start.
     */
    static void checkpoint(@NonNull final OpenTagViewerDatabase db, @NonNull final String after) {
        final SupportSQLiteDatabase sqlite;
        try {
            sqlite = db.getOpenHelper().getWritableDatabase();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not open the database to checkpoint it after " + after, e);
            return;
        }
        // Read the row: that is what runs it. The first column is 1 when a reader kept it from
        // finishing.
        try (Cursor result = sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (result.moveToFirst() && result.getInt(0) != 0) {
                Log.w(TAG, "The checkpoint after " + after + " could not finish; what was"
                        + " deleted stays in the write-ahead log until the next one");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not checkpoint the database after " + after + "; the rows are"
                    + " gone but their bytes stay in the files until the next checkpoint", e);
        }
    }
}
