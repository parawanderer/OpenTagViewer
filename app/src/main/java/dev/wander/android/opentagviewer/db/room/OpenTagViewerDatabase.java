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
    version = 3
)
public abstract class OpenTagViewerDatabase extends RoomDatabase {
    private static OpenTagViewerDatabase INSTANCE = null;

    /**
     * v1 → v2: adds {@code accessory_json} to {@code OwnedBeacons} for FindMy 0.9.x's
     * stateful FindMyAccessory persistence (issue #30 fix). Pure additive ALTER —
     * existing rows survive with NULL and are lazily backfilled on first fetch.
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE OwnedBeacons ADD COLUMN accessory_json TEXT");
        }
    };

    /**
     * v2 → v3: adds {@code alignment_plist} to {@code OwnedBeacons}, holding the
     * KeyAlignmentRecord exported from macOS.
     * <br>
     * Pure additive ALTER. Existing rows stay NULL, which is correct: their exports
     * predate format 0.0.2 and genuinely have no alignment record. Those beacons keep
     * working exactly as before, relying on the alignment probe in main.py instead.
     */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE OwnedBeacons ADD COLUMN alignment_plist TEXT");
        }
    };

    /**
     * The database file's name, which is also read directly - see
     * {@code OpenAirTagApplication.isFirstRun()}, which uses the file's presence to tell a new
     * user from a returning one before anything has opened the database.
     */
    public static final String DATABASE_NAME = "opentagviewer-db";

    public static OpenTagViewerDatabase getInstance(Context context) {
        // Singleton pattern for single-process apps: https://developer.android.com/training/data-storage/room#java

        if (INSTANCE == null) {
            INSTANCE = Room.databaseBuilder(
                    context,
                    OpenTagViewerDatabase.class,
                    DATABASE_NAME)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
