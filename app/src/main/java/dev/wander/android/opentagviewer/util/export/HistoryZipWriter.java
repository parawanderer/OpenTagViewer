package dev.wander.android.opentagviewer.util.export;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;

/**
 * Packs one CSV per tag into a zip.
 *
 * <p>Android-free on purpose, like {@link HistoryCsvWriter}: the caller supplies an already-open
 * stream, so this works the same whether that came from the storage picker or a test.
 *
 * <p>Entry names carry the tag's name and the range its reports actually span, so a zip is
 * self-describing once it has been unpacked and the surrounding context is gone.
 */
public final class HistoryZipWriter {

    /** Windows, macOS and Linux between them object to all of these. */
    private static final String ILLEGAL_IN_FILENAMES = "[\\\\/:*?\"<>|\\x00-\\x1F]";

    /** Long enough to stay recognisable, short enough to leave room for the dates. */
    private static final int MAX_NAME_LENGTH = 60;

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"));

    private final HistoryCsvWriter csvWriter;

    public HistoryZipWriter(@NonNull final ZoneId localZone) {
        this.csvWriter = new HistoryCsvWriter(localZone);
    }

    /**
     * Writes one entry per tag, in the order given.
     *
     * <p>The stream is not closed - whoever opened it closes it, which for the storage picker
     * means the try-with-resources that produced it.
     *
     * @param histories ordered tags with both their stable identity and user-visible name
     */
    public void write(
            @NonNull final OutputStream out,
            @NonNull final List<HistoryExportEntry> histories)
            throws IOException {

        final Set<String> used = new HashSet<>();

        // Not closed with try-with-resources: closing a ZipOutputStream closes what it wraps,
        // and the caller owns that.
        final ZipOutputStream zip = new ZipOutputStream(out);

        for (HistoryExportEntry tag : histories) {
            final String entryName = uniqueEntryName(
                    used, tag.getDisplayName(), tag.getReports());

            zip.putNextEntry(new ZipEntry(entryName));

            // The writer is not given the zip stream directly: an OutputStreamWriter closed
            // here would close the zip with it, and the encoding has to be explicit anyway -
            // the default charset is the platform's, and these files travel.
            Writer writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
            this.csvWriter.write(writer, tag.getBeaconId(), tag.getReports());
            writer.flush();

            zip.closeEntry();
        }

        zip.finish();
    }

    /**
     * {@code <tag name>_<first day>_<last day>.csv}, or just the name when there is nothing to
     * put a date on.
     */
    private String uniqueEntryName(
            final Set<String> used,
            final String tagName,
            final List<BeaconLocationReport> reports) {

        final StringBuilder name = new StringBuilder(safeName(tagName));

        if (!reports.isEmpty()) {
            long first = Long.MAX_VALUE;
            long last = Long.MIN_VALUE;
            for (BeaconLocationReport report : reports) {
                first = Math.min(first, report.getTimestamp());
                last = Math.max(last, report.getTimestamp());
            }
            name.append('_').append(DAY.format(Instant.ofEpochMilli(first)))
                    .append('_').append(DAY.format(Instant.ofEpochMilli(last)));
        }

        // Two tags can share a name - the app does not stop anyone calling both "Keys" - and a
        // duplicate zip entry is not merely untidy, some readers refuse the whole archive.
        String candidate = name + ".csv";
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = name + " (" + suffix++ + ").csv";
        }
        return candidate;
    }

    /** Strips what filesystems reject, so the zip unpacks everywhere rather than mostly. */
    static String safeName(final String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return "tag";
        }

        String cleaned = tagName.replaceAll(ILLEGAL_IN_FILENAMES, "").trim();

        // Emoji are common in tag names here and survive fine; a name that was *only* illegal
        // characters does not, and an entry called ".csv" helps nobody.
        if (cleaned.isEmpty()) {
            return "tag";
        }

        if (cleaned.length() > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NAME_LENGTH).trim();
        }

        return cleaned;
    }
}
