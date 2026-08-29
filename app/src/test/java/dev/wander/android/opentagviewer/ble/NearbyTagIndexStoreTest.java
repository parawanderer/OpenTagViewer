package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
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
 * What the index derives when it already holds some of the answer.
 *
 * <p>The point of keeping derived addresses is not deriving them again, so these tests are
 * mostly about what is <i>not</i> asked for. The one that matters most is the gap: a range held
 * from an earlier session and a window that has since moved past it must end up joined, because
 * the alternative - starting over - throws away hours of widening for a tag nobody has heard,
 * which is the tag the widening was for.
 */
public class NearbyTagIndexStoreTest {

    private static final String BEACON = "A-TAG";
    private static final String JSON = "{\"accessory\":true}";

    @Rule
    public TemporaryFolder files = new TemporaryFolder();

    private DerivedAddressStore store;
    private RecordingResolver resolver;
    private NearbyTagIndex index;

    private static final class RecordingResolver implements AccessoryMacResolver {
        private final List<int[]> derived = new ArrayList<>();
        private int windowLo = 9_617;
        private int windowHi = 10_000;

        @Override
        public Map<String, Integer> currentMacAddresses(final String accessoryJson) {
            throw new AssertionError("the store path must not fall back to currentMacAddresses");
        }

        @Override
        @Nullable
        public IndexRange candidateWindow(final String accessoryJson) {
            return new IndexRange(this.windowLo, this.windowHi);
        }

        @Override
        public Map<String, Integer> addressesBetween(
                final String accessoryJson, final int lo, final int hi) {
            this.derived.add(new int[]{lo, hi});

            final Map<String, Integer> out = new HashMap<>();
            for (int i = lo; i <= hi; i++) {
                out.put(macFor(i), i);
            }
            return out;
        }
    }

    private static String macFor(final int index) {
        return String.format("AA:BB:CC:%02X:%02X:%02X",
                (index >> 16) & 0xFF, (index >> 8) & 0xFF, index & 0xFF);
    }

    @Before
    public void setUp() {
        this.store = new DerivedAddressStore(this.files.getRoot());
        this.resolver = new RecordingResolver();
        this.index = new NearbyTagIndex();
    }

    private void rebuild(final long nowMs) {
        this.index.rebuild(Map.of(BEACON, JSON), this.resolver, nowMs, this.store);
    }

    @Test
    public void anEmptyStoreDerivesTheWholeWindowAndKeepsIt() {
        this.rebuild(0L);

        assertEquals(1, this.resolver.derived.size());
        assertEquals(9_617, this.resolver.derived.get(0)[0]);
        assertEquals(10_000, this.resolver.derived.get(0)[1]);

        final DerivedAddressStore.Derived held = this.store.load(BEACON);
        assertNotNull(held);
        assertEquals(9_617, held.getLo());
        assertEquals(10_000, held.getHi());
    }

    @Test
    public void aSecondRebuildAtTheSameMomentDerivesNothing() {
        this.rebuild(0L);
        this.resolver.derived.clear();

        this.rebuild(1_000L);

        assertTrue("the window had not moved, so there was nothing to derive",
                this.resolver.derived.isEmpty());
        assertNotNull(this.index.matchFor(macFor(9_800)));
    }

    @Test
    public void onlyTheIndicesTheWindowHasMovedOnToAreDerived() {
        this.rebuild(0L);
        this.resolver.derived.clear();

        this.resolver.windowLo = 9_620;
        this.resolver.windowHi = 10_003;
        this.rebuild(1_000L);

        assertEquals(1, this.resolver.derived.size());
        assertEquals("only the three new indices at the top", 10_001, this.resolver.derived.get(0)[0]);
        assertEquals(10_003, this.resolver.derived.get(0)[1]);
    }

    /**
     * The app left closed for a few days: the window has moved past what is held, so the two no
     * longer touch. The gap must be derived along with the window, and the widened bottom kept.
     */
    @Test
    public void aWindowThatHasMovedPastWhatIsHeldJoinsUpRatherThanStartingOver() {
        this.store.save(BEACON, 400, 10_000, Map.of(macFor(400), 400));

        this.resolver.windowLo = 10_600;
        this.resolver.windowHi = 10_983;
        this.rebuild(0L);

        assertEquals(1, this.resolver.derived.size());
        assertEquals("the gap must be derived, not skipped", 10_001, this.resolver.derived.get(0)[0]);
        assertEquals(10_983, this.resolver.derived.get(0)[1]);

        final DerivedAddressStore.Derived held = this.store.load(BEACON);
        assertNotNull(held);
        assertEquals("the widened bottom must survive", 400, held.getLo());
        assertEquals(10_983, held.getHi());
        assertTrue(held.getAddresses().containsKey(macFor(400)));
        assertTrue(held.getAddresses().containsKey(macFor(10_500)));
    }

    /**
     * A tag that turns up again after a long absence is matched from the widened part of the
     * store, which is the whole reason for keeping it.
     */
    @Test
    public void anAddressFromTheWidenedPartStillMatches() {
        this.store.save(BEACON, 400, 10_000, Map.of(macFor(450), 450));

        this.resolver.windowLo = 9_617;
        this.resolver.windowHi = 10_000;
        this.rebuild(0L);

        final NearbyTagIndex.Match match = this.index.matchFor(macFor(450));

        assertNotNull("an address derived hours ago must still be matched", match);
        assertEquals(BEACON, match.getBeaconId());
        assertNull("the stored index is not kept, so there is no hint", match.getKeyIndex());
    }

    @Test
    public void aRangeGrownPastTheCapIsStartedOver() {
        this.store.save(BEACON, 0, 10_000, Map.of(macFor(0), 0));

        this.resolver.windowLo = NearbyTagIndex.MAX_STORED_INDICES + 100;
        this.resolver.windowHi = NearbyTagIndex.MAX_STORED_INDICES + 483;
        this.rebuild(0L);

        final DerivedAddressStore.Derived held = this.store.load(BEACON);
        assertNotNull(held);
        assertEquals("past the cap the oldest part is certainly dead and is dropped",
                NearbyTagIndex.MAX_STORED_INDICES + 100, held.getLo());
    }
}
