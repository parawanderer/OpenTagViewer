package dev.wander.android.opentagviewer.db.room;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

/**
 * Regression tests for the v1 → v2 database upgrade.
 * <br>
 * This exists because the failure mode is invisible on a fresh install: a version bump
 * without a registered {@link androidx.room.migration.Migration} only throws for users
 * who already have a database, and it throws at first DB access rather than at build
 * time. Every existing install would have crashed with
 * {@code IllegalStateException: A migration from 1 to 2 was required but not found}.
 * <br>
 * The migration must also be non-destructive: falling back to a destructive migration
 * here would wipe imported beacons and location history, forcing users through the
 * macOS export wizard again — the exact thing this app exists to avoid.
 */
@RunWith(AndroidJUnit4.class)
public class OpenTagViewerDatabaseMigrationTest {
    private static final String TEST_DB = "migration-test-db";

    private static final String BEACON_ID = "ABCDEF01-2345-6789-ABCD-EF0123456789";
    private static final String BEACON_PLIST = "<?xml version=\"1.0\"?><plist><dict></dict></plist>";

    @Rule
    public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OpenTagViewerDatabase.class
    );

    /**
     * The core guarantee: an existing v1 database survives the upgrade with its rows and
     * column values intact, and gains a NULL {@code accessory_json}.
     */
    @Test
    public void migrate1To2_preservesExistingBeacons() throws IOException {
        try (SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1)) {
            insertImport(db, 1L);
            insertOwnedBeaconV1(db, BEACON_ID, 1L, BEACON_PLIST, false);
        }

        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB, 2, /* validateDroppedTables= */ true, OpenTagViewerDatabase.MIGRATION_1_2);

        try (Cursor cursor = db.query(
                "SELECT id, import_id, content, version, is_removed, accessory_json"
                        + " FROM OwnedBeacons WHERE id = ?", new Object[]{BEACON_ID})) {

            assertTrue("beacon row did not survive the migration", cursor.moveToFirst());
            assertEquals(BEACON_ID, cursor.getString(0));
            assertEquals(1L, cursor.getLong(1));
            assertEquals("plist content was altered by the migration", BEACON_PLIST, cursor.getString(2));
            assertEquals("1.0", cursor.getString(3));
            assertEquals(0, cursor.getInt(4));

            // Pre-existing rows have no accessory JSON yet. BeaconRepository#toAccessoryRequests
            // backfills it from the retained plist on the next fetch.
            assertTrue("accessory_json should start NULL for migrated rows", cursor.isNull(5));
            assertFalse("expected exactly one row for this beacon", cursor.moveToNext());
        }
    }

    /**
     * The plist in {@code content} is what the lazy backfill re-converts from, so the
     * migration must not drop it once {@code accessory_json} exists. Guards against a
     * future "clean up the now-redundant column" change.
     */
    @Test
    public void migrate1To2_retainsPlistContentColumn() throws IOException {
        try (SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1)) {
            insertImport(db, 1L);
            insertOwnedBeaconV1(db, BEACON_ID, 1L, BEACON_PLIST, false);
        }

        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB, 2, true, OpenTagViewerDatabase.MIGRATION_1_2);

        try (Cursor cursor = db.query("SELECT content FROM OwnedBeacons WHERE id = ?", new Object[]{BEACON_ID})) {
            assertTrue(cursor.moveToFirst());
            assertNotNull("content must survive - the backfill re-reads it", cursor.getString(0));
        }
    }

    /**
     * Multiple beacons across imports, including a soft-deleted one, all survive. Row
     * count is the thing users would notice going wrong.
     */
    @Test
    public void migrate1To2_preservesAllRowsIncludingRemoved() throws IOException {
        try (SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1)) {
            insertImport(db, 1L);
            insertImport(db, 2L);
            insertOwnedBeaconV1(db, "beacon-a", 1L, BEACON_PLIST, false);
            insertOwnedBeaconV1(db, "beacon-b", 1L, BEACON_PLIST, true);
            insertOwnedBeaconV1(db, "beacon-c", 2L, BEACON_PLIST, false);
        }

        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB, 2, true, OpenTagViewerDatabase.MIGRATION_1_2);

        try (Cursor cursor = db.query("SELECT COUNT(*) FROM OwnedBeacons")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("beacons were lost during the migration", 3, cursor.getInt(0));
        }

        try (Cursor cursor = db.query("SELECT COUNT(*) FROM OwnedBeacons WHERE is_removed = 1")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("soft-delete state was not preserved", 1, cursor.getInt(0));
        }
    }

    /**
     * An empty v1 database (installed but never imported) must also upgrade cleanly.
     */
    @Test
    public void migrate1To2_handlesEmptyDatabase() throws IOException {
        helper.createDatabase(TEST_DB, 1).close();

        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB, 2, true, OpenTagViewerDatabase.MIGRATION_1_2);

        try (Cursor cursor = db.query("SELECT COUNT(*) FROM OwnedBeacons")) {
            assertTrue(cursor.moveToFirst());
            assertEquals(0, cursor.getInt(0));
        }
    }

    /**
     * Location history is the expensive-to-rebuild data, and it is not what the migration
     * touches — so it is exactly what a careless destructive fallback would silently eat.
     */
    @Test
    public void migrate1To2_preservesLocationHistory() throws IOException {
        try (SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1)) {
            insertImport(db, 1L);
            insertOwnedBeaconV1(db, BEACON_ID, 1L, BEACON_PLIST, false);
            insertLocationReport(db, "hash-1", BEACON_ID, 1700000000000L);
            insertLocationReport(db, "hash-2", BEACON_ID, 1700000600000L);
        }

        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB, 2, true, OpenTagViewerDatabase.MIGRATION_1_2);

        try (Cursor cursor = db.query(
                "SELECT COUNT(*) FROM LocationReport WHERE beacon_id = ?", new Object[]{BEACON_ID})) {
            assertTrue(cursor.moveToFirst());
            assertEquals("location history was lost during the migration", 2, cursor.getInt(0));
        }
    }

    /**
     * v2 → v3 adds alignment_plist. Existing rows keep their data and gain a NULL column,
     * which is correct: their exports predate format 0.0.2 and have no alignment record.
     */
    @Test
    public void migrate2To3_preservesExistingBeacons() throws IOException {
        try (SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1)) {
            insertImport(db, 1L);
            insertOwnedBeaconV1(db, BEACON_ID, 1L, BEACON_PLIST, false);
        }

        // reach v2 the way a real device would, then apply the new one
        helper.runMigrationsAndValidate(TEST_DB, 2, true, OpenTagViewerDatabase.MIGRATION_1_2);
        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB, 3, true, OpenTagViewerDatabase.MIGRATION_2_3);

        try (Cursor cursor = db.query(
                "SELECT content, accessory_json, alignment_plist FROM OwnedBeacons WHERE id = ?",
                new Object[]{BEACON_ID})) {
            assertTrue("beacon row did not survive v2 to v3", cursor.moveToFirst());
            assertEquals(BEACON_PLIST, cursor.getString(0));
            assertTrue("accessory_json should still be NULL", cursor.isNull(1));
            assertTrue("alignment_plist starts NULL for pre-0.0.2 exports", cursor.isNull(2));
        }
    }

    /**
     * The case that actually reaches users: someone who never took the v2 release and
     * upgrades straight from v1. Both migrations have to run in sequence.
     */
    @Test
    public void migrate1To3_directUpgradePreservesEverything() throws IOException {
        try (SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1)) {
            insertImport(db, 1L);
            insertOwnedBeaconV1(db, "beacon-a", 1L, BEACON_PLIST, false);
            insertOwnedBeaconV1(db, "beacon-b", 1L, BEACON_PLIST, true);
            insertLocationReport(db, "hash-1", "beacon-a", 1700000000000L);
        }

        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB, 3, true,
                OpenTagViewerDatabase.MIGRATION_1_2,
                OpenTagViewerDatabase.MIGRATION_2_3);

        try (Cursor cursor = db.query("SELECT COUNT(*) FROM OwnedBeacons")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("beacons lost on a direct v1 to v3 upgrade", 2, cursor.getInt(0));
        }

        try (Cursor cursor = db.query("SELECT COUNT(*) FROM LocationReport")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("location history lost on a direct v1 to v3 upgrade", 1, cursor.getInt(0));
        }

        try (Cursor cursor = db.query(
                "SELECT accessory_json, alignment_plist FROM OwnedBeacons WHERE id = ?",
                new Object[]{"beacon-a"})) {
            assertTrue(cursor.moveToFirst());
            assertTrue(cursor.isNull(0));
            assertTrue(cursor.isNull(1));
        }
    }

    /**
     * v3 → v4 adds {@code from_account}, and every existing row must come out as a file import.
     *
     * <p><b>This is the assertion that protects everybody's tags.</b> Refreshing from the Apple
     * account deletes account beacons that are no longer on it; a pre-existing row defaulting to
     * "from the account" would therefore be deleted the first time somebody fetched - and those
     * rows are the only copy that exists, since the export they came from may be long gone.
     */
    @Test
    public void migrate3To4_existingBeaconsAreNotTreatedAsAccountBeacons() throws IOException {
        try (SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1)) {
            insertImport(db, 1L);
            insertOwnedBeaconV1(db, BEACON_ID, 1L, BEACON_PLIST, false);
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, OpenTagViewerDatabase.MIGRATION_1_2);
        helper.runMigrationsAndValidate(TEST_DB, 3, true, OpenTagViewerDatabase.MIGRATION_2_3);
        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB, 4, true, OpenTagViewerDatabase.MIGRATION_3_4);

        try (Cursor cursor = db.query(
                "SELECT content, from_account FROM OwnedBeacons WHERE id = ?",
                new Object[]{BEACON_ID})) {
            assertTrue("beacon row did not survive v3 to v4", cursor.moveToFirst());
            assertEquals(BEACON_PLIST, cursor.getString(0));
            assertEquals(
                    "an existing beacon must be a file import, or a refresh will delete it",
                    0, cursor.getInt(1));
        }
    }

    /**
     * <b>v4 to v5, from a v1 database - the path a long-standing user actually takes.</b>
     *
     * <p>Users skip releases, so the only migration that matters is the whole chain. What must
     * survive is the beacon itself; what must be true afterwards is that it looks healthy -
     * never scanned, nothing held against it, not ignored - because the alternative is an
     * upgrade that silently decides somebody's tags have stopped broadcasting and stops looking
     * for them.
     */
    @Test
    public void migrate4To5_existingBeaconsStartHealthyRatherThanIgnored() throws IOException {
        try (SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1)) {
            insertImport(db, 1L);
            insertOwnedBeaconV1(db, BEACON_ID, 1L, BEACON_PLIST, false);
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, OpenTagViewerDatabase.MIGRATION_1_2);
        helper.runMigrationsAndValidate(TEST_DB, 3, true, OpenTagViewerDatabase.MIGRATION_2_3);
        helper.runMigrationsAndValidate(TEST_DB, 4, true, OpenTagViewerDatabase.MIGRATION_3_4);
        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB, 5, true, OpenTagViewerDatabase.MIGRATION_4_5);

        try (Cursor cursor = db.query(
                "SELECT content, fruitless_scans, last_scan_at, ignored_at FROM OwnedBeacons"
                        + " WHERE id = ?",
                new Object[]{BEACON_ID})) {
            assertTrue("beacon row did not survive v4 to v5", cursor.moveToFirst());
            assertEquals(BEACON_PLIST, cursor.getString(0));
            assertEquals("an upgraded beacon must not start with strikes against it",
                    0, cursor.getInt(1));
            assertTrue("an upgraded beacon must look never-scanned", cursor.isNull(2));
            assertTrue("an upgrade must never mark somebody's tag as ignored", cursor.isNull(3));
        }
    }

    /** And a soft-deleted row still survives the whole chain, as it does at every other step. */
    @Test
    public void migrate4To5_preservesRemovedBeaconsToo() throws IOException {
        try (SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1)) {
            insertImport(db, 1L);
            insertOwnedBeaconV1(db, "beacon-a", 1L, BEACON_PLIST, false);
            insertOwnedBeaconV1(db, "beacon-b", 1L, BEACON_PLIST, true);
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, OpenTagViewerDatabase.MIGRATION_1_2);
        helper.runMigrationsAndValidate(TEST_DB, 3, true, OpenTagViewerDatabase.MIGRATION_2_3);
        helper.runMigrationsAndValidate(TEST_DB, 4, true, OpenTagViewerDatabase.MIGRATION_3_4);
        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB, 5, true, OpenTagViewerDatabase.MIGRATION_4_5);

        try (Cursor cursor = db.query("SELECT COUNT(*) FROM OwnedBeacons")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("beacons were lost during v4 to v5", 2, cursor.getInt(0));
        }
    }

    @Test
    public void migrate4To5_handlesEmptyDatabase() throws IOException {
        helper.createDatabase(TEST_DB, 1).close();

        helper.runMigrationsAndValidate(TEST_DB, 2, true, OpenTagViewerDatabase.MIGRATION_1_2);
        helper.runMigrationsAndValidate(TEST_DB, 3, true, OpenTagViewerDatabase.MIGRATION_2_3);
        helper.runMigrationsAndValidate(TEST_DB, 4, true, OpenTagViewerDatabase.MIGRATION_3_4);
        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB, 5, true, OpenTagViewerDatabase.MIGRATION_4_5);

        try (Cursor cursor = db.query("SELECT COUNT(*) FROM OwnedBeacons")) {
            assertTrue(cursor.moveToFirst());
            assertEquals(0, cursor.getInt(0));
        }
    }

    @Test
    public void migrate3To4_handlesEmptyDatabase() throws IOException {
        helper.createDatabase(TEST_DB, 1).close();

        helper.runMigrationsAndValidate(TEST_DB, 2, true, OpenTagViewerDatabase.MIGRATION_1_2);
        helper.runMigrationsAndValidate(TEST_DB, 3, true, OpenTagViewerDatabase.MIGRATION_2_3);
        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB, 4, true, OpenTagViewerDatabase.MIGRATION_3_4);

        try (Cursor cursor = db.query("SELECT COUNT(*) FROM OwnedBeacons")) {
            assertTrue(cursor.moveToFirst());
            assertEquals(0, cursor.getInt(0));
        }
    }

    /**
     * Straight from v1 to v4, which is what somebody who skipped two releases actually does.
     *
     * <p>Users skip releases, so the sequential path being right is not enough on its own.
     */
    @Test
    public void migrate1To4_directUpgradePreservesEverything() throws IOException {
        try (SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1)) {
            insertImport(db, 1L);
            insertOwnedBeaconV1(db, "beacon-a", 1L, BEACON_PLIST, false);
            insertOwnedBeaconV1(db, "beacon-b", 1L, BEACON_PLIST, true);
            insertLocationReport(db, "hash-1", "beacon-a", 1700000000000L);
        }

        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB, 4, true,
                OpenTagViewerDatabase.MIGRATION_1_2,
                OpenTagViewerDatabase.MIGRATION_2_3,
                OpenTagViewerDatabase.MIGRATION_3_4);

        try (Cursor cursor = db.query("SELECT COUNT(*) FROM OwnedBeacons")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("beacons lost on a direct v1 to v4 upgrade", 2, cursor.getInt(0));
        }

        try (Cursor cursor = db.query("SELECT COUNT(*) FROM LocationReport")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("location history lost on a direct v1 to v4 upgrade", 1, cursor.getInt(0));
        }

        try (Cursor cursor = db.query("SELECT COUNT(*) FROM OwnedBeacons WHERE from_account = 1")) {
            assertTrue(cursor.moveToFirst());
            assertEquals(
                    "nothing that existed before the account route may be marked as coming from it",
                    0, cursor.getInt(0));
        }
    }

    private static void insertImport(SupportSQLiteDatabase db, long id) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("version", "1.0");
        values.put("imported_at", 1700000000000L);
        values.put("exported_at", 1699999999000L);
        values.put("source_user", "someone@example.com");
        values.put("via", "test");
        db.insert("Import", android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values);
    }

    private static void insertOwnedBeaconV1(
            SupportSQLiteDatabase db, String id, long importId, String content, boolean isRemoved) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("import_id", importId);
        values.put("content", content);
        values.put("version", "1.0");
        values.put("is_removed", isRemoved ? 1 : 0);
        db.insert("OwnedBeacons", android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values);
    }

    private static void insertLocationReport(
            SupportSQLiteDatabase db, String hashId, String beaconId, long timestamp) {
        ContentValues values = new ContentValues();
        values.put("hash_id", hashId);
        values.put("beacon_id", beaconId);
        values.put("published_at", timestamp);
        values.put("description", "test report");
        values.put("timestamp", timestamp);
        values.put("confidence", 2);
        values.put("latitude", 52.379189);
        values.put("longitude", 4.899431);
        values.put("horizontal_accuracy", 10);
        values.put("status", 0);
        values.put("last_update", timestamp);
        db.insert("LocationReport", android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values);
    }
}
