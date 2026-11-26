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

    @Insert
    long insert(Import importData);

    @Delete
    int delete(Import importDataWithId);

    /**
     * Delete an import once nothing refers to it any more, removed or not.
     *
     * <p>An import row records who exported the bundle ({@code source_user}, an Apple ID) and
     * when. Once its last tag has been erased it describes nothing, so it goes too. One that a
     * hidden, removed tag still points at stays, because a re-import may bring that tag back.
     *
     * <p><b>Naming records count too.</b> A tag that later arrived from the Apple account has its
     * {@code OwnedBeacons} row moved off the import, but when the account holds no naming record
     * for it, the one from the zip stays, still pointing here. {@code BeaconNamingRecord} cascades
     * on delete, so deleting the import would take that tag's name with it.
     */
    @Query("DELETE FROM Import WHERE id = :importId"
            + " AND NOT EXISTS (SELECT 1 FROM OwnedBeacons WHERE import_id = :importId)"
            + " AND NOT EXISTS (SELECT 1 FROM BeaconNamingRecord WHERE import_id = :importId)")
    int deleteIfNothingRefersTo(long importId);
}
