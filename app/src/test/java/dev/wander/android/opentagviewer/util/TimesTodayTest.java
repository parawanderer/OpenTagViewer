package dev.wander.android.opentagviewer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Seeded report times must land on today, whatever hour the suite runs at.
 *
 * <p>They used to be "now minus two hours", which is yesterday for anybody running between 00:00
 * and 02:00 - and the history screen asks for one local day at a time, so it showed nothing.
 * Green all day, red at midnight, twice on CI. Pure arithmetic with the clock passed in, so JVM
 * (rule 13).
 */
public class TimesTodayTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static long at(final LocalDateTime local, final ZoneId zone) {
        return local.atZone(zone).toInstant().toEpochMilli();
    }

    private static LocalDate day(final long millis, final ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }

    @Test
    public void aMidafternoonRunGetsAReportFromEarlierToday() {
        final long now = at(LocalDateTime.of(2026, 11, 24, 15, 30), UTC);

        final long report = TimesToday.at(0, now, UTC);

        assertEquals(LocalDate.of(2026, 11, 24), day(report, UTC));
        assertTrue("not in the past", report < now);
        assertEquals("a minute into the day", TimesToday.startOfToday(now, UTC) + 60_000L, report);
    }

    /** The regression: this is the window the fixture was red in. */
    @Test
    public void aRunJustAfterMidnightStillGetsTodaysDate() {
        final long now = at(LocalDateTime.of(2026, 11, 24, 0, 30), UTC);

        final long report = TimesToday.at(0, now, UTC);

        assertEquals(LocalDate.of(2026, 11, 24), day(report, UTC));
        assertTrue("not in the past", report < now);
    }

    @Test
    public void aRunJustBeforeMidnightGetsTheDayThatIsEnding() {
        final long now = at(LocalDateTime.of(2026, 11, 24, 23, 59, 59), UTC);

        final long report = TimesToday.at(0, now, UTC);

        assertEquals(LocalDate.of(2026, 11, 24), day(report, UTC));
        assertTrue("not in the past", report < now);
    }

    /** In the first minute of the day the offset has not happened yet, so now is all there is. */
    @Test
    public void inTheFirstMinuteItDoesNotDateAnythingInTheFuture() {
        final long now = at(LocalDateTime.of(2026, 11, 24, 0, 0, 10), UTC);

        final long report = TimesToday.at(0, now, UTC);

        assertEquals(now, report);
        assertEquals(LocalDate.of(2026, 11, 24), day(report, UTC));
    }

    @Test
    public void eachItemInABatchIsASeparateMoment() {
        final long now = at(LocalDateTime.of(2026, 11, 24, 9, 0), UTC);

        assertEquals(TimesToday.at(0, now, UTC) + 60_000L, TimesToday.at(1, now, UTC));
        assertEquals(TimesToday.at(1, now, UTC) + 60_000L, TimesToday.at(2, now, UTC));
    }

    /** The day boundary is the local one, not UTC's, whatever the device is set to. */
    @Test
    public void itUsesTheZoneItIsGivenNotUtc() {
        final ZoneId berlin = ZoneId.of("Europe/Berlin");
        // 23:30 UTC is already the 25th in Berlin, and its midnight is 23:00 UTC.
        final long now = at(LocalDateTime.of(2026, 11, 24, 23, 30), UTC);

        final long report = TimesToday.at(0, now, berlin);

        assertEquals(LocalDate.of(2026, 11, 25), day(report, berlin));
        assertEquals(day(now, berlin), day(report, berlin));
        assertTrue("not in the past", report < now);
    }
}
