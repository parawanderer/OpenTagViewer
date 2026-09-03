package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.Nullable;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.wander.android.opentagviewer.python.AccessoryMacResolver;

/**
 * The rule for looking further back, without a radio, a phone or a key derivation.
 *
 * <p>What matters here is not that it derives, but when it refuses to: while the app is still
 * starting up, for a tag that was just heard, past the point it is willing to look, and after a
 * derivation that failed. Each of those, got wrong, is either wasted battery or a range recorded
 * as covered that nothing ever looked at.
 */
public class WideningSearchTest {

    private static final String NEAR = "TAG-THAT-IS-HERE";
    private static final String MISSING = "TAG-NOBODY-HAS-HEARD";
    private static final String JSON = "{\"accessory\":true}";

    @Rule
    public TemporaryFolder files = new TemporaryFolder();

    private DerivedAddressStore store;
    private RecordingResolver resolver;
    private WideningSearch search;

    /** Records what it was asked for, and answers with addresses named after the range. */
    private static final class RecordingResolver implements AccessoryMacResolver {
        private final List<int[]> derivedRanges = new ArrayList<>();
        private int windowHi = 10_000;
        private boolean deriveNothing = false;
        private boolean noWindow = false;

        @Override
        public Map<String, Integer> currentMacAddresses(final String accessoryJson) {
            return Map.of();
        }

        @Override
        @Nullable
        public IndexRange candidateWindow(final String accessoryJson) {
            return this.noWindow ? null : new IndexRange(this.windowHi - 383, this.windowHi);
        }

        @Override
        public Map<String, Integer> addressesBetween(
                final String accessoryJson, final int lo, final int hi) {
            this.derivedRanges.add(new int[]{lo, hi});

            if (this.deriveNothing) {
                return Map.of();
            }

            final Map<String, Integer> out = new HashMap<>();
            for (int i = lo; i <= hi; i++) {
                out.put(String.format("AA:BB:CC:%02X:%02X:%02X",
                        (i >> 16) & 0xFF, (i >> 8) & 0xFF, i & 0xFF), i);
            }
            return out;
        }
    }

    @Before
    public void setUp() {
        this.store = new DerivedAddressStore(this.files.getRoot());
        this.resolver = new RecordingResolver();
        this.search = new WideningSearch(this.resolver, this.store);
    }

    /** A tag whose derived range starts at {@code lo} and reaches the top of the window. */
    private void alreadyDerived(final String beaconId, final int lo) {
        final Map<String, Integer> addresses = new HashMap<>();
        addresses.put("AA:BB:CC:DD:EE:FF", lo);
        this.store.save(beaconId, lo, 10_000, addresses);
    }

    private static Map<String, String> tags(final String... beaconIds) {
        final Map<String, String> out = new HashMap<>();
        for (final String beaconId : beaconIds) {
            out.put(beaconId, JSON);
        }
        return out;
    }

    @Test
    public void nothingIsDueBeforeTheWatchHasEvenStarted() {
        assertFalse(this.search.isDue(1_000_000L));
    }

    @Test
    public void nothingIsDueDuringTheWarmUp() {
        this.search.started(0L);

        assertFalse("deriving during startup is the one thing this must not do",
                this.search.isDue(WideningSearch.WARM_UP_MS - 1));
    }

    @Test
    public void aRoundIsDueOnceTheWarmUpHasPassed() {
        this.search.started(0L);

        assertTrue(this.search.isDue(WideningSearch.WARM_UP_MS));
    }

    @Test
    public void roundsAreSpacedOut() {
        this.search.started(0L);
        final long first = WideningSearch.WARM_UP_MS;

        this.search.widenOne(tags(MISSING), Map.of(), first);

        assertFalse(this.search.isDue(first + WideningSearch.BETWEEN_ROUNDS_MS - 1));
        assertTrue(this.search.isDue(first + WideningSearch.BETWEEN_ROUNDS_MS));
    }

    @Test
    public void aTagHeardJustNowIsLeftAlone() {
        this.alreadyDerived(NEAR, 9_000);

        final Map<String, Long> heard = new HashMap<>();
        heard.put(NEAR, 500_000L);

        assertNull(this.search.widenOne(tags(NEAR), heard, 500_000L + 1000L));
        assertTrue(this.resolver.derivedRanges.isEmpty());
    }

    @Test
    public void aTagNotHeardForLongEnoughIsWidened() {
        this.alreadyDerived(MISSING, 9_000);

        final Map<String, Long> heard = new HashMap<>();
        heard.put(MISSING, 0L);

        assertEquals(MISSING,
                this.search.widenOne(tags(MISSING), heard, WideningSearch.HEARD_RECENTLY_MS));

        assertEquals(1, this.resolver.derivedRanges.size());
        assertEquals(9_000 - WideningSearch.CHUNK_INDICES, this.resolver.derivedRanges.get(0)[0]);
        assertEquals(8_999, this.resolver.derivedRanges.get(0)[1]);
    }

    @Test
    public void aTagNeverHeardAtAllIsWidened() {
        this.alreadyDerived(MISSING, 9_000);

        assertEquals(MISSING, this.search.widenOne(tags(MISSING), Map.of(), 1_000_000L));
    }

    @Test
    public void theStoredRangeGrowsDownwardAndKeepsWhatItHad() {
        this.alreadyDerived(MISSING, 9_000);

        this.search.widenOne(tags(MISSING), Map.of(), 1_000_000L);

        final DerivedAddressStore.Derived held = this.store.load(MISSING);
        assertNotNull(held);
        assertEquals(9_000 - WideningSearch.CHUNK_INDICES, held.getLo());
        assertEquals(10_000, held.getHi());
        assertTrue("what was already held must survive",
                held.getAddresses().containsKey("AA:BB:CC:DD:EE:FF"));
    }

    @Test
    public void roundsResumeWhereTheLastOneStopped() {
        this.alreadyDerived(MISSING, 9_000);

        this.search.widenOne(tags(MISSING), Map.of(), 1_000_000L);
        this.search.widenOne(tags(MISSING), Map.of(), 2_000_000L);

        assertEquals(2, this.resolver.derivedRanges.size());
        assertEquals(9_000 - 2 * WideningSearch.CHUNK_INDICES,
                this.resolver.derivedRanges.get(1)[0]);
        assertEquals(9_000 - WideningSearch.CHUNK_INDICES - 1,
                this.resolver.derivedRanges.get(1)[1]);
    }

    @Test
    public void itStopsAtTheFloorRatherThanRunningToZero() {
        final int floor = 10_000 - WideningSearch.TARGET_INDICES;
        this.alreadyDerived(MISSING, floor + 10);

        assertEquals(MISSING, this.search.widenOne(tags(MISSING), Map.of(), 1_000_000L));
        assertEquals(floor, this.store.load(MISSING).getLo());

        assertNull("already as far back as it will look",
                this.search.widenOne(tags(MISSING), Map.of(), 2_000_000L));
    }

    @Test
    public void aTagWithNothingDerivedYetIsLeftToTheOrdinaryRebuild() {
        assertNull(this.search.widenOne(tags(MISSING), Map.of(), 1_000_000L));
        assertTrue(this.resolver.derivedRanges.isEmpty());
    }

    @Test
    public void anUnreadableAccessoryIsSkipped() {
        this.alreadyDerived(MISSING, 9_000);
        this.resolver.noWindow = true;

        assertNull(this.search.widenOne(tags(MISSING), Map.of(), 1_000_000L));
    }

    /**
     * The failure that would not announce itself: recording indices as covered that were never
     * derived means nothing ever goes back for them, and the tag stays unfindable for a reason
     * no log would show.
     */
    @Test
    public void aFailedDerivationDoesNotAdvanceTheStoredRange() {
        this.alreadyDerived(MISSING, 9_000);
        this.resolver.deriveNothing = true;

        assertNull(this.search.widenOne(tags(MISSING), Map.of(), 1_000_000L));
        assertEquals(9_000, this.store.load(MISSING).getLo());
    }

    @Test
    public void onlyOneTagIsWidenedPerRound() {
        this.alreadyDerived(NEAR, 9_000);
        this.alreadyDerived(MISSING, 9_000);

        this.search.widenOne(tags(NEAR, MISSING), Map.of(), 1_000_000L);

        assertEquals("a round must not cost more because somebody owns more tags",
                1, this.resolver.derivedRanges.size());
    }

    @Test
    public void theTagsWorthWideningAreTheOnesNotHeardRecently() {
        final Map<String, Long> heard = new HashMap<>();
        heard.put(NEAR, 1_000_000L);
        heard.put(MISSING, 1_000_000L - WideningSearch.HEARD_RECENTLY_MS);

        assertEquals(java.util.Set.of(MISSING),
                WideningSearch.notHeardRecently(
                        java.util.Set.of(NEAR, MISSING), heard, 1_000_000L));
    }
}
