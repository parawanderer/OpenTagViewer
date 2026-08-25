package dev.wander.android.opentagviewer.db.room;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import dev.wander.android.opentagviewer.db.room.dao.BeaconNamingRecordDao;
import dev.wander.android.opentagviewer.db.room.dao.LastBleSightingDao;
import dev.wander.android.opentagviewer.db.room.dao.DailyHistoryFetchRecordDao;
import dev.wander.android.opentagviewer.db.room.dao.ImportDao;
import dev.wander.android.opentagviewer.db.room.dao.LocationReportDao;
import dev.wander.android.opentagviewer.db.room.dao.OwnedBeaconDao;
import dev.wander.android.opentagviewer.db.room.dao.UserBeaconOptionsDao;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.LastBleSighting;
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
        UserBeaconOptions.class,
        LastBleSighting.class
    },
    version = 8
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
     * v3 → v4: adds {@code from_account} to {@code OwnedBeacons}, marking a beacon as read from
     * the user's Apple account rather than imported from a file.
     *
     * <p><b>The two are not the same kind of row and must not be treated alike.</b> An account
     * beacon is a cache: the list is refreshed from Apple, so one that has left the account is
     * removed here too. A file-imported beacon is the only copy that exists - nobody else holds
     * it, and the export it came from may be long gone - so a refresh must never touch it. Without
     * a way to tell them apart, "drop what is no longer on the account" would delete every
     * imported tag the first time somebody fetched.
     *
     * <p>Pure additive ALTER with a default of 0, which is right for every existing row: they all
     * predate the account route and every one of them came from a file.
     */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                    "ALTER TABLE OwnedBeacons ADD COLUMN from_account INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * v4 → v5: three columns on {@code OwnedBeacons} for tags that have stopped broadcasting.
     *
     * <p>A tag with no key alignment record searches its whole life on every refresh, at a
     * request per ~290 keys. For a tag that will never answer that is the most expensive thing
     * in the batch, repeated forever - so {@code fruitless_scans} and {@code last_scan_at} drive
     * a backoff, and {@code ignored_at} marks the ones given up on entirely.
     *
     * <p>Additive, with defaults that mean "healthy, never scanned, not ignored" - so every
     * existing row keeps behaving exactly as it did until its first fruitless search.
     */
    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE OwnedBeacons"
                    + " ADD COLUMN fruitless_scans INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE OwnedBeacons ADD COLUMN last_scan_at INTEGER");
            db.execSQL("ALTER TABLE OwnedBeacons ADD COLUMN ignored_at INTEGER");
        }
    };

    /**
     * v5 → v6: adds {@code ui_order} to {@code UserBeaconOptions}, holding the position the user
     * dragged a tag to on the device list.
     *
     * <p>Additive, and nullable rather than defaulted. Null means "never arranged", which is
     * every existing row and is the honest answer for them - a default of 0 would claim every
     * tag had been deliberately put first. {@code TagOrder} reads null as "sort after anything
     * that was arranged, accessories before the owner's own devices", so an upgrade changes
     * nobody's order.
     *
     * <p><b>No classification is done here, on purpose.</b> The obvious alternative - seed the
     * column with the default order during the upgrade - would mean parsing every stored plist
     * inside a migration, on the database thread, on every user's device, at the one moment
     * where a throw destroys the database. The rule lives in a pure function instead and this
     * stays a single ALTER.
     */
    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE UserBeaconOptions ADD COLUMN ui_order INTEGER");
        }
    };

    /**
     * v6 → v7: adds {@code LastBleSighting}, the last thing this phone heard each tag say over
     * Bluetooth - today its battery level, and whatever else a sighting turns out to be worth
     * keeping later.
     *
     * <p>A new table rather than a column, for the reasons on {@link LastBleSighting} - briefly,
     * neither of the tables that already hold something per beacon is a place a measurement taken
     * by this phone belongs.
     *
     * <p>Creating an empty table changes nothing for an existing install: every tag simply has no
     * sighting until the next time its advertisement is actually heard, which is the honest
     * state. Nothing is backfilled, and in particular the accessory record's own battery field is
     * not copied in - that value is Apple's, is stale or unset for exactly the users this table
     * is for, and would arrive here presented as something this phone had heard.
     */
    public static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `LastBleSighting` ("
                    + "`beacon_id` TEXT NOT NULL, "
                    + "`heard_at` INTEGER NOT NULL, "
                    + "`battery_level` TEXT NOT NULL, "
                    + "`status_byte` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`beacon_id`), "
                    + "FOREIGN KEY(`beacon_id`) REFERENCES `OwnedBeacons`(`id`)"
                    + " ON UPDATE CASCADE ON DELETE CASCADE )");
        }
    };

    /**
     * v7 → v8: adds {@code provenance} to {@code LocationReport}, saying whether a row came from
     * Apple's network or from this phone hearing the tag itself.
     *
     * <p>Both kinds live in this table on purpose - everything that draws a tag reads from here -
     * but they are not the same claim, and the history export hands somebody a file in which
     * they would otherwise be indistinguishable. See {@link LocationReport#provenance}.
     *
     * <p>Additive, with a default of {@code apple}. That is not a fallback but the truth for
     * every existing row: local rows could not exist before this column did.
     */
    public static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE LocationReport"
                    + " ADD COLUMN provenance TEXT NOT NULL DEFAULT 'apple'");
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
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
    public abstract LastBleSightingDao lastBleSightingDao();
}
