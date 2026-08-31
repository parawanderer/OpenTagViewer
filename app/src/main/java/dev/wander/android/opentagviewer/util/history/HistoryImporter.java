package dev.wander.android.opentagviewer.util.history;

import androidx.annotation.NonNull;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVException;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.util.export.HistoryCsvWriter;

/** Restores an Android history-export ZIP through one blocking interface. */
public final class HistoryImporter {
    private static final String BEACON_ID = "beacon_id";

    private static final CSVFormat CSV = CSVFormat.RFC4180.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
            .get();

    private final HistoryImportSink sink;
    private final LongSupplier clock;

    public HistoryImporter(@NonNull final OpenTagViewerDatabase db) {
        this(db.historyImportDao()::merge, System::currentTimeMillis);
    }

    HistoryImporter(
            @NonNull final HistoryImportSink sink,
            @NonNull final LongSupplier clock) {
        this.sink = sink;
        this.clock = clock;
    }

    /**
     * Consumes and closes {@code archive}. The caller must run this off the main thread.
     */
    public HistoryImportResult importArchive(@NonNull final InputStream archive)
            throws HistoryImportException {

        final ReadResult read = this.readArchive(archive);
        try {
            return this.sink.merge(
                    read.rows, read.rowsRead, read.malformedRows, this.clock.getAsLong());
        } catch (RuntimeException error) {
            throw new HistoryImportException(
                    HistoryImportException.Reason.DATABASE_FAILED,
                    "Room could not merge the history archive",
                    error);
        }
    }

    private ReadResult readArchive(final InputStream archive) throws HistoryImportException {
        final ReadResult result = new ReadResult();
        int csvEntries = 0;

        try (ZipInputStream zip = new ZipInputStream(archive, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()
                        || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                    continue;
                }

                csvEntries++;
                this.readCsvEntry(zip, result);
            }
        } catch (HistoryImportException error) {
            throw error;
        } catch (CSVException | ZipException | UncheckedIOException error) {
            throw new HistoryImportException(
                    HistoryImportException.Reason.INVALID_ARCHIVE,
                    "History ZIP or CSV is damaged",
                    error);
        } catch (IOException error) {
            throw new HistoryImportException(
                    HistoryImportException.Reason.READ_FAILED,
                    "History archive could not be read",
                    error);
        } catch (RuntimeException error) {
            // Commons CSV reports some malformed record shapes while its iterator advances.
            throw new HistoryImportException(
                    HistoryImportException.Reason.INVALID_ARCHIVE,
                    "History CSV structure is invalid",
                    error);
        }

        if (csvEntries == 0) {
            throw new HistoryImportException(
                    HistoryImportException.Reason.INVALID_ARCHIVE,
                    "Archive contains no history CSV files");
        }
        return result;
    }

    private void readCsvEntry(final ZipInputStream zip, final ReadResult result)
            throws IOException, HistoryImportException {

        // Closing a parser closes its Reader. The wrapper keeps that close from reaching the
        // ZipInputStream, whose next entry still has to be read.
        final InputStream currentEntry = new FilterInputStream(zip) {
            @Override
            public void close() {
                // The owning readArchive try-with-resources closes the zip once, at the end.
            }
        };

        try (InputStreamReader reader = new InputStreamReader(
                     currentEntry, StandardCharsets.UTF_8);
             CSVParser parser = CSV.parse(reader)) {

            final Map<String, Integer> headers = parser.getHeaderMap();
            if (!headers.containsKey(BEACON_ID)) {
                throw new HistoryImportException(
                        HistoryImportException.Reason.UNSUPPORTED_LEGACY,
                        "History export has no stable beacon identity");
            }
            if (!headers.keySet().containsAll(HistoryCsvWriter.requiredHeaders())) {
                throw new HistoryImportException(
                        HistoryImportException.Reason.INVALID_ARCHIVE,
                        "History CSV is missing required columns");
            }

            for (CSVRecord record : parser) {
                result.rowsRead++;
                final HistoryImportRow row = parseRow(record);
                if (row == null) {
                    result.malformedRows++;
                } else {
                    result.rows.add(row);
                }
            }
        }
    }

    private static HistoryImportRow parseRow(final CSVRecord row) {
        try {
            final String beaconId = row.get(BEACON_ID);
            if (beaconId == null || beaconId.isBlank()) {
                return null;
            }

            final double latitude = Double.parseDouble(row.get("latitude_exact"));
            final double longitude = Double.parseDouble(row.get("longitude_exact"));
            if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0
                    || !Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
                return null;
            }

            final BeaconLocationReport report = BeaconLocationReport.builder()
                    // The two readable timestamp columns are deliberately informational. Epoch
                    // milliseconds are the lossless value the app wrote for restoration.
                    .timestamp(Long.parseLong(row.get("timestamp_epoch_ms")))
                    .publishedAt(Long.parseLong(row.get("published_at_epoch_ms")))
                    .latitude(latitude)
                    .longitude(longitude)
                    .horizontalAccuracy(Long.parseLong(row.get("horizontal_accuracy_m")))
                    .confidence(Long.parseLong(row.get("confidence")))
                    .status(Long.parseLong(row.get("status")))
                    .description(parseDescription(row))
                    .build();

            return new HistoryImportRow(beaconId, report);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String parseDescription(final CSVRecord row) {
        final String present = row.get("description_present");
        if ("false".equals(present)) {
            return null;
        }
        if ("true".equals(present)) {
            return row.get("description");
        }
        throw new IllegalArgumentException("description_present is not a boolean");
    }

    private static final class ReadResult {
        private final List<HistoryImportRow> rows = new ArrayList<>();
        private int rowsRead;
        private int malformedRows;
    }
}
