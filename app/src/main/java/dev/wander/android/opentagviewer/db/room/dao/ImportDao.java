package dev.wander.android.opentagviewer.db.room.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

import dev.wander.android.opentagviewer.db.room.entity.Import;

@Dao
public interface ImportDao {
    @Query("SELECT * FROM Import")
    List<Import> getAll();

    @Query("SELECT * FROM Import WHERE source_user = :sourceUser")
    List<Import> getImportsFromUser(String sourceUser);

    @Query("SELECT * FROM Import WHERE id = :importId")
    Import getById(long importId);

    /**
     * The most recent import, or null if nothing has ever been imported.
     *
     * <p>Read for one thing: naming the bundle a log came from. Which program wrote an export
     * decides what to expect of it, and until this the only way to find out was to open the zip -
     * a bad instruction generally and a dangerous one now that bundles are password-protected by
     * default.
     *
     * <p>Null is a real answer, not a gap: an install connected straight to an Apple account has
     * no bundle behind it at all.
     */
    @Query("SELECT * FROM Import ORDER BY imported_at DESC LIMIT 1")
    Import getMostRecent();

    /**
     * Every distinct exporter that produced a bundle on this install, newest first.
     *
     * <p><b>Distinct, because {@link #getMostRecent()} answers a different question than it
     * looks like it does.</b> Importing twice is ordinary - a second Mac, a re-export after a
     * new tag, a bundle from an older wizard alongside a current one - and asking only for the
     * latest names one producer while implying it accounts for everything on the phone. A report
     * saying "exported with 1.3.0" when half the tags came out of 1.1.0 sends whoever reads it
     * looking in the wrong place.
     *
     * <p>{@code GROUP BY} rather than {@code SELECT DISTINCT}, because the ordering is by
     * something not in the result: SQLite rejects an {@code ORDER BY} on a column a
     * {@code DISTINCT} query does not select, and {@code MAX(imported_at)} per producer is the
     * sort that actually means "most recently used".
     */
    @Query("SELECT via FROM Import WHERE via IS NOT NULL AND via != ''"
            + " GROUP BY via ORDER BY MAX(imported_at) DESC")
    List<String> getDistinctProducers();

    @Insert
    long insert(Import importData);

    @Delete
    int delete(Import importDataWithId);
}
