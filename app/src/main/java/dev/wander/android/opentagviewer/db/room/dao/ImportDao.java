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

    @Insert
    long insert(Import importData);

    @Delete
    int delete(Import importDataWithId);
}
