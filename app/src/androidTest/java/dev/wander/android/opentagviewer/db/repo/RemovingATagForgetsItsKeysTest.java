package dev.wander.android.opentagviewer.db.repo;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;

import dev.wander.android.opentagviewer.db.repo.model.ImportData;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.DailyHistoryFetchRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.LastBleSighting;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.db.room.entity.UserBeaconOptions;
import dev.wander.android.opentagviewer.python.FetchResult;
import dev.wander.android.opentagviewer.python.icloud.AccessoryRecords;

/**
 * What removing a tag leaves behind on the phone.
 *
 * <p><b>It used to leave everything.</b> Remove only set {@code is_removed}, so the private key
 * and shared secrets of every tag ever removed stayed in the database, enough to locate the tag
 * until it was paired again. Somebody removing a tag they sold or handed back expects them gone.
 *
 * <p>The history is a different question, and the answer is the user's: kept (hidden) by default,
 * so a tag removed by mistake comes back whole when imported again, or deleted with the row when
 * they tick the box. Both paths are pinned here, along with "erase all data".
 *
 * <p>An in-memory database, never the real one: {@link BeaconRepository#eraseEverything} on the
 * on-device database would take a developer's own tags with it.
 */
@RunWith(AndroidJUnit4.class)
public class RemovingATagForgetsItsKeysTest {

    private static final String THE_TAG = "sold-tag";
    private static final String ITS_SIBLING = "kept-tag";

    private static final String A_PLIST = "<?xml version=\"1.0\"?><plist><dict>"
            + "<key>privateKey</key><data>bm90LWEtcmVhbC1rZXk=</data></dict></plist>";
    private static final String AN_ALIGNMENT = "<?xml version=\"1.0\"?><plist><dict>"
            + "<key>lastIndexObserved</key><integer>1234</integer></dict></plist>";
    private static final String ACCESSORY_JSON = "{\"type\":\"accessory\",\"master_key\":\"x\"}";
    /** What a fetch hands back: the same keys, with the alignment moved on. */
    private static final String ALIGNED_JSON =
            "{\"type\":\"accessory\",\"master_key\":\"x\",\"alignment\":5678}";

    private OpenTagViewerDatabase db;
    private BeaconRepository repo;

    @Before
    public void openAnInMemoryDatabase() {
        this.db = Room.inMemoryDatabaseBuilder(
                        getInstrumentation().getTargetContext(), OpenTagViewerDatabase.class)
                .allowMainThreadQueries()
                .build();
        this.repo = new BeaconRepository(this.db, (plist, alignment) -> ACCESSORY_JSON);
    }

    @After
    public void closeIt() {
        this.db.close();
    }

    // ---- arranging ----------------------------------------------------------------------------

    private static OwnedBeacon aTag(final String id) {
        return OwnedBeacon.builder()
                .id(id)
                .content(A_PLIST)
                .alignmentPlist(AN_ALIGNMENT)
                .accessoryJson(ACCESSORY_JSON)
                .version("0.0.2")
                .isRemoved(false)
                .fromAccount(false)
                .build();
    }

    private static BeaconNamingRecord itsName(final String id) {
        return BeaconNamingRecord.builder()
                .id(id)
                .version("0.0.2")
                .isRemoved(false)
                .content("<plist><dict><key>name</key><string>" + id + "</string></dict></plist>")
                .build();
    }

    /** One zip, holding the given tags, as the importer would store it. */
    private void importAZipOf(final String... ids) {
        final List<OwnedBeacon> beacons = new ArrayList<>();
        final List<BeaconNamingRecord> names = new ArrayList<>();
        for (final String id : ids) {
            beacons.add(aTag(id));
            names.add(itsName(id));
        }
        this.repo.addNewImport(new ImportData(
                Import.builder()
                        .version("0.0.2")
                        .importedAt(2_000L)
                        .exportedAt(1_000L)
                        .sourceUser("someone@example.invalid")
                        .exportedVia("OpenTagViewer.cli:test")
                        .build(),
                beacons,
                names)).blockingFirst();
    }

    private void givenItHasHistory(final String id) {
        this.db.locationReportDao().insertAll(LocationReport.builder()
                .hashId("report-" + id)
                .beaconId(id)
                .publishedAt(1_000L)
                .description("Wi-Fi")
                .timestamp(1_000L)
                .confidence(0)
                .latitude(52.370216)
                .longitude(4.895168)
                .horizontalAccuracy(83)
                .status(144)
                .lastUpdate(1_000L)
                .provenance(LocationReport.PROVENANCE_APPLE)
                .build());
        this.db.dailyHistoryFetchRecordDao().insertAll(DailyHistoryFetchRecord.builder()
                .beaconId(id)
                .dayStartTime(0L)
                .lastUpdate(1_000L)
                .build());
        this.db.lastBleSightingDao().insert(LastBleSighting.builder()
                .beaconId(id)
                .heardAt(1_000L)
                .batteryLevel("FULL")
                .statusByte(0)
                .build());
        this.db.userBeaconOptionsDao().insertAll(UserBeaconOptions.builder()
                .beaconId(id)
                .lastUpdate(1_000L)
                .uiName("My keys")
                .uiEmoji("🔑")
                .build());
    }

    // ---- looking ------------------------------------------------------------------------------

    /** The row as it is on disk, removed or not - the DAO's getters hide removed rows. */
    private String[] storedKeysOf(final String id) {
        try (Cursor row = this.db.query(
                "SELECT content, accessory_json, alignment_plist FROM OwnedBeacons WHERE id = ?",
                new Object[] {id})) {
            if (!row.moveToFirst()) {
                return null;
            }
            return new String[] {row.getString(0), row.getString(1), row.getString(2)};
        }
    }

    private int countWhere(final String table, final String column, final String value) {
        try (Cursor c = this.db.query(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                new Object[] {value})) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    private int count(final String table) {
        try (Cursor c = this.db.query("SELECT COUNT(*) FROM " + table, new Object[0])) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    private int historyRowsFor(final String id) {
        return countWhere("LocationReport", "beacon_id", id);
    }

    // ---- the behaviour ------------------------------------------------------------------------

    /**
     * <b>The audit finding.</b> Plain Remove deletes the key material, and keeps the history.
     */
    @Test
    public void removingDeletesTheKeysAndKeepsTheHistoryHidden() {
        this.importAZipOf(THE_TAG);
        this.givenItHasHistory(THE_TAG);

        this.repo.removeBeacon(THE_TAG, false).blockingAwait();

        final String[] keys = this.storedKeysOf(THE_TAG);
        assertNotNull("the row should stay, hidden, so its history has something to belong to",
                keys);
        assertNull("the exported plist (the private key) is still stored", keys[0]);
        assertNull("FindMy.py's accessory state (the same keys) is still stored", keys[1]);
        assertNull("the key alignment record is still stored", keys[2]);

        assertNull("a removed tag must not be listed", this.db.ownedBeaconDao().getById(THE_TAG));
        assertEquals("plain Remove should keep the history, as it always has",
                1, this.historyRowsFor(THE_TAG));
        assertNotNull("plain Remove should keep the user's name for it",
                this.db.userBeaconOptionsDao().getById(THE_TAG));
    }

    /**
     * Keeping the history is only worth it if importing the tag again brings it back, keys and
     * all. This is the behaviour plain Remove exists to preserve.
     */
    @Test
    public void importingARemovedTagAgainBringsBackItsKeysAndItsHistory() {
        this.importAZipOf(THE_TAG);
        this.givenItHasHistory(THE_TAG);
        this.repo.removeBeacon(THE_TAG, false).blockingAwait();

        this.importAZipOf(THE_TAG);

        final OwnedBeacon back = this.db.ownedBeaconDao().getById(THE_TAG);
        assertNotNull("the re-imported tag should be listed again", back);
        assertEquals(A_PLIST, back.content);
        assertEquals(AN_ALIGNMENT, back.alignmentPlist);
        assertEquals(ACCESSORY_JSON, back.accessoryJson);
        assertEquals("its history should have come back with it", 1, this.historyRowsFor(THE_TAG));
    }

    /** Ticking the box deletes the row and everything that hangs off it. */
    @Test
    public void removingWithTheHistoryDeletesEverythingAboutTheTag() {
        this.importAZipOf(THE_TAG);
        this.givenItHasHistory(THE_TAG);

        this.repo.removeBeacon(THE_TAG, true).blockingAwait();

        assertNull("the row itself, keys included, should be gone", this.storedKeysOf(THE_TAG));
        assertEquals("location history", 0, this.historyRowsFor(THE_TAG));
        assertEquals("fetched-day records",
                0, this.countWhere("DailyHistoryFetchRecord", "beacon_id", THE_TAG));
        assertEquals("last Bluetooth sighting",
                0, this.countWhere("LastBleSighting", "beacon_id", THE_TAG));
        assertEquals("the user's name and emoji",
                0, this.countWhere("UserBeaconOptions", "beacon_id", THE_TAG));
        assertEquals("the naming record from the export",
                0, this.countWhere("BeaconNamingRecord", "id", THE_TAG));
        assertEquals("the import, which only held this tag, still records who exported it",
                0, this.count("Import"));
    }

    /**
     * Erasing one tag of a zip leaves the rest of that zip alone, its import row included -
     * deleting the import would cascade into the other tags.
     */
    @Test
    public void erasingOneTagLeavesTheOthersFromTheSameZip() {
        this.importAZipOf(THE_TAG, ITS_SIBLING);
        this.givenItHasHistory(THE_TAG);
        this.givenItHasHistory(ITS_SIBLING);

        this.repo.removeBeacon(THE_TAG, true).blockingAwait();

        assertNotNull("the other tag from the same zip was taken too",
                this.db.ownedBeaconDao().getById(ITS_SIBLING));
        assertEquals(1, this.historyRowsFor(ITS_SIBLING));
        assertEquals("the import still has a tag, so it stays", 1, this.count("Import"));
        assertFalse(this.db.beaconNamingRecordDao().getAll().isEmpty());
    }

    /**
     * A removed tag still points at its import, so erasing its sibling must not delete that
     * import: it would cascade into the hidden row, and the history plain Remove promised to keep.
     */
    @Test
    public void anImportAHiddenTagStillPointsAtIsKept() {
        this.importAZipOf(THE_TAG, ITS_SIBLING);
        this.givenItHasHistory(ITS_SIBLING);
        this.repo.removeBeacon(ITS_SIBLING, false).blockingAwait();

        this.repo.removeBeacon(THE_TAG, true).blockingAwait();

        assertEquals(1, this.count("Import"));
        assertEquals("the hidden tag's history went with an import that should have stayed",
                1, this.historyRowsFor(ITS_SIBLING));
    }

    /**
     * Multi-select removes several tags in one call, so the file is compacted once. It must do to
     * each tag exactly what removing it alone does.
     */
    @Test
    public void removingSeveralAtOnceForgetsEveryOnesKeys() {
        this.importAZipOf(THE_TAG, ITS_SIBLING);
        this.givenItHasHistory(THE_TAG);
        this.givenItHasHistory(ITS_SIBLING);

        this.repo.removeBeacons(List.of(THE_TAG, ITS_SIBLING), false).blockingAwait();

        for (final String id : new String[] {THE_TAG, ITS_SIBLING}) {
            final String[] keys = this.storedKeysOf(id);
            assertNotNull(id + " should stay, hidden", keys);
            assertNull(id + "'s plist is still stored", keys[0]);
            assertNull(id + "'s accessory state is still stored", keys[1]);
            assertNull(id + "'s alignment record is still stored", keys[2]);
            assertEquals(id + "'s history should be kept", 1, this.historyRowsFor(id));
        }
    }

    /** And with the box ticked, the import goes once the last of its tags has. */
    @Test
    public void erasingAWholeZipAtOnceDeletesItsImport() {
        this.importAZipOf(THE_TAG, ITS_SIBLING);
        this.givenItHasHistory(THE_TAG);
        this.givenItHasHistory(ITS_SIBLING);

        this.repo.removeBeacons(List.of(THE_TAG, ITS_SIBLING), true).blockingAwait();

        assertNull(this.storedKeysOf(THE_TAG));
        assertNull(this.storedKeysOf(ITS_SIBLING));
        assertEquals(0, this.count("LocationReport"));
        assertEquals(0, this.count("BeaconNamingRecord"));
        assertEquals("the import outlived every tag it held", 0, this.count("Import"));
    }

    /**
     * A tag from a zip that later arrived from the Apple account has its row moved off the import,
     * but keeps the zip's naming record when the account holds none. Erasing the other tag from
     * that zip must not delete the import, which would cascade into that naming record and take
     * the account tag's name with it.
     */
    @Test
    public void anImportANamingRecordStillPointsAtIsKept() {
        this.importAZipOf(THE_TAG, ITS_SIBLING);
        // (beaconId, ownedBeaconPlist, namingRecordPlist, keyAlignmentPlist): no naming record.
        this.repo.refreshAccountBeacons(
                List.of(new AccessoryRecords(ITS_SIBLING, A_PLIST, null, AN_ALIGNMENT)))
                .blockingFirst();

        this.repo.removeBeacon(THE_TAG, true).blockingAwait();

        assertNotNull("the account tag lost the name it had from the zip",
                this.db.beaconNamingRecordDao().getByBeaconId(ITS_SIBLING));
        assertEquals("the import still has a naming record, so it stays", 1, this.count("Import"));
    }

    /**
     * <b>A fetch already running when the tag was removed.</b> It lands afterwards carrying the
     * tag's freshly aligned accessory JSON, which is the same keys Remove has just deleted. It must
     * not write them back onto the hidden row; the tag still listed beside it is updated as usual.
     */
    @Test
    public void aFetchThatLandsAfterRemovalDoesNotWriteTheKeysBack() {
        this.importAZipOf(THE_TAG, ITS_SIBLING);
        this.repo.removeBeacon(THE_TAG, false).blockingAwait();

        this.repo.storeFetchResult(new FetchResult(
                Map.of(THE_TAG, List.of(), ITS_SIBLING, List.of()),
                Map.of(THE_TAG, ALIGNED_JSON, ITS_SIBLING, ALIGNED_JSON),
                Set.of(), Set.of())).blockingFirst();

        assertNull("a fetch that was in flight put the removed tag's keys back",
                this.storedKeysOf(THE_TAG)[1]);
        assertEquals("the tag that was not removed should still get its new alignment",
                ALIGNED_JSON, this.storedKeysOf(ITS_SIBLING)[1]);
    }

    /**
     * The same for a Bluetooth sighting, which reads the row before deriving and writes after,
     * so a removal can land in between. {@code recordAccessorySighting} ends in this write.
     */
    @Test
    public void aSightingThatLandsAfterRemovalDoesNotWriteTheKeysBack() {
        this.importAZipOf(THE_TAG);
        this.repo.removeBeacon(THE_TAG, false).blockingAwait();

        this.db.ownedBeaconDao().updateAccessoryJson(THE_TAG, ALIGNED_JSON);

        assertNull(this.storedKeysOf(THE_TAG)[1]);
    }

    /**
     * A fetch that lands after its tag was erased has reports for a row that no longer exists.
     * One such report fails {@code LocationReport}'s foreign key and with it the whole insert, so
     * the other tags fetched alongside lost their reports too.
     */
    @Test
    public void aFetchThatLandsAfterTheTagWasErasedStillStoresTheOthers() {
        this.importAZipOf(THE_TAG, ITS_SIBLING);
        this.repo.removeBeacon(THE_TAG, true).blockingAwait();

        final List<BeaconLocationReport> aReport = List.of(BeaconLocationReport.builder()
                .timestamp(5_000L).publishedAt(5_000L).description("somewhere")
                .latitude(52.370216).longitude(4.895168).build());
        this.repo.storeFetchResult(new FetchResult(
                Map.of(THE_TAG, aReport, ITS_SIBLING, aReport),
                Map.of(), Set.of(), Set.of())).blockingFirst();

        assertEquals("the erased tag's report should be dropped", 0, this.historyRowsFor(THE_TAG));
        assertEquals("the other tag's report went down with the erased one's",
                1, this.historyRowsFor(ITS_SIBLING));
        assertNull("the erased tag came back", this.storedKeysOf(THE_TAG));
    }

    /** Settings -> Erase all data, as far as the database goes. */
    @Test
    public void erasingEverythingEmptiesEveryTable() {
        this.importAZipOf(THE_TAG, ITS_SIBLING);
        this.givenItHasHistory(THE_TAG);
        this.repo.removeBeacon(ITS_SIBLING, false).blockingAwait();

        this.repo.eraseEverything().blockingAwait();

        for (final String table : new String[] {"OwnedBeacons", "Import", "BeaconNamingRecord",
                "LocationReport", "DailyHistoryFetchRecord", "LastBleSighting",
                "UserBeaconOptions"}) {
            assertEquals(table + " still has rows after erasing everything", 0, this.count(table));
        }
        assertTrue(this.repo.getAllBeacons().blockingFirst().isEmpty());
    }
}
