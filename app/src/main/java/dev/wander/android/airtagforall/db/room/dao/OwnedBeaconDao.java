package dev.wander.android.airtagforall.db.room.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

import dev.wander.android.airtagforall.db.room.entity.OwnedBeacon;

@Dao
public interface OwnedBeaconDao {
    @Query("SELECT * FROM OwnedBeacons")
    List<OwnedBeacon> getAll();

    @Query("SELECT * FROM OwnedBeacons WHERE import_id = :importId")
    List<OwnedBeacon> getAllByImportId(int importId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertAll(OwnedBeacon... ownedBeacons);

    @Delete
    void delete(OwnedBeacon ownedBeaconWithId);
}
