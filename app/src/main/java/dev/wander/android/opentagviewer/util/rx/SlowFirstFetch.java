package dev.wander.android.opentagviewer.util.rx;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Whether a batch of tags is one that will take minutes rather than seconds.
 *
 * <p><b>The banner was showing for fetches that finish immediately.</b> "Still locating your
 * tags (2 of 3)" is worth saying when the wait is genuinely long, and is noise otherwise - and a
 * message that appears when nothing is wrong is one people learn to ignore before the day it
 * matters. It went up for every fetch that passed six seconds, which on a slow network is most
 * of them.
 *
 * <p><b>What actually makes a fetch long is the key search, and that is knowable in advance.</b>
 * An accessory is located by deriving the rotating keys it would have published and asking
 * Apple's network about them. Where the search starts is set by the {@code KeyAlignmentRecord}
 * in the export: with a recent one the app resumes near where the tag is now, and with none at
 * all {@code FindMyAccessory} starts at index 0 from the pairing date and searches the tag's
 * whole life - see AGENTS.md rule 6, which is about the same records and why both paths have to
 * keep working.
 *
 * <p>Keys advance every fifteen minutes, so 96 a day, and Apple takes roughly 290 of them per
 * request. The arithmetic that follows is the whole of this class:
 *
 * <table>
 *   <tr><th>Alignment last observed</th><th>Keys to search</th><th>Requests</th></tr>
 *   <tr><td>yesterday</td><td>~96</td><td>1</td></tr>
 *   <tr><td>a week ago</td><td>~672</td><td>3</td></tr>
 *   <tr><td>a month ago</td><td>~2,880</td><td>10</td></tr>
 *   <tr><td>never (18-month-old tag)</td><td>~52,000</td><td>~180</td></tr>
 * </table>
 *
 * <p>The threshold sits at a week, where the search is still a couple of requests and finishes
 * while somebody is looking at the screen. It errs towards showing the banner: being told to
 * wait for something that turns out to be quick costs a moment's attention, and being told
 * nothing during three minutes of apparent hang is what this whole mechanism exists to prevent.
 *
 * <p>Pure and on the JVM, per AGENTS.md rule 13 - it takes timestamps and returns a boolean.
 */
public final class SlowFirstFetch {

    /**
     * How stale an alignment record has to be before its fetch is worth warning about.
     *
     * <p>Seven days is about three requests. Below that the search is over before the banner's
     * six-second delay has elapsed, so showing it would only ever be a flash.
     */
    static final long STALE_AFTER_MS = TimeUnit.DAYS.toMillis(7);

    private SlowFirstFetch() {
    }

    /**
     * @param alignmentObservedAt when each tag in the batch last had its keys aligned. A
     *                            {@code null} entry is a tag with no alignment record at all,
     *                            which is the slowest case there is.
     * @param now                 the current time, passed in so this can be tested without a
     *                            clock.
     * @return true if any one of them will search far enough back to be worth a banner. One is
     *         enough: the batch is fetched one accessory at a time, so a single unaligned tag
     *         holds up everything behind it.
     */
    public static boolean isLikely(final Collection<Long> alignmentObservedAt, final long now) {
        if (alignmentObservedAt == null) {
            // Nothing known about the batch. Treated as slow, because the alternative is
            // silence during the exact case the banner is for.
            return true;
        }

        for (final Long observedAt : alignmentObservedAt) {
            if (observedAt == null || now - observedAt > STALE_AFTER_MS) {
                return true;
            }
        }

        return false;
    }
}
