package dev.wander.android.opentagviewer.util.rx;

import java.util.concurrent.TimeUnit;

/**
 * How often to keep asking about a tag that has said nothing.
 *
 * <p><b>The expensive tags are the silent ones.</b> A tag with no key alignment record searches
 * from its pairing date, at a request per ~290 keys - so where a healthy tag costs one request,
 * one that nobody has walked past costs hundreds, and it costs them again on every single
 * refresh. Left alone, the app spends most of its conversation with Apple on the tags least
 * likely to answer, which is the account-flagging risk of rule 6 arriving through the back door.
 *
 * <p><b>So a tag earns its next attempt.</b> The first few silences are ordinary - a tag in a
 * drawer for a fortnight is a normal tag - so the backoff starts short and only becomes long once
 * the silence has. It caps rather than growing forever, because a tag can always come back: a
 * bike found, a jacket out of storage, and the app should notice within a day.
 *
 * <p><b>This governs the automatic fetches and nothing else.</b> A person who opens a tag and
 * asks for it gets a search, every time, however long it has been quiet - they may have just
 * found the bike. Backing off a button somebody pressed would look like the app ignoring them,
 * which is the failure this whole area is meant to avoid rather than reproduce. So this is
 * consulted where the periodic request list is built, never on a user-initiated refresh.
 *
 * <p>Pure and clock-free: it takes the time rather than reading one, so the schedule can be
 * tested without waiting a week.
 */
public final class WideScanBackoff {

    /**
     * What each consecutive fruitless search buys, in minutes.
     *
     * <p>Sized against what silence means rather than against a formula. The first two entries
     * are "no delay at all" - two misses is not evidence of anything, and a tag that has just
     * been imported deserves a proper try. After that it lengthens quickly, and stops at a day
     * so nothing is ever unreachable for longer than that without somebody asking.
     */
    private static final long[] SCHEDULE_MINUTES = {0, 0, 15, 60, 4 * 60, 12 * 60, 24 * 60};

    private WideScanBackoff() {
    }

    /**
     * How long to wait after {@code fruitlessScans} searches that found nothing.
     *
     * @param fruitlessScans consecutive searches that came back empty; 0 means it last worked.
     */
    public static long waitMillisAfter(final int fruitlessScans) {
        if (fruitlessScans <= 0) {
            return 0;
        }

        final int step = Math.min(fruitlessScans, SCHEDULE_MINUTES.length - 1);
        return TimeUnit.MINUTES.toMillis(SCHEDULE_MINUTES[step]);
    }

    /**
     * Whether this tag is due to be searched for again.
     *
     * @param now            the wall clock, passed in
     * @param fruitlessScans consecutive searches that found nothing
     * @param lastScanAt     when it was last searched for, or null if never
     */
    public static boolean isDue(final long now, final int fruitlessScans, final Long lastScanAt) {
        if (lastScanAt == null) {
            // Never tried. Always worth one attempt - this is how a tag imported five minutes
            // ago gets looked for at all.
            return true;
        }

        return now >= lastScanAt + waitMillisAfter(fruitlessScans);
    }

    /** The longest anything is ever left alone, for a test and for a log line to quote. */
    public static long longestWaitMillis() {
        return TimeUnit.MINUTES.toMillis(SCHEDULE_MINUTES[SCHEDULE_MINUTES.length - 1]);
    }
}
