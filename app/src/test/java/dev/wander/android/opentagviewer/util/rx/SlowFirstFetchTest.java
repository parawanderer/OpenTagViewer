package dev.wander.android.opentagviewer.util.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Which batches are worth warning somebody about, and which are not.
 *
 * <p>The banner used to go up for any fetch that took longer than six seconds, which on a slow
 * network is nearly all of them. What makes a fetch genuinely long is how far back the key
 * search has to start, and that is known before the request is sent - so these are the cases
 * that decide it.
 */
public class SlowFirstFetchTest {

    private static final long NOW = 1_750_000_000_000L;

    private static long daysAgo(final int days) {
        return NOW - TimeUnit.DAYS.toMillis(days);
    }

    // ------------------------------------------------------------------ quick, so stay quiet

    @Test
    public void aTagAlignedYesterdayIsOneRequestAndNeedsNoBanner() {
        assertFalse("a day of keys is about 96, well inside one request",
                SlowFirstFetch.isLikely(Collections.singletonList(daysAgo(1)), NOW));
    }

    @Test
    public void aWholeBatchOfRecentlyAlignedTagsNeedsNoBanner() {
        assertFalse("every one of them resumes near where it is now",
                SlowFirstFetch.isLikely(
                        Arrays.asList(daysAgo(1), daysAgo(3), daysAgo(6)), NOW));
    }

    @Test
    public void nothingToFetchIsNotSlow() {
        assertFalse("an empty batch cannot take minutes",
                SlowFirstFetch.isLikely(Collections.emptyList(), NOW));
    }

    /** The boundary itself, stated explicitly so a change to it has to be deliberate. */
    @Test
    public void exactlyAtTheThresholdIsStillQuick() {
        assertFalse("seven days is about three requests, which finishes while you watch",
                SlowFirstFetch.isLikely(
                        Collections.singletonList(NOW - SlowFirstFetch.STALE_AFTER_MS), NOW));
    }

    // ------------------------------------------------------------------ slow, so say so

    @Test
    public void aTagWithNoAlignmentRecordAtAllIsTheSlowestCase() {
        assertTrue("with no record it starts at index 0 from the pairing date",
                SlowFirstFetch.isLikely(Collections.singletonList(null), NOW));
    }

    @Test
    public void aTagAlignedAMonthAgoIsWorthWarningAbout() {
        assertTrue("about 2,880 keys, so roughly ten sequential requests",
                SlowFirstFetch.isLikely(Collections.singletonList(daysAgo(30)), NOW));
    }

    /**
     * <b>One slow tag is enough, and this is the case the whole change turns on.</b>
     *
     * <p>The batch is fetched one accessory at a time, so a single unaligned tag holds up every
     * tag behind it. A rule of "most of them are quick" would stay silent through exactly the
     * three-minute wait the banner exists for.
     */
    @Test
    public void oneUnalignedTagAmongManyQuickOnesIsStillSlow() {
        final List<Long> batch = Arrays.asList(daysAgo(1), null, daysAgo(2));

        assertTrue("the unaligned one holds up the two behind it",
                SlowFirstFetch.isLikely(batch, NOW));
    }

    @Test
    public void knowingNothingAboutTheBatchWarnsRatherThanStaysSilent() {
        assertTrue("silence during a three-minute hang is the failure being avoided",
                SlowFirstFetch.isLikely(null, NOW));
    }

    /**
     * A clock that has gone backwards - a device whose time was wrong and got corrected - must
     * not read as "aligned in the future, so very fresh" and suppress the banner forever.
     */
    @Test
    public void anAlignmentInTheFutureIsTreatedAsFresh() {
        assertFalse("a future timestamp is nonsense, but it is not evidence of a long search",
                SlowFirstFetch.isLikely(
                        Collections.singletonList(NOW + TimeUnit.DAYS.toMillis(2)), NOW));
    }

    /**
     * The bug this pair of methods exists to stop.
     *
     * <p>A tag exported a month ago and fetched this morning is not a slow fetch, but the export's
     * record still says a month. Reading only the record showed the banner on every single
     * refresh, for anybody whose export was more than a week old, however healthy their tags.
     */
    @Test
    public void arecentFetchBeatsAnOldExportRecord() {
        final long now = 1_700_000_000_000L;
        final long thisMorning = now - TimeUnit.HOURS.toMillis(6);
        final long aMonthAgo = now - TimeUnit.DAYS.toMillis(30);

        assertFalse("the export is old, but the keys are aligned to this morning",
                SlowFirstFetch.isLikely(
                        List.of(SlowFirstFetch.laterOf(thisMorning, aMonthAgo)), now));
    }

    /** And a tag never fetched still leans on whatever the export knew. */
    @Test
    public void theexportRecordStillCountsBeforeTheFirstFetch() {
        final long now = 1_700_000_000_000L;

        assertEquals(Long.valueOf(now - TimeUnit.DAYS.toMillis(2)),
                SlowFirstFetch.laterOf(null, now - TimeUnit.DAYS.toMillis(2)));
        assertFalse(SlowFirstFetch.isLikely(
                List.of(SlowFirstFetch.laterOf(null, now - TimeUnit.DAYS.toMillis(2))), now));
    }

    /** Neither known is the slowest case there is, and must stay slow. */
    @Test
    public void neitherKnownIsStillSlow() {
        final long now = 1_700_000_000_000L;

        assertNull(SlowFirstFetch.laterOf(null, null));
        assertTrue(SlowFirstFetch.isLikely(
                Collections.singletonList(SlowFirstFetch.laterOf(null, null)), now));
    }

    /** A re-import can carry a newer record than a stale accessory blob. */
    @Test
    public void afreshReimportBeatsAStaleAccessory() {
        final long now = 1_700_000_000_000L;
        final long yesterday = now - TimeUnit.DAYS.toMillis(1);
        final long aYearAgo = now - TimeUnit.DAYS.toMillis(365);

        assertEquals(Long.valueOf(yesterday), SlowFirstFetch.laterOf(aYearAgo, yesterday));
        assertFalse(SlowFirstFetch.isLikely(List.of(SlowFirstFetch.laterOf(aYearAgo, yesterday)), now));
    }
}
