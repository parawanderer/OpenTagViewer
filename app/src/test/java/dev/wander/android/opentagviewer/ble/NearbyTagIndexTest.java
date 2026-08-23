package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import dev.wander.android.opentagviewer.python.AccessoryMacResolver;

/** A JVM test: {@link NearbyTagIndex} has no Android and no Bluetooth in it, deliberately. */
public class NearbyTagIndexTest {

    private static final String KEYS = "keys-beacon-id";
    private static final String BIKE = "bike-beacon-id";

    private static Map<String, String> twoTags() {
        final Map<String, String> tags = new HashMap<>();
        tags.put(KEYS, "{\"type\":\"accessory\",\"tag\":\"keys\"}");
        tags.put(BIKE, "{\"type\":\"accessory\",\"tag\":\"bike\"}");
        return tags;
    }

    /** Answers a different address set per accessory, so a mix-up between tags would show. */
    private static AccessoryMacResolver resolverFor(final Map<String, List<String>> byJson) {
        return json -> byJson.getOrDefault(json, List.of());
    }

    @Test
    public void mapsEveryCandidateAddressBackToItsTag() {
        final Map<String, List<String>> answers = new HashMap<>();
        answers.put("{\"type\":\"accessory\",\"tag\":\"keys\"}",
                List.of("AA:AA:AA:AA:AA:01", "AA:AA:AA:AA:AA:02"));
        answers.put("{\"type\":\"accessory\",\"tag\":\"bike\"}",
                List.of("BB:BB:BB:BB:BB:01"));

        final NearbyTagIndex index = new NearbyTagIndex();
        index.rebuild(twoTags(), resolverFor(answers), 0L);

        assertEquals(3, index.size());
        assertEquals(KEYS, index.beaconIdFor("AA:AA:AA:AA:AA:01"));
        assertEquals(KEYS, index.beaconIdFor("AA:AA:AA:AA:AA:02"));
        assertEquals(BIKE, index.beaconIdFor("BB:BB:BB:BB:BB:01"));
    }

    @Test
    public void anAddressThatIsNotOursResolvesToNothing() {
        final NearbyTagIndex index = new NearbyTagIndex();
        index.rebuild(twoTags(), resolverFor(Map.of()), 0L);

        assertNull(index.beaconIdFor("CC:CC:CC:CC:CC:CC"));
        assertNull(index.beaconIdFor(null));
    }

    /** Neither side promises a casing forever, and a casing mismatch would present as
     * "the tag is never nearby" rather than as anything failing. */
    @Test
    public void matchingIgnoresCase() {
        final NearbyTagIndex index = new NearbyTagIndex();
        index.rebuild(Map.of(KEYS, "j"), resolverFor(Map.of("j", List.of("aa:bb:cc:dd:ee:ff"))), 0L);

        assertEquals(KEYS, index.beaconIdFor("AA:BB:CC:DD:EE:FF"));
        assertEquals(KEYS, index.beaconIdFor("aa:bb:cc:dd:ee:ff"));
    }

    // --- expiry -------------------------------------------------------------------------------

    @Test
    public void aFreshlyConstructedIndexIsStale() {
        assertTrue(new NearbyTagIndex().isStale(0L));
    }

    @Test
    public void staysFreshInsideTheWindowAndExpiresAtIt() {
        final NearbyTagIndex index = new NearbyTagIndex();
        index.rebuild(Map.of(KEYS, "j"), resolverFor(Map.of()), 1_000L);

        assertFalse(index.isStale(1_000L));
        assertFalse(index.isStale(1_000L + NearbyTagIndex.MAX_AGE_MS - 1));
        assertTrue("must expire before the 15 minute rollover, or sightings are missed",
                index.isStale(1_000L + NearbyTagIndex.MAX_AGE_MS));
    }

    @Test
    public void expiryIsShorterThanTheRolloverInterval() {
        assertTrue("an index older than a rollover predicts addresses nothing is sending any more",
                NearbyTagIndex.MAX_AGE_MS < java.util.concurrent.TimeUnit.MINUTES.toMillis(15));
    }

    // --- rebuilding ---------------------------------------------------------------------------

    @Test
    public void rebuildingReplacesTheOldAddressesRatherThanAccumulating() {
        final NearbyTagIndex index = new NearbyTagIndex();
        index.rebuild(Map.of(KEYS, "j"), resolverFor(Map.of("j", List.of("AA:AA:AA:AA:AA:01"))), 0L);
        index.rebuild(Map.of(KEYS, "j"), resolverFor(Map.of("j", List.of("AA:AA:AA:AA:AA:99"))), 1L);

        assertEquals(1, index.size());
        assertNull("a rolled-past address must stop matching", index.beaconIdFor("AA:AA:AA:AA:AA:01"));
        assertEquals(KEYS, index.beaconIdFor("AA:AA:AA:AA:AA:99"));
    }

    /**
     * A tag whose accessory JSON has not been backfilled yet resolves to nothing. It must cost
     * only its own sightings, not the whole rebuild.
     */
    @Test
    public void oneUnresolvableTagDoesNotCostTheOthers() {
        final Map<String, String> tags = new HashMap<>();
        tags.put(KEYS, "good");
        tags.put(BIKE, "unbackfilled");

        final NearbyTagIndex index = new NearbyTagIndex();
        index.rebuild(tags, resolverFor(Map.of("good", List.of("AA:AA:AA:AA:AA:01"))), 0L);

        assertEquals(KEYS, index.beaconIdFor("AA:AA:AA:AA:AA:01"));
        assertEquals(1, index.size());
    }

    @Test
    public void resolvesEachTagExactlyOncePerRebuild() {
        final AtomicInteger calls = new AtomicInteger();
        final AccessoryMacResolver counting = json -> {
            calls.incrementAndGet();
            return List.of();
        };

        new NearbyTagIndex().rebuild(twoTags(), counting, 0L);

        assertEquals("one interpreter start per tag, not per address", 2, calls.get());
    }
}
