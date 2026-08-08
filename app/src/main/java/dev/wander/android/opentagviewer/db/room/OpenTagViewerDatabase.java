package dev.wander.android.opentagviewer.db.room;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import dev.wander.android.opentagviewer.db.room.dao.BeaconNamingRecordDao;
import dev.wander.android.opentagviewer.db.room.dao.DailyHistoryFetchRecordDao;
import dev.wander.android.opentagviewer.db.room.dao.ImportDao;
import dev.wander.android.opentagviewer.db.room.dao.LocationReportDao;
import dev.wander.android.opentagviewer.db.room.dao.OwnedBeaconDao;
import dev.wander.android.opentagviewer.db.room.dao.UserBeaconOptionsDao;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.DailyHistoryFetchRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.db.room.entity.UserBeaconOptions;

@Database(
    entities = {
        Import.class,
        BeaconNamingRecord.class,
        OwnedBeacon.class,
        LocationReport.class,
        DailyHistoryFetchRecord.class,
        UserBeaconOptions.class
    },
    version = 2
)
public abstract class OpenTagViewerDatabase extends RoomDatabase {
    private static OpenTagViewerDatabase INSTANCE = null;

    /**
     * v1 → v2: adds {@code accessory_json} to {@code OwnedBeacons} for FindMy 0.9.x's
     * stateful FindMyAccessory persistence (issue #30 fix). Pure additive ALTER —
     * existing rows survive with NULL and are lazily backfilled on first fetch.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE OwnedBeacons ADD COLUMN accessory_json TEXT");
        }
    };

    public static OpenTagViewerDatabase getInstance(Context context) {
        // Singleton pattern for single-process apps: https://developer.android.com/training/data-storage/room#java

        if (INSTANCE == null) {
            INSTANCE = Room.databaseBuilder(
                    context,
                    OpenTagViewerDatabase.class,
                    "opentagviewer-db")
                    .addMigrations(MIGRATION_1_2)
                    .build();
        }

        return INSTANCE;
    }

    public abstract ImportDao importDao();
    public abstract BeaconNamingRecordDao beaconNamingRecordDao();
    public abstract OwnedBeaconDao ownedBeaconDao();
    public abstract LocationReportDao locationReportDao();
    public abstract DailyHistoryFetchRecordDao dailyHistoryFetchRecordDao();
    public abstract UserBeaconOptionsDao userBeaconOptionsDao();
}
