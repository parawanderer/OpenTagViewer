package dev.wander.android.opentagviewer.db.room.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;

@Dao
public interface OwnedBeaconDao {
    @Query("SELECT * FROM OwnedBeacons WHERE is_removed = 0")
    List<OwnedBeacon> getAll();

    @Query("SELECT * FROM OwnedBeacons WHERE import_id = :importId AND is_removed = 0")
    List<OwnedBeacon> getAllByImportId(int importId);

    @Query("SELECT * FROM OwnedBeacons WHERE id = :beaconId AND is_removed = 0")
    OwnedBeacon getById(String beaconId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertAll(OwnedBeacon... ownedBeacons);

    @Query("UPDATE OwnedBeacons SET is_removed = 1 WHERE id = :beaconId")
    void setRemoved(String beaconId);

    @Delete
    void delete(OwnedBeacon ownedBeaconWithId);
}
