package dev.wander.android.opentagviewer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Whether a log says it was cut off.
 *
 * <p><b>Because a short log and a truncated one are the same file from outside.</b> A report of a
 * tag showing no location arrived with a capture covering fourteen seconds, in which one of the
 * reporter's four tags had finished fetching. Whether that was all the device had, or all this
 * app asked for, was not answerable from the file - it needed the source open, and the person
 * who has the file is not usually the person who has that.
 *
 * <p>Only the composition is tested here. Reading logcat needs a device and is not the part that
 * can be subtly wrong - rule 13.
 */
public class DescribingHowMuchLogThereIsTest {

    // Built rather than String.repeat'd: repeat() is API 34 on Android, and NewApi does not
    // always know that a src/test source set never reaches a device.
    private static String lines(final int howMany) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < howMany; i++) {
            sb.append("a line\n");
        }
        return sb.toString();
    }

    /**
     * <b>A full log says this app cut it</b>, which is the case where re-capturing sooner helps.
     */
    @Test
    public void afullLogSaysTheLimitIsWhatEndedIt() {
        final String described = LogCollectorUtil.describeVolume(500, lines(500));

        assertTrue(described, described.contains("500 lines"));
        assertTrue("the reader has to know something is missing above the first line: " + described,
                described.contains("cut"));
    }

    /**
     * A short log says the device had no more, which is the case where re-capturing does nothing.
     *
     * <p>The opposite advice from the test above, off the same sentence, which is the whole
     * reason this is worth composing rather than printing a bare number.
     */
    @Test
    public void ashortLogSaysTheDeviceHadNoMoreToGive() {
        final String described = LogCollectorUtil.describeVolume(5000, lines(120));

        assertTrue(described, described.contains("120 lines"));
        assertTrue("what was asked for is what makes 120 meaningful: " + described,
                described.contains("5000"));
        assertFalse("nothing was cut here, and saying so would send them to re-capture "
                + "for no reason: " + described, described.contains("cut"));
    }

    /**
     * More back than was asked for still counts as truncated.
     *
     * <p>logcat is not required to honour {@code -t} to the line, and the advice for a log at the
     * limit does not change because one extra line arrived.
     */
    @Test
    public void oneLineOverTheLimitIsStillTruncated() {
        assertTrue(LogCollectorUtil.describeVolume(500, lines(501)).contains("cut"));
    }

    /**
     * A log with no trailing newline has not lost its last line.
     *
     * <p>Off by one in a number somebody uses to decide whether to capture again.
     */
    @Test
    public void thelastLineCountsWithoutANewlineAfterIt() {
        assertEquals(3, LogCollectorUtil.countLines("one\ntwo\nthree"));
        assertEquals(3, LogCollectorUtil.countLines("one\ntwo\nthree\n"));
    }

    /**
     * No log is zero lines, not one empty one.
     *
     * <p>A device that returns nothing is a real case - logcat can be denied - and "1 line" would
     * describe it wrongly in the direction that reads as working.
     */
    @Test
    public void nologIsNoLines() {
        assertEquals(0, LogCollectorUtil.countLines(""));
        assertEquals(0, LogCollectorUtil.countLines(null));

        final String described = LogCollectorUtil.describeVolume(5000, "");
        assertTrue(described, described.contains("0 lines"));
    }
}
