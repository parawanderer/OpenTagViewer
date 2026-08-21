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
}
