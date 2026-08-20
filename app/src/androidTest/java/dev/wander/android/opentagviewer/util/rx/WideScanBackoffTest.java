package dev.wander.android.opentagviewer.util.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

/**
 * How often a silent tag is asked again.
 *
 * <p>A tag with no key alignment record searches its whole life on every attempt, at a request
 * per ~290 keys - so the silent tags are the expensive ones, and asking them as often as the
 * healthy ones spends most of the app's conversation with Apple on the least likely answers.
 *
 * <p>Nothing here throws when it is wrong. Too eager is a quiet flood of requests; too slow is a
 * tag that has come back and goes unnoticed for days. Both are invisible without a test.
 */
@RunWith(AndroidJUnit4.class)
public class WideScanBackoffTest {

    private static final long NOON = 1_700_000_000_000L;

    /** A tag nobody has looked for yet is always worth one attempt. */
    @Test
    public void atagThatWasNeverSearchedForIsDue() {
        assertTrue(WideScanBackoff.isDue(NOON, 0, null));
    }

    /** And so is one that answered last time - no delay at all. */
    @Test
    public void atagThatAnsweredLastTimeIsNotBackedOff() {
        assertEquals(0, WideScanBackoff.waitMillisAfter(0));
        assertTrue(WideScanBackoff.isDue(NOON, 0, NOON - 1));
    }

    /**
     * <b>The first couple of silences buy nothing.</b>
     *
     * <p>A fortnight in a drawer is a normal tag having a normal week. Backing off immediately
     * would make the app slow to notice ordinary tags, which is the opposite of the point.
     */
    @Test
    public void oneortwoQuietFetchesAreNotTreatedAsEvidence() {
        assertEquals(0, WideScanBackoff.waitMillisAfter(1));
        assertTrue(WideScanBackoff.isDue(NOON, 1, NOON - 1));
    }

    /** <b>Then it lengthens.</b> Each further silence costs more than the last. */
    @Test
    public void thewaitGrowsWithEachFruitlessSearch() {
        long previous = -1;
        for (int scans = 2; scans <= 6; scans++) {
            final long wait = WideScanBackoff.waitMillisAfter(scans);
            assertTrue("wait did not grow at " + scans + " fruitless scans", wait > previous);
            previous = wait;
        }
    }

    /**
     * <b>And stops growing.</b>
     *
     * <p>A tag can always come back - a bike found, a coat out of storage - so nothing is left
     * unasked for longer than a day. Unbounded growth would mean a tag that recovered after a
     * long silence stayed invisible for as long as it had been quiet.
     */
    @Test
    public void thewaitIsCappedRatherThanGrowingForever() {
        assertEquals(WideScanBackoff.longestWaitMillis(), WideScanBackoff.waitMillisAfter(7));
        assertEquals(WideScanBackoff.longestWaitMillis(), WideScanBackoff.waitMillisAfter(500));
        assertTrue("nothing should be left unasked for more than a day",
                WideScanBackoff.longestWaitMillis() <= TimeUnit.DAYS.toMillis(1));
    }

    /** A tag inside its backoff is skipped. */
    @Test
    public void atagSearchedTooRecentlyIsNotDue() {
        final long waited = WideScanBackoff.waitMillisAfter(4);

        assertFalse(WideScanBackoff.isDue(NOON + waited - 1, 4, NOON));
        assertTrue(WideScanBackoff.isDue(NOON + waited, 4, NOON));
    }

    /**
     * <b>The backoff is time-based, so it cannot leak into a manual refresh.</b>
     *
     * <p>Somebody who opens a tag and asks gets a search however long it has been quiet - they
     * may have just found the thing. This class has no say in that: it is consulted where the
     * periodic request list is built and nowhere else, and having no state of its own is what
     * makes that impossible to get wrong by accident.
     */
    @Test
    public void ithasNoStateOfItsOwnToLeakIntoAManualRefresh() {
        final long waited = WideScanBackoff.waitMillisAfter(6);

        assertFalse(WideScanBackoff.isDue(NOON, 6, NOON));
        assertEquals("asking twice must give the same answer",
                waited, WideScanBackoff.waitMillisAfter(6));
        assertTrue(WideScanBackoff.isDue(NOON + waited, 6, NOON));
    }
}
