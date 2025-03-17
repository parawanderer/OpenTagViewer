package dev.wander.android.airtagforall.db.room;

import android.content.Context;

import androidx.room.AutoMigration;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import dev.wander.android.airtagforall.db.room.dao.BeaconNamingRecordDao;
import dev.wander.android.airtagforall.db.room.dao.DailyHistoryFetchRecordDao;
import dev.wander.android.airtagforall.db.room.dao.ImportDao;
import dev.wander.android.airtagforall.db.room.dao.LocationReportDao;
import dev.wander.android.airtagforall.db.room.dao.OwnedBeaconDao;
import dev.wander.android.airtagforall.db.room.entity.BeaconNamingRecord;
import dev.wander.android.airtagforall.db.room.entity.DailyHistoryFetchRecord;
import dev.wander.android.airtagforall.db.room.entity.Import;
import dev.wander.android.airtagforall.db.room.entity.LocationReport;
import dev.wander.android.airtagforall.db.room.entity.OwnedBeacon;

@Database(
    entities = {
        Import.class,
        BeaconNamingRecord.class,
        OwnedBeacon.class,
        LocationReport.class,
        DailyHistoryFetchRecord.class
    },
    version = 1
)
public abstract class AirTag4AllDatabase extends RoomDatabase {
    private static AirTag4AllDatabase INSTANCE = null;

    public static AirTag4AllDatabase getInstance(Context context) {
        // Singleton pattern for single-process apps: https://developer.android.com/training/data-storage/room#java

        if (INSTANCE == null) {
            INSTANCE = Room.databaseBuilder(
                    context,
                    AirTag4AllDatabase.class,
                    "airtag4all-db")
                    .build();
        }

        return INSTANCE;
    }

    public abstract ImportDao importDao();
    public abstract BeaconNamingRecordDao beaconNamingRecordDao();
    public abstract OwnedBeaconDao ownedBeaconDao();
    public abstract LocationReportDao locationReportDao();

    public abstract DailyHistoryFetchRecordDao dailyHistoryFetchRecordDao();
}
