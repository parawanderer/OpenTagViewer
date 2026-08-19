package dev.wander.android.opentagviewer.db.repo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AccessoryRequest;
import dev.wander.android.opentagviewer.python.PlistToAccessoryJsonConverter;

/**
 * Covers the lazy {@code accessory_json} backfill.
 * <br>
 * Beacons imported under FindMy 0.7.6 have no accessory JSON, and the v1 → v2 migration
 * deliberately leaves the column NULL. Without a working backfill those beacons are
 * excluded from every future fetch - they simply stop updating, with nothing but a
 * logcat warning to show for it. That is a silent failure on the user's existing data,
 * so it needs covering directly.
 */
@RunWith(AndroidJUnit4.class)
public class BeaconRepositoryBackfillTest {

    private static final String PLIST = "<?xml version=\"1.0\"?><plist><dict></dict></plist>";
    private static final String CONVERTED_JSON = "{\"type\":\"accessory\",\"converted\":true}";

    private OpenTagViewerDatabase db;

    /** Records what it was asked to convert so tests can assert on call count and input. */
    private static class RecordingConverter implements PlistToAccessoryJsonConverter {
        final List<String> calls = new ArrayList<>();
        final List<String> alignmentCalls = new ArrayList<>();
        private final String result;

        RecordingConverter(String result) {
            this.result = result;
        }

        @Override
        public String convert(String plistXml, String alignmentPlistXml) {
            this.calls.add(plistXml);
            this.alignmentCalls.add(alignmentPlistXml);
            return this.result;
        }
    }

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        this.db = Room.inMemoryDatabaseBuilder(context, OpenTagViewerDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void tearDown() {
        this.db.close();
    }

    private void insertBeacon(String beaconId, String content, String accessoryJson) {
        insertBeacon(beaconId, content, accessoryJson, null);
    }

    private void insertBeacon(String beaconId, String content, String accessoryJson, String alignmentPlist) {
        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(beaconId)
                .importId(null)
                .content(content)
                .version("1.0")
                .isRemoved(false)
                .accessoryJson(accessoryJson)
                .alignmentPlist(alignmentPlist)
                .build());
    }

    private static final String CUSTOM_JSON =
            "{\"type\":\"custom_rolling_key_accessory\",\"identifier\":\"oh-1\","
            + "\"name\":\"Bike\",\"private_keys\":[\"11\"]}";

    /**
     * A self-generated tag is fetched, and it is asked for with no plist.
     *
     * <p>It has none - its keys are a flat list, and its {@code accessory_json} is written at
     * import rather than converted from anything - so the fallback branch must not run for it.
     * A converter call here would mean the app trying to parse a null plist on every refresh.
     */
    @Test
    public void aselfGeneratedTagIsRequestedWithoutAPlist() {
        insertBeacon("oh-1", null, CUSTOM_JSON);
        var converter = new RecordingConverter(CONVERTED_JSON);
        var repo = new BeaconRepository(this.db, converter);

        var requests = repo.toAccessoryRequests(
                BeaconRepository.plistFallback("oh-1", null)).blockingFirst();

        assertEquals(1, requests.size());
        assertEquals(CUSTOM_JSON, requests.get(0).getAccessoryJson());
        assertTrue("nothing should have been converted - there is no plist to convert",
                converter.calls.isEmpty());
    }

    /**
     * The bug this pair exists for, and the reason it mattered so much.
     *
     * <p>The refresh built its argument with {@code Collectors.toMap}, which throws on a null
     * value. So one self-generated tag did not merely fail to update - it took down the
     * periodic refresh for <b>every</b> tag the user owns, before a single request was made.
     */
    @Test
    public void amixOfBothKindsRefreshesTogether() {
        insertBeacon("paired-1", PLIST, null);
        insertBeacon("oh-1", null, CUSTOM_JSON);
        var converter = new RecordingConverter(CONVERTED_JSON);
        var repo = new BeaconRepository(this.db, converter);

        // Exactly the shape the map screen builds, nulls and all.
        final Map<String, String> fallback = new HashMap<>();
        fallback.put("paired-1", PLIST);
        fallback.put("oh-1", null);

        var requests = repo.toAccessoryRequests(fallback).blockingFirst();

        assertEquals("both tags must be asked for", 2, requests.size());
        assertEquals("only the paired one needs converting", 1, converter.calls.size());
    }

    /**
     * The crash a real import produced.
     *
     * <p>The import path built this map with {@code Collectors.toMap}, which throws on a null
     * value - so importing a self-generated tag ended in a {@link NullPointerException} deep in
     * the stream machinery, with nothing naming the tag or the import. It is the only kind of
     * tag that arrives here with no plist, so it was also the only way to find it.
     */
    @Test
    public void amixOfBothKindsSurvivesBeingCollected() {
        final Map<String, String> fallbacks = BeaconRepository.plistFallbacks(List.of(
                OwnedBeacon.builder().id("paired-1").content(PLIST).build(),
                OwnedBeacon.builder().id("oh-1").content(null).accessoryJson(CUSTOM_JSON).build()));

        assertEquals(2, fallbacks.size());
        assertEquals(PLIST, fallbacks.get("paired-1"));
        assertNull("a tag with no plist keeps its key and a null value", fallbacks.get("oh-1"));
        assertTrue("the key must be there, or that tag is simply never fetched",
                fallbacks.containsKey("oh-1"));
    }

    /** And an import of nothing but self-generated tags is still a map of tags. */
    @Test
    public void anImportOfOnlySelfGeneratedTagsCollectsFine() {
        final Map<String, String> fallbacks = BeaconRepository.plistFallbacks(List.of(
                OwnedBeacon.builder().id("oh-1").content(null).accessoryJson(CUSTOM_JSON).build()));

        assertEquals(1, fallbacks.size());
        assertTrue(fallbacks.containsKey("oh-1"));
    }

    /**
     * The alignment record is what stops the first fetch searching the tag's whole
     * history, so the backfill has to hand it to the converter rather than dropping it.
     */
    @Test
    public void backfillPassesTheStoredAlignmentRecordToTheConverter() {
        insertBeacon("beacon-a", PLIST, null, "ALIGNMENT-PLIST");
        var converter = new RecordingConverter(CONVERTED_JSON);
        var repo = new BeaconRepository(this.db, converter);

        repo.toAccessoryRequests(Map.of("beacon-a", PLIST)).blockingFirst();

        assertEquals(1, converter.alignmentCalls.size());
        assertEquals("ALIGNMENT-PLIST", converter.alignmentCalls.get(0));
    }

    /** Exports predating format 0.0.2 have no alignment record; that must still convert. */
    @Test
    public void backfillWorksWithoutAnAlignmentRecord() {
        insertBeacon("beacon-a", PLIST, null, null);
        var converter = new RecordingConverter(CONVERTED_JSON);
        var repo = new BeaconRepository(this.db, converter);

        var requests = repo.toAccessoryRequests(Map.of("beacon-a", PLIST)).blockingFirst();

        assertEquals(1, requests.size());
        assertNull("no alignment record should be passed as null", converter.alignmentCalls.get(0));
    }

    /** The migrated case: NULL accessory_json is converted from the retained plist and persisted. */
    @Test
    public void backfillsAndPersistsWhenAccessoryJsonIsNull() {
        insertBeacon("beacon-a", PLIST, null);
        var converter = new RecordingConverter(CONVERTED_JSON);
        var repo = new BeaconRepository(this.db, converter);

        List<AccessoryRequest> requests =
                repo.toAccessoryRequests(Map.of("beacon-a", PLIST)).blockingFirst();

        assertEquals(1, requests.size());
        assertEquals("beacon-a", requests.get(0).getBeaconId());
        assertEquals(CONVERTED_JSON, requests.get(0).getAccessoryJson());

        assertEquals("converter should have been called exactly once", 1, converter.calls.size());

        OwnedBeacon stored = this.db.ownedBeaconDao().getById("beacon-a");
        assertEquals("backfill must be persisted, not just returned", CONVERTED_JSON, stored.accessoryJson);
    }

    /** Once backfilled, later fetches must not pay for the Chaquopy round trip again. */
    @Test
    public void doesNotReconvertWhenAccessoryJsonAlreadyPresent() {
        insertBeacon("beacon-a", PLIST, CONVERTED_JSON);
        var converter = new RecordingConverter("SHOULD-NOT-BE-USED");
        var repo = new BeaconRepository(this.db, converter);

        List<AccessoryRequest> requests =
                repo.toAccessoryRequests(Map.of("beacon-a", PLIST)).blockingFirst();

        assertEquals(1, requests.size());
        assertEquals(CONVERTED_JSON, requests.get(0).getAccessoryJson());
        assertTrue("converter must not be called when the column is populated", converter.calls.isEmpty());
    }

    /**
     * Conversion failing is not permanent - Python may simply not be up yet - so the row
     * must be left NULL and retried next time, never poisoned with a bad value.
     */
    @Test
    public void skipsBeaconAndLeavesColumnNullWhenConversionFails() {
        insertBeacon("beacon-a", PLIST, null);
        var converter = new RecordingConverter(null);
        var repo = new BeaconRepository(this.db, converter);

        List<AccessoryRequest> requests =
                repo.toAccessoryRequests(Map.of("beacon-a", PLIST)).blockingFirst();

        assertTrue("unconvertible beacon must be dropped from the batch", requests.isEmpty());

        OwnedBeacon stored = this.db.ownedBeaconDao().getById("beacon-a");
        assertNull("a failed conversion must remain retryable", stored.accessoryJson);
    }

    /** One bad beacon must not take down the fetch for the others. */
    @Test
    public void oneFailedConversionDoesNotDropTheOthers() {
        insertBeacon("good-1", PLIST, CONVERTED_JSON);
        insertBeacon("bad", PLIST, null);
        insertBeacon("good-2", PLIST, CONVERTED_JSON);

        // Fails only for the beacon that needs converting.
        var converter = new RecordingConverter(null);
        var repo = new BeaconRepository(this.db, converter);

        List<AccessoryRequest> requests = repo.toAccessoryRequests(Map.of(
                "good-1", PLIST,
                "bad", PLIST,
                "good-2", PLIST
        )).blockingFirst();

        assertEquals("the two healthy beacons should still be fetched", 2, requests.size());
        for (AccessoryRequest request : requests) {
            assertNotNull(request.getAccessoryJson());
        }
    }

    /**
     * The row's retained plist is the source of truth, not the caller's map, which can be
     * stale. Guards the migration path where the caller passes whatever it had in memory.
     */
    @Test
    public void prefersStoredPlistOverCallerSuppliedOne() {
        insertBeacon("beacon-a", "STORED-PLIST", null);
        var converter = new RecordingConverter(CONVERTED_JSON);
        var repo = new BeaconRepository(this.db, converter);

        repo.toAccessoryRequests(Map.of("beacon-a", "CALLER-PLIST")).blockingFirst();

        assertEquals(1, converter.calls.size());
        assertEquals("STORED-PLIST", converter.calls.get(0));
    }

    /** A beacon that was never imported must not blow up the batch. */
    @Test
    public void handlesMissingRowGracefully() {
        var converter = new RecordingConverter(CONVERTED_JSON);
        var repo = new BeaconRepository(this.db, converter);

        List<AccessoryRequest> requests =
                repo.toAccessoryRequests(Map.of("never-imported", PLIST)).blockingFirst();

        // Falls back to the caller's plist since there is no row to read one from.
        assertEquals(1, requests.size());
        assertEquals(CONVERTED_JSON, requests.get(0).getAccessoryJson());
    }

    /** No beacons requested means no work and no Python round trip. */
    @Test
    public void emptyRequestDoesNoWork() {
        var converter = new RecordingConverter(CONVERTED_JSON);
        var repo = new BeaconRepository(this.db, converter);

        List<AccessoryRequest> requests =
                repo.toAccessoryRequests(Collections.emptyMap()).blockingFirst();

        assertTrue(requests.isEmpty());
        assertTrue(converter.calls.isEmpty());
    }

    /**
     * The whole point of issue #30: the rolling-key alignment returned by a fetch has to
     * land back in the database, or key drift re-emerges on the next fetch.
     */
    @Test
    public void storeFetchResultPersistsUpdatedAccessoryJson() {
        insertBeacon("beacon-a", PLIST, CONVERTED_JSON);
        var repo = new BeaconRepository(this.db, new RecordingConverter(null));

        final String updated = "{\"type\":\"accessory\",\"alignment_index\":4242}";
        var fetchResult = new dev.wander.android.opentagviewer.python.FetchResult(
                Collections.emptyMap(),
                Map.of("beacon-a", updated)
        );

        repo.storeFetchResult(fetchResult).blockingFirst();

        OwnedBeacon stored = this.db.ownedBeaconDao().getById("beacon-a");
        assertEquals("updated key alignment must be persisted", updated, stored.accessoryJson);
    }

    /** A null value in the fetch result must not wipe a good stored alignment. */
    @Test
    public void storeFetchResultIgnoresNullUpdates() {
        insertBeacon("beacon-a", PLIST, CONVERTED_JSON);
        var repo = new BeaconRepository(this.db, new RecordingConverter(null));

        var updates = new java.util.HashMap<String, String>();
        updates.put("beacon-a", null);
        var fetchResult = new dev.wander.android.opentagviewer.python.FetchResult(
                Collections.emptyMap(), updates);

        repo.storeFetchResult(fetchResult).blockingFirst();

        OwnedBeacon stored = this.db.ownedBeaconDao().getById("beacon-a");
        assertEquals("existing alignment must survive a null update", CONVERTED_JSON, stored.accessoryJson);
    }
}
