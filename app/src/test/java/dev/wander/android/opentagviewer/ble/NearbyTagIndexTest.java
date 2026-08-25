package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
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

    /** The candidate map shape currentMacAddresses returns; this class only reads its keys, so
     * the indices are arbitrary placeholders. */
    private static Map<String, Integer> macs(final String... addresses) {
        final Map<String, Integer> byMac = new HashMap<>();
        for (int i = 0; i < addresses.length; i++) {
            byMac.put(addresses[i], i);
        }
        return byMac;
    }

    /** Answers a different address set per accessory, so a mix-up between tags would show. */
    private static AccessoryMacResolver resolverFor(final Map<String, Map<String, Integer>> byJson) {
        return json -> byJson.getOrDefault(json, Map.of());
    }

    @Test
    public void mapsEveryCandidateAddressBackToItsTag() {
        final Map<String, Map<String, Integer>> answers = new HashMap<>();
        answers.put("{\"type\":\"accessory\",\"tag\":\"keys\"}",
                macs("AA:AA:AA:AA:AA:01", "AA:AA:AA:AA:AA:02"));
        answers.put("{\"type\":\"accessory\",\"tag\":\"bike\"}",
                macs("BB:BB:BB:BB:BB:01"));

        final NearbyTagIndex index = new NearbyTagIndex();
        index.rebuild(twoTags(), resolverFor(answers), 0L);

        assertEquals(3, index.size());
        assertEquals(KEYS, index.matchFor("AA:AA:AA:AA:AA:01").getBeaconId());
        assertEquals(KEYS, index.matchFor("AA:AA:AA:AA:AA:02").getBeaconId());
        assertEquals(BIKE, index.matchFor("BB:BB:BB:BB:BB:01").getBeaconId());
    }

    @Test
    public void anAddressThatIsNotOursResolvesToNothing() {
        final NearbyTagIndex index = new NearbyTagIndex();
        index.rebuild(twoTags(), resolverFor(Map.of()), 0L);

        assertNull(index.matchFor("CC:CC:CC:CC:CC:CC"));
        assertNull(index.matchFor(null));
    }

    /** Neither side promises a casing forever, and a casing mismatch would present as
     * "the tag is never nearby" rather than as anything failing. */
    @Test
    public void matchingIgnoresCase() {
        final NearbyTagIndex index = new NearbyTagIndex();
        index.rebuild(Map.of(KEYS, "j"), resolverFor(Map.of("j", macs("aa:bb:cc:dd:ee:ff"))), 0L);

        assertEquals(KEYS, index.matchFor("AA:BB:CC:DD:EE:FF").getBeaconId());
        assertEquals(KEYS, index.matchFor("aa:bb:cc:dd:ee:ff").getBeaconId());
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
        index.rebuild(Map.of(KEYS, "j"), resolverFor(Map.of("j", macs("AA:AA:AA:AA:AA:01"))), 0L);
        index.rebuild(Map.of(KEYS, "j"), resolverFor(Map.of("j", macs("AA:AA:AA:AA:AA:99"))), 1L);

        assertEquals(1, index.size());
        assertNull("a rolled-past address must stop matching", index.matchFor("AA:AA:AA:AA:AA:01"));
        assertEquals(KEYS, index.matchFor("AA:AA:AA:AA:AA:99").getBeaconId());
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
        index.rebuild(tags, resolverFor(Map.of("good", macs("AA:AA:AA:AA:AA:01"))), 0L);

        assertEquals(KEYS, index.matchFor("AA:AA:AA:AA:AA:01").getBeaconId());
        assertEquals(1, index.size());
    }

    /**
     * The resolver's documented way of saying "not from me": null, not an empty map.
     *
     * <p><b>This is the one that got out.</b> Turning on "show my own Apple devices" put a
     * phone in the list, and a phone has no rolling-key alignment, so the resolver refused it -
     * correctly. The refusal was then dereferenced, the throw killed the whole rebuild, and the
     * scan never started: every real tag stopped being seen, with nothing failing anywhere to
     * say why. One entry may only ever cost its own sightings.
     */
    @Test
    public void aTagTheResolverRefusesDoesNotCostTheOthers() {
        final Map<String, String> tags = new HashMap<>();
        tags.put(KEYS, "good");
        tags.put(BIKE, "refused");

        final AccessoryMacResolver refusesOne = json ->
                "good".equals(json) ? macs("AA:AA:AA:AA:AA:01") : null;

        final NearbyTagIndex index = new NearbyTagIndex();
        index.rebuild(tags, refusesOne, 0L);

        assertEquals(KEYS, index.matchFor("AA:AA:AA:AA:AA:01").getBeaconId());
        assertEquals(1, index.size());
    }

    /** And when every tag is refused, that is an empty index rather than a thrown rebuild. */
    @Test
    public void aResolverThatRefusesEverythingLeavesAnEmptyIndexRatherThanThrowing() {
        final NearbyTagIndex index = new NearbyTagIndex();

        index.rebuild(twoTags(), json -> null, 5_000L);

        assertEquals(0, index.size());
        assertFalse("a rebuild that ran must count as built, or it repeats every scan result",
                index.isStale(5_000L));
    }

    /**
     * The index the address was derived at is carried, not discarded.
     *
     * <p>It is the hint that lets the alignment correction check one index instead of
     * re-deriving a 48-hour window - three key derivations against about 1150. Dropping it here
     * is what made that correction expensive enough to get the app killed for not answering
     * input, and nothing would have failed to say so.
     */
    @Test
    public void eachAddressRemembersTheIndexItWasDerivedAt() {
        final Map<String, Integer> byMac = new HashMap<>();
        byMac.put("AA:AA:AA:AA:AA:01", 6221);
        byMac.put("AA:AA:AA:AA:AA:02", 6222);

        final NearbyTagIndex index = new NearbyTagIndex();
        index.rebuild(Map.of(KEYS, "j"), resolverFor(Map.of("j", byMac)), 0L);

        assertEquals(6221, index.matchFor("AA:AA:AA:AA:AA:01").getKeyIndex());
        assertEquals(6222, index.matchFor("AA:AA:AA:AA:AA:02").getKeyIndex());
    }

    @Test
    public void resolvesEachTagExactlyOncePerRebuild() {
        final AtomicInteger calls = new AtomicInteger();
        final AccessoryMacResolver counting = json -> {
            calls.incrementAndGet();
            return Map.of();
        };

        new NearbyTagIndex().rebuild(twoTags(), counting, 0L);

        assertEquals("one interpreter start per tag, not per address", 2, calls.get());
    }
}
