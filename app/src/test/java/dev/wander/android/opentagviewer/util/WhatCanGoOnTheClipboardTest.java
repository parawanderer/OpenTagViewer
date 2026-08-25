package dev.wander.android.opentagviewer.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Whether a log is small enough to put on the clipboard.
 *
 * <p><b>Because above roughly a megabyte the clipboard call throws and takes the app with it.</b>
 * {@code setPrimaryClip} is a Binder transaction, Binder caps one at about 1 MB across the whole
 * process, and a string parcels as UTF-16 - two bytes per character. Raising the captured log to
 * 5000 lines produced a 1,055,848-byte parcel and a {@code TransactionTooLargeException} on the
 * error-report screen, which is to say the app crashed while somebody was reporting a crash.
 *
 * <p>The screen answers that by not offering the clipboard for a log that large. This is the
 * predicate it asks, and it is pure - rule 13.
 */
public class WhatCanGoOnTheClipboardTest {

    private static String ofLength(final int chars) {
        final StringBuilder sb = new StringBuilder(chars);
        while (sb.length() < chars) {
            sb.append('x');
        }
        return sb.toString();
    }

    /** An ordinary log is copyable, which is the path people actually take. */
    @Test
    public void anordinaryLogGoesOnTheClipboard() {
        assertTrue(LogCollectorUtil.fitsOnTheClipboard(ofLength(80_000)));
    }

    /**
     * <b>The size that crashed it does not.</b>
     *
     * <p>525,000 characters is about what 5000 lines of this device's logcat came to, and about
     * the 1,055,848 bytes the failing parcel measured.
     */
    @Test
    public void thesizeThatCrashedTheAppDoesNot() {
        assertFalse(LogCollectorUtil.fitsOnTheClipboard(ofLength(525_000)));
    }

    /**
     * The limit itself is allowed, and one character past it is not.
     *
     * <p>Pinning both sides, because a comparison that is accidentally strict or accidentally
     * inclusive passes every test that only checks the far ends.
     */
    @Test
    public void thelimitIsInclusiveAndOnePastItIsNot() {
        assertTrue(LogCollectorUtil.fitsOnTheClipboard(
                ofLength(LogCollectorUtil.CLIPBOARD_LIMIT_CHARS)));
        assertFalse(LogCollectorUtil.fitsOnTheClipboard(
                ofLength(LogCollectorUtil.CLIPBOARD_LIMIT_CHARS + 1)));
    }

    /**
     * The limit leaves room for the rest of the transaction.
     *
     * <p>Binder's budget is shared with whatever else the process has in flight, so a limit that
     * merely fits on its own is not enough. This asserts the headroom is real rather than
     * incidental: parcelled at two bytes a character, the cap has to stay well under a megabyte.
     */
    @Test
    public void thelimitKeepsHeadroomUnderBinder() {
        final long parcelledBytes = 2L * LogCollectorUtil.CLIPBOARD_LIMIT_CHARS;

        assertTrue("parcelled size " + parcelledBytes + " leaves no room for anything else",
                parcelledBytes <= 512 * 1024);
    }

    /**
     * No log is not something to offer copying.
     *
     * <p>The screen already hides the share button when redaction fails, but a predicate that
     * throws on null would turn that into a crash if the two ever came apart.
     */
    @Test
    public void nologIsNotCopyable() {
        assertFalse(LogCollectorUtil.fitsOnTheClipboard(null));
    }
}
