package dev.wander.android.opentagviewer.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Rounding Apple's {@code Retry-After} into something a person reads.
 *
 * <p>The cases are the exporter's, from {@code test_apple_declining.py}, so the phone and the
 * desktop say the same thing about the same header. Pure arithmetic, so JVM - rule 13.
 */
public class HowLongToWaitTest {

    private static void assertWait(double seconds, long amount, HowLongToWait.Unit unit) {
        final HowLongToWait wait = HowLongToWait.of(seconds);
        assertEquals(seconds + "s", amount, wait.getAmount());
        assertEquals(seconds + "s", unit, wait.getUnit());
    }

    @Test
    public void underAMinuteIsSeconds() {
        assertWait(0, 0, HowLongToWait.Unit.SECONDS);
        assertWait(1, 1, HowLongToWait.Unit.SECONDS);
        assertWait(45, 45, HowLongToWait.Unit.SECONDS);
    }

    /** Told to come back too early, a person is refused again and trusts the number less. */
    @Test
    public void itRoundsUpNeverDown() {
        assertWait(0.2, 1, HowLongToWait.Unit.SECONDS);
        assertWait(59.2, 1, HowLongToWait.Unit.MINUTES);
        assertWait(61, 2, HowLongToWait.Unit.MINUTES);
        assertWait(90, 2, HowLongToWait.Unit.MINUTES);
        assertWait(7201, 3, HowLongToWait.Unit.HOURS);
    }

    @Test
    public void minutesUntilTwoHoursThenHours() {
        assertWait(60, 1, HowLongToWait.Unit.MINUTES);
        assertWait(7199, 120, HowLongToWait.Unit.MINUTES);
        assertWait(7200, 2, HowLongToWait.Unit.HOURS);
    }

    /** A date already past arrives as 0 from Python; nothing should make it negative here. */
    @Test
    public void aNegativeWaitIsNone() {
        assertWait(-5, 0, HowLongToWait.Unit.SECONDS);
    }
}
