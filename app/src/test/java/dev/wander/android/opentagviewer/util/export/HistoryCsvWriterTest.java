package dev.wander.android.opentagviewer.util.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;

/**
 * The CSV a user opens in a spreadsheet.
 *
 * <p>What these protect is the class of bug that produces a file which opens without complaint
 * and is quietly wrong: a decimal comma splitting rows into extra columns, a description
 * containing a comma doing the same, timestamps that cannot be told apart from each other.
 * None of those throw, and none are visible until somebody trusts the numbers.
 */
public class HistoryCsvWriterTest {

    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");
    private static final String BEACON_ID = "beacon-123";

    /**
     * Summer, so Amsterdam is +02:00 and the UTC and local columns differ visibly.
     *
     * <p>Derived rather than written as a literal: an epoch constant and the human-readable
     * time it is supposed to mean drift apart the moment somebody mistypes one, and the test
     * then asserts against a date nobody intended.
     */
    private static final String RECORDED_AT_UTC = "2026-08-15T12:34:56Z";
    private static final long RECORDED_AT = java.time.Instant.parse(RECORDED_AT_UTC).toEpochMilli();

    private static BeaconLocationReport.BeaconLocationReportBuilder report() {
        return BeaconLocationReport.builder()
                .timestamp(RECORDED_AT)
                .publishedAt(RECORDED_AT + 60_000L)
                .latitude(52.3702157)
                .longitude(4.8951679)
                .horizontalAccuracy(12)
                .confidence(2)
                .status(1)
                .description("Amsterdam");
    }

    private static String write(final List<BeaconLocationReport> reports) throws IOException {
        StringWriter out = new StringWriter();
        new HistoryCsvWriter(AMSTERDAM).write(out, BEACON_ID, reports);
        return out.toString();
    }

    @Test
    public void everyReportCarriesTheStableBeaconIdentity() throws Exception {
        final String[] rows = write(List.of(report().build())).split("\r\n");

        assertTrue("the header should identify the stable beacon column",
                rows[0].endsWith(",beacon_id"));
        assertTrue("the report should carry the stable beacon id, got: " + rows[1],
                rows[1].endsWith("," + BEACON_ID));
    }

    @Test
    public void anEmptyHistoryStillWritesItsColumnNames() throws Exception {
        // A tag with nothing recorded is a normal state, not a failure. An empty file would
        // leave somebody wondering whether the export worked.
        final String csv = write(Collections.emptyList());

        assertEquals("only the header row was expected", 1, csv.split("\r\n").length);
        assertTrue("the header should name every column",
                csv.startsWith(String.join(",", HistoryCsvWriter.HEADERS)));
    }

    @Test
    public void coordinatesUseAPointEvenWhereTheLocaleUsesAComma() throws Exception {
        final Locale previous = Locale.getDefault();
        try {
            // The failure this guards against is invisible on an English phone: with a decimal
            // comma, "52,3702157" becomes two columns and every field after it shifts.
            Locale.setDefault(Locale.GERMANY);

            final String row = write(List.of(report().build())).split("\r\n")[1];

            assertTrue("latitude should use a decimal point, got: " + row,
                    row.contains("52.3702157"));
            assertEquals("the row should have exactly one field per column",
                    HistoryCsvWriter.HEADERS.length, row.split(",").length);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void bothUtcAndLocalTimesAreWrittenAndTheyDiffer() throws Exception {
        final String row = write(List.of(report().build())).split("\r\n")[1];

        assertTrue("an ISO 8601 UTC timestamp was expected, got: " + row,
                row.contains(RECORDED_AT_UTC));
        // +02:00 in August. Carrying the offset is what stops a local time being ambiguous.
        assertTrue("a local timestamp with its offset was expected, got: " + row,
                row.contains("2026-08-15 14:34:56+02:00"));
        assertTrue("the raw epoch value should be present too, got: " + row,
                row.contains(Long.toString(RECORDED_AT)));
    }

    @Test
    public void aDescriptionContainingACommaDoesNotBecomeTwoColumns() throws Exception {
        final String row = write(List.of(
                report().description("Hiraistraat 9D, 1101 DA Amsterdam").build())).split("\r\n")[1];

        assertTrue("the description should be quoted, got: " + row,
                row.contains("\"Hiraistraat 9D, 1101 DA Amsterdam\""));
    }

    @Test
    public void aDescriptionContainingQuotesIsEscapedRatherThanTruncated() {
        // Apple supplies this text, so it is arbitrary as far as this app is concerned.
        assertEquals("\"he said \"\"here\"\"\"", HistoryCsvWriter.escape("he said \"here\""));
    }

    @Test
    public void reportsAreWrittenOldestFirstWhateverOrderTheyArriveIn() throws Exception {
        final String[] rows = write(List.of(
                report().timestamp(RECORDED_AT + 120_000L).description("later").build(),
                report().timestamp(RECORDED_AT).description("earlier").build()
        )).split("\r\n");

        assertTrue("the oldest report should come first, got: " + rows[1],
                rows[1].contains("earlier"));
        assertTrue("the newest report should come last, got: " + rows[2],
                rows[2].contains("later"));
    }
}
