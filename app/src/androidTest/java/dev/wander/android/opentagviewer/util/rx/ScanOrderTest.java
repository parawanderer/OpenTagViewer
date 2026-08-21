package dev.wander.android.opentagviewer.util.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The order tags are asked about in on a scheduled fetch.
 *
 * <p><b>Order is not cosmetic here.</b> The fetch is one accessory at a time and can be abandoned
 * half way - the user closes the app - so whatever sorts last is not merely late, it is the tag
 * that is always skipped. A fixed order concentrates that cost on one unlucky tag forever.
 */
@RunWith(AndroidJUnit4.class)
public class ScanOrderTest {

    private static ScanOrder.Candidate answering(final String id) {
        return new ScanOrder.Candidate(id, true, true);
    }

    private static ScanOrder.Candidate silent(final String id) {
        return new ScanOrder.Candidate(id, true, false);
    }

    private static ScanOrder.Candidate neverScanned(final String id) {
        return new ScanOrder.Candidate(id, false, false);
    }

    /** Never scanned, but the export said when macOS last saw it. */
    private static ScanOrder.Candidate neverScannedButObserved(
            final String id, final long observedAtMillis) {
        return new ScanOrder.Candidate(id, false, false, observedAtMillis);
    }

    private static final long JANUARY = 1_704_067_200_000L;   // 2024-01-01
    private static final long JUNE = 1_719_792_000_000L;      // 2024-07-01
    private static final long DECEMBER = 1_735_689_600_000L;  // 2025-01-01

    /** <b>Tags that answered last time come first.</b> They are cheap and they change the screen. */
    @Test
    public void tagsThatAnsweredGoBeforeTagsThatDidNot() {
        final List<String> order = ScanOrder.forScheduledFetch(
                List.of(silent("quiet-1"), answering("chatty-1"), silent("quiet-2"),
                        answering("chatty-2")),
                new Random(1));

        assertEquals("the answering tags should be the first two",
                Set.of("chatty-1", "chatty-2"), new HashSet<>(order.subList(0, 2)));
        assertEquals(Set.of("quiet-1", "quiet-2"), new HashSet<>(order.subList(2, 4)));
    }

    /**
     * Tags nobody has scanned sit between them.
     *
     * <p>No evidence either way: they should not queue behind known-silent tags, and they have
     * not earned a place ahead of tags known to be answering.
     */
    @Test
    public void neverScannedTagsSitBetweenTheTwo() {
        final List<String> order = ScanOrder.forScheduledFetch(
                List.of(silent("quiet"), neverScanned("new"), answering("chatty")),
                new Random(1));

        assertEquals(List.of("chatty", "new", "quiet"), order);
    }

    /**
     * <b>On a fresh install the whole batch is simply random.</b>
     *
     * <p>Which is the right answer when nothing is known about anything - and it falls out of the
     * bucketing rather than being a special case in the code.
     */
    @Test
    public void afreshInstallIsFullyShuffled() {
        final List<ScanOrder.Candidate> tags = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tags.add(neverScanned("tag-" + i));
        }

        final Set<List<String>> seen = new HashSet<>();
        for (int seed = 0; seed < 30; seed++) {
            seen.add(ScanOrder.forScheduledFetch(tags, new Random(seed)));
        }

        assertTrue("a fresh install produced the same order every time", seen.size() > 1);
    }

    /**
     * <b>No tag is permanently last.</b>
     *
     * <p>The reason the shuffle exists. With a fixed order the same tag loses every interrupted
     * batch, for the life of the install, through no property of its own.
     */
    @Test
    public void withinAgroupNoTagIsAlwaysLast() {
        final List<ScanOrder.Candidate> tags = List.of(
                answering("a"), answering("b"), answering("c"), answering("d"));

        // **One Random across the runs, not a fresh one per seed.** Java scrambles a seed
        // weakly, so the first draw from new Random(0), new Random(1), new Random(2)... is
        // strongly correlated - and in Fisher-Yates the first draw picks the last element. The
        // first version of this used consecutive seeds and saw the same tag last forty times,
        // which said something true about java.util.Random and nothing about this code. It is
        // also what production does: the repository holds one Random for the life of the app.
        final Random random = new Random(20260821L);

        final Set<String> everLast = new HashSet<>();
        for (int run = 0; run < 40; run++) {
            final List<String> order = ScanOrder.forScheduledFetch(tags, random);
            everLast.add(order.get(order.size() - 1));
        }

        assertTrue("only " + everLast + " ever came last, so the rest are never starved",
                everLast.size() > 1);
    }

    /** Everything asked for comes back, exactly once. */
    @Test
    public void everyTagIsAskedAboutAndNoneTwice() {
        final List<ScanOrder.Candidate> tags = List.of(
                answering("a"), silent("b"), neverScanned("c"), silent("d"), answering("e"));

        final List<String> order = ScanOrder.forScheduledFetch(tags, new Random(7));

        assertEquals("a tag was dropped or duplicated", 5, order.size());
        assertEquals(Set.of("a", "b", "c", "d", "e"), new HashSet<>(order));
    }

    /** Nothing in, nothing out - and no exception on the way. */
    @Test
    public void anemptyBatchIsAnEmptyOrder() {
        assertTrue(ScanOrder.forScheduledFetch(List.of(), new Random(1)).isEmpty());
    }

    // --- what the export knows, on a first import ------------------------------------------

    /**
     * <b>The most recently observed tag is asked about first.</b>
     *
     * <p>The whole point of reading the alignment record. A tag macOS saw last week has a key
     * window of a hundred or so indices; one it last saw a year ago has tens of thousands, and
     * Apple takes about 290 keys per request. Asking in this order is the difference between a
     * pin appearing in seconds and an empty map for minutes.
     */
    @Test
    public void themostRecentlyObservedTagIsAskedAboutFirst() {
        final List<String> order = ScanOrder.forScheduledFetch(List.of(
                neverScannedButObserved("old", JANUARY),
                neverScannedButObserved("newest", DECEMBER),
                neverScannedButObserved("middling", JUNE)),
                new Random(1));

        assertEquals(List.of("newest", "middling", "old"), order);
    }

    /**
     * <b>Tags the export knew nothing about go behind the ones it did.</b>
     *
     * <p>No alignment record means the search starts at the pairing date and covers the tag's
     * whole life, which is the most expensive thing in the batch. Putting those first would
     * spend the user's first minute on the one tag least likely to answer quickly.
     */
    @Test
    public void tagsWithNoAlignmentRecordGoBehindTheOnesThatHaveOne() {
        final List<String> order = ScanOrder.forScheduledFetch(List.of(
                neverScanned("unknown-one"),
                neverScannedButObserved("observed", JANUARY),
                neverScanned("unknown-two")),
                new Random(1));

        assertEquals("a tag with an alignment record should be asked about first",
                "observed", order.get(0));
        assertTrue(order.containsAll(List.of("unknown-one", "unknown-two")));
    }

    /**
     * <b>And they are still shuffled among themselves.</b>
     *
     * <p>The reason the original group was shuffled at all, and it survives the change: the
     * batch is sequential and routinely abandoned, so a fixed order among the expensive tags
     * would leave the same one permanently last and therefore permanently unscanned. Ordering
     * the groups is fine; ordering within the last group is not.
     */
    @Test
    public void tagsWithNoAlignmentAreNotAlwaysInTheSameOrder() {
        final Set<String> everFirst = new HashSet<>();

        for (int seed = 0; seed < 50; seed++) {
            final List<String> order = ScanOrder.forScheduledFetch(List.of(
                    neverScanned("a"), neverScanned("b"), neverScanned("c")),
                    new Random(seed));
            everFirst.add(order.get(0));
        }

        assertTrue("the unaligned tags are in a fixed order, so one of them is always last and"
                + " never gets scanned at all", everFirst.size() > 1);
    }

    /**
     * <b>A real scan still beats anything a file says.</b>
     *
     * <p>The alignment date is evidence about cost, not about whether the tag answers. Once
     * there has been an actual fetch, that is better evidence - so a tag known to be answering
     * goes first even if its record is ancient, and a tag known to be silent goes last even if
     * its record is fresh.
     */
    @Test
    public void alignmentOnlyDecidesAnythingForTagsNobodyHasScannedYet() {
        final List<String> order = ScanOrder.forScheduledFetch(List.of(
                new ScanOrder.Candidate("silent-but-freshly-aligned", true, false, DECEMBER),
                neverScannedButObserved("never-scanned", JUNE),
                new ScanOrder.Candidate("answering-but-ancient", true, true, JANUARY)),
                new Random(1));

        assertEquals(List.of(
                "answering-but-ancient", "never-scanned", "silent-but-freshly-aligned"), order);
    }

    /** And nothing is lost or duplicated by the extra bucket. */
    @Test
    public void everyTagIsStillAskedAboutExactlyOnceWithAlignmentInPlay() {
        final List<String> order = ScanOrder.forScheduledFetch(List.of(
                answering("a"), silent("b"),
                neverScanned("c"), neverScannedButObserved("d", JUNE),
                neverScannedButObserved("e", JANUARY)),
                new Random(7));

        assertEquals(5, order.size());
        assertEquals(5, new HashSet<>(order).size());
        assertTrue(order.containsAll(List.of("a", "b", "c", "d", "e")));
    }
}
