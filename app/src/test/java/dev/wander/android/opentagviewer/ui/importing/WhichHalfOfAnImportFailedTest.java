package dev.wander.android.opentagviewer.ui.importing;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.EOFException;
import java.net.SocketTimeoutException;

import dev.wander.android.opentagviewer.util.parse.ZipImporterException;

/**
 * What the app decides to say about an import that did not finish.
 *
 * <p><b>On the JVM, because none of this touches Android</b> - it is an enum and a walk up a
 * cause chain. See {@code AGENTS.md} rule 13.
 *
 * <p>The behaviour under test is the fix for issues
 * <a href="https://github.com/parawanderer/OpenTagViewer/issues/19">#19</a> and
 * <a href="https://github.com/parawanderer/OpenTagViewer/issues/26">#26</a>, where every failure
 * anywhere in the import chain - including the network fetch that runs <i>after</i> the tags are
 * committed - reported that importing had failed. The structural half of that fix is in
 * {@code MapsActivity}, which now runs the two as separate chains so a fetch failure cannot
 * reach this classifier at all. What is left for it to get right is the zip half.
 */
public class WhichHalfOfAnImportFailedTest {

    /**
     * <b>The trap this class replaces.</b>
     *
     * <p>{@code reasonOf} answers {@code UNKNOWN} for anything that is not a
     * {@code ZipImporterException}, which is a perfectly reasonable thing for a function about
     * zip problems to say - it means "not one of mine". The old caller read it as "an unknown
     * import problem" and produced a message about importing, which is a different claim and,
     * for the errors that actually arrived here, a false one.
     */
    @Test
    public void anythingNotDiagnosedIsWorthReportingRatherThanExplaining() {
        assertEquals(ImportOutcome.REPORT_THE_IMPORT,
                ImportOutcome.of(new EOFException("Unexpected end of ZLIB input stream")));
        assertEquals(ImportOutcome.REPORT_THE_IMPORT,
                ImportOutcome.of(new ZipImporterException("something below us broke")));
        assertEquals(ImportOutcome.REPORT_THE_IMPORT, ImportOutcome.of(null));
    }

    /** Every reason the importer can name has advice, so it gets a message and not a bug page. */
    @Test
    public void everyNamedReasonIsExplainedInPlace() {
        for (final ZipImporterException.Reason reason : new ZipImporterException.Reason[] {
                ZipImporterException.Reason.NOT_A_ZIP,
                ZipImporterException.Reason.NOT_AN_EXPORT,
                ZipImporterException.Reason.DAMAGED,
                ZipImporterException.Reason.NO_TAGS,
                ZipImporterException.Reason.UNREADABLE }) {

            assertEquals("reason " + reason + " has advice and should not open a bug report",
                    ImportOutcome.EXPLAIN_THE_FILE,
                    ImportOutcome.of(new ZipImporterException(reason, "x")));
        }
    }

    /** A locked bundle is the ordinary path for a current export, so it is a question. */
    @Test
    public void alockedBundleAsksRatherThanFails() {
        assertEquals(ImportOutcome.ASK_FOR_THE_PASSCODE,
                ImportOutcome.of(new ZipImporterException(
                        ZipImporterException.Reason.LOCKED, "locked")));
        assertEquals(ImportOutcome.ASK_FOR_THE_PASSCODE,
                ImportOutcome.of(new ZipImporterException(
                        ZipImporterException.Reason.WRONG_PASSCODE, "nope")));
    }

    /**
     * Wrapped, because RxJava hands the subscriber its own exception rather than the thrown one.
     *
     * <p>Without the cause walk every real failure would classify as {@code REPORT_THE_IMPORT},
     * so somebody who picked their holiday photos would be invited to file a bug about it.
     */
    @Test
    public void areasonBuriedInACauseChainStillCounts() {
        assertEquals(ImportOutcome.EXPLAIN_THE_FILE,
                ImportOutcome.of(new RuntimeException("rx wrapper", new IllegalStateException(
                        "another layer",
                        new ZipImporterException(
                                ZipImporterException.Reason.NOT_A_ZIP, "a jpeg")))));
    }

    /**
     * <b>A network failure is not an import failure, and never was.</b>
     *
     * <p>Kept as a test rather than left to the structure, because the structure is what somebody
     * would undo. If a future change rejoins the two chains, this classifier starts seeing
     * timeouts again - and it will call them {@code REPORT_THE_IMPORT}, which sends every
     * Anisette outage to the bug tracker. The assertion documents what the value means so that
     * reading it back is enough to notice.
     */
    @Test
    public void atimeoutMustNeverReachThisClassifier() {
        assertEquals(
                "a timeout classifies as an unexplained *import* failure, which is why fetching"
                        + " must stay on its own chain",
                ImportOutcome.REPORT_THE_IMPORT,
                ImportOutcome.of(new SocketTimeoutException("connect timed out")));
    }
}
