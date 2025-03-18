package dev.wander.android.opentagviewer.db.room.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

import dev.wander.android.opentagviewer.db.room.entity.UserBeaconOptions;

@Dao
public interface UserBeaconOptionsDao {
    @Query("SELECT * FROM UserBeaconOptions")
    List<UserBeaconOptions> getAll();

    @Query("SELECT * FROM UserBeaconOptions WHERE beacon_id = :beaconId")
    UserBeaconOptions getById(String beaconId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(UserBeaconOptions... options);
}
