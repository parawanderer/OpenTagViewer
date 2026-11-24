package dev.wander.android.opentagviewer.util;

import java.time.Instant;
import java.time.ZoneId;

/**
 * A timestamp on today's local date, a few minutes in, for seeded test data.
 *
 * <p><b>Anchored to local midnight rather than counted back from now.</b> The history screen
 * asks for one local day at a time, so a report seeded at "two hours ago" belongs to yesterday
 * for anybody running the suite between 00:00 and 02:00 - green all day, and empty-handed at
 * half past midnight for reasons that have nothing to do with the app under test. That happened
 * on CI, twice, and cost a 22-minute rerun each time.
 *
 * <p>Minutes rather than hours, so a report seeded this way is always on today. And clamped to
 * {@code now}, because in the first minutes of the day today's clock has not got as far as the
 * offset yet and a report dated in the future is its own kind of wrong.
 *
 * <p>Plain Java with no Android types, so the JVM suite can pin the boundary cases (rule 13). It
 * lives in the app's source set rather than beside the instrumentation tests because the JVM
 * suite cannot compile against that one.
 */
public final class TimesToday {

    private static final long MINUTE = 60_000L;

    private TimesToday() {
    }

    /**
     * A distinct timestamp on today's local date, for item {@code index} of a seeded batch.
     *
     * @param index which item; each one is a minute later than the one before
     */
    public static long at(final int index) {
        return at(index, System.currentTimeMillis(), ZoneId.systemDefault());
    }

    /** @see #at(int) */
    public static long at(final int index, final long nowMillis, final ZoneId zone) {
        return Math.min(nowMillis, startOfToday(nowMillis, zone) + ((index + 1) * MINUTE));
    }

    /** Midnight this morning, by the clock the history screen uses. */
    public static long startOfToday(final long nowMillis, final ZoneId zone) {
        return Instant.ofEpochMilli(nowMillis)
                .atZone(zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli();
    }
}
