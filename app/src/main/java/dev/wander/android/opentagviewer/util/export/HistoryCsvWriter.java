package dev.wander.android.opentagviewer.util.export;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;

/**
 * Writes a tag's location history as CSV.
 *
 * <p>Deliberately free of Android types so it can be tested on a normal JVM: everything here
 * is a decision about the file's contents, and those are what break.
 *
 * <p>Two decisions worth knowing about, because they are the ones that make the difference
 * between a file somebody can use and one they cannot:
 *
 * <ul>
 *   <li><b>Timestamps appear three times</b> - epoch milliseconds, ISO 8601 UTC, and the
 *       user's local time. Epoch alone is unreadable in a spreadsheet, local time alone is
 *       ambiguous once a reader is in a different zone or the clocks change, and ISO UTC alone
 *       makes somebody do timezone arithmetic to answer "where was I that afternoon".</li>
 *   <li><b>An empty history writes a header</b> rather than an empty file or an error. A tag
 *       with nothing recorded is a normal state, and a file with column names says that
 *       clearly.</li>
 * </ul>
 */
public final class HistoryCsvWriter {

    /**
     * Ordered so the columns somebody actually reads come first.
     *
     * <p>{@code horizontalAccuracy} and {@code confidence} are included because they are the
     * difference between a fix worth believing and one that is not, and a reader who does not
     * have them will believe all of them equally.
     */
    static final String[] HEADERS = {
            "timestamp_utc",
            "timestamp_local",
            "timestamp_epoch_ms",
            "latitude",
            "longitude",
            "horizontal_accuracy_m",
            "confidence",
            "status",
            "published_at_utc",
            "description",
    };

    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"));

    /** Offset included so a local time is never ambiguous about which zone produced it. */
    private static final DateTimeFormatter LOCAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");

    private static final String NEWLINE = "\r\n";

    private final ZoneId localZone;

    public HistoryCsvWriter(@NonNull final ZoneId localZone) {
        this.localZone = localZone;
    }

    /**
     * Writes every report, oldest first.
     *
     * <p>Sorting here rather than relying on the query: a spreadsheet opened at row 1 should
     * start at the beginning, and nothing else in this class depends on the caller's ordering.
     */
    public void write(
            @NonNull final Writer out,
            @NonNull final List<BeaconLocationReport> reports) throws IOException {

        out.write(String.join(",", HEADERS));
        out.write(NEWLINE);

        reports.stream()
                .sorted((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()))
                .forEach(report -> {
                    try {
                        out.write(toRow(report));
                        out.write(NEWLINE);
                    } catch (IOException e) {
                        throw new UncheckedWriteFailure(e);
                    }
                });
    }

    private String toRow(final BeaconLocationReport report) {
        final Instant recorded = Instant.ofEpochMilli(report.getTimestamp());

        return String.join(",",
                UTC.format(recorded),
                LOCAL.format(recorded.atZone(this.localZone)),
                Long.toString(report.getTimestamp()),
                // Plain decimal rather than the default locale's formatting: a comma decimal
                // separator would silently split the row into extra columns.
                String.format(java.util.Locale.ROOT, "%.7f", report.getLatitude()),
                String.format(java.util.Locale.ROOT, "%.7f", report.getLongitude()),
                Long.toString(report.getHorizontalAccuracy()),
                Long.toString(report.getConfidence()),
                Long.toString(report.getStatus()),
                UTC.format(Instant.ofEpochMilli(report.getPublishedAt())),
                escape(report.getDescription()));
    }

    /**
     * Quotes a field only when it needs it, per RFC 4180.
     *
     * <p>The description comes from Apple and is not under this app's control, so it is treated
     * as arbitrary text rather than assumed safe.
     */
    static String escape(final String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        final boolean needsQuoting = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;

        if (!needsQuoting) {
            return value;
        }

        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** Lets an IOException out of the forEach above without widening every signature. */
    static final class UncheckedWriteFailure extends RuntimeException {
        UncheckedWriteFailure(final IOException cause) {
            super(cause);
        }
    }
}
