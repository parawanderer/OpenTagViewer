package dev.wander.android.opentagviewer.util.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import dev.wander.android.opentagviewer.util.export.HistoryExportEntry;
import dev.wander.android.opentagviewer.util.export.HistoryCsvWriter;
import dev.wander.android.opentagviewer.util.export.HistoryZipWriter;

/** The history backup crossing its public restore seam. */
public class HistoryImporterTest {

    private static final String BEACON_ID = "beacon-123";
    private static final long RECORDED_AT =
            Instant.parse("2026-08-15T12:34:56Z").toEpochMilli();

    @Test
    public void whatTheAppExportsCanBeReadBackWithoutLosingAReportField() throws Exception {
        final BeaconLocationReport report = BeaconLocationReport.builder()
                .timestamp(RECORDED_AT)
                .publishedAt(RECORDED_AT + 60_001L)
                .latitude(52.37021571234567)
                .longitude(4.895167912345678)
                .horizontalAccuracy(12)
                .confidence(2)
                .status(1)
                .description("Hiraistraat 9D, \"Amsterdam\"\r\nsecond line")
                .build();
        final ByteArrayOutputStream archive = new ByteArrayOutputStream();
        new HistoryZipWriter(ZoneId.of("Europe/Amsterdam")).write(
                archive,
                List.of(new HistoryExportEntry(BEACON_ID, "Wallet", List.of(report))));

        final List<HistoryImportRow> received = new ArrayList<>();
        final HistoryImporter importer = new HistoryImporter(
                (rows, rowsRead, malformedRows, now) -> {
                    received.addAll(rows);
                    return new HistoryImportResult(rowsRead, rows.size(), 0, malformedRows, 0);
                },
                () -> 1_800_000_000_000L);

        final HistoryImportResult result = importer.importArchive(
                new ByteArrayInputStream(archive.toByteArray()));

        assertEquals(1, result.getRowsRead());
        assertEquals(1, result.getRowsAdded());
        assertEquals(1, received.size());
        assertEquals(BEACON_ID, received.get(0).getBeaconId());
        assertEquals(report, received.get(0).getReport());
    }

    @Test
    public void anAbsentDescriptionStaysAbsentRatherThanBecomingEmptyText() throws Exception {
        final BeaconLocationReport report = BeaconLocationReport.builder()
                .timestamp(RECORDED_AT)
                .publishedAt(RECORDED_AT + 1L)
                .latitude(1.123456789012345)
                .longitude(2.123456789012345)
                .horizontalAccuracy(12)
                .confidence(2)
                .status(1)
                .description(null)
                .build();
        final ByteArrayOutputStream archive = new ByteArrayOutputStream();
        new HistoryZipWriter(ZoneId.of("UTC")).write(
                archive,
                List.of(new HistoryExportEntry(BEACON_ID, "Wallet", List.of(report))));
        final List<HistoryImportRow> received = new ArrayList<>();

        importerCapturing(received).importArchive(
                new ByteArrayInputStream(archive.toByteArray()));

        assertEquals(report, received.get(0).getReport());
    }

    @Test
    public void aMalformedRowIsCountedWithoutBlockingTheRowsAroundIt() throws Exception {
        final String header = String.join(",", HistoryCsvWriter.requiredHeaders());
        final String bad = row("not-a-latitude", "bad");
        final String good = row("52.3702157", "good");
        final List<HistoryImportRow> received = new ArrayList<>();
        final HistoryImporter importer = importerCapturing(received);

        final HistoryImportResult result = importer.importArchive(new ByteArrayInputStream(
                zip("Wallet.csv", header + "\r\n" + bad + "\r\n" + good + "\r\n")));

        assertEquals(2, result.getRowsRead());
        assertEquals(1, result.getRowsAdded());
        assertEquals(1, result.getRowsMalformed());
        assertEquals("good", received.get(0).getReport().getDescription());
    }

    @Test
    public void headerOrderAndExtraColumnsDoNotChangeTheContract() throws Exception {
        final String csv = "beacon_id,description,status,confidence,horizontal_accuracy_m,"
                + "description_present,longitude,longitude_exact,latitude,latitude_exact,"
                + "published_at_utc,published_at_epoch_ms,timestamp_epoch_ms,timestamp_local,"
                + "timestamp_utc,future_column\r\n"
                + BEACON_ID + ",somewhere,1,2,12,true,4.8951679,4.895167912345678,"
                + "52.3702157,52.37021571234567,2026-08-15T12:35:56Z,"
                + (RECORDED_AT + 60_000L) + "," + RECORDED_AT + ","
                + "2026-08-15 14:34:56+02:00,2026-08-15T12:34:56Z,ignored\r\n";

        final HistoryImportResult result = importerCapturing(new ArrayList<>()).importArchive(
                new ByteArrayInputStream(zip("Wallet.csv", csv)));

        assertEquals(1, result.getRowsAdded());
    }

    @Test
    public void aNameOnlyExportIsRejectedInsteadOfGuessedFromItsFilename() throws Exception {
        final List<String> currentHeaders = HistoryCsvWriter.requiredHeaders();
        final String legacyHeader = String.join(",",
                currentHeaders.subList(0, currentHeaders.size() - 1));

        final HistoryImportException error = assertThrows(
                HistoryImportException.class,
                () -> importerCapturing(new ArrayList<>()).importArchive(
                        new ByteArrayInputStream(zip("Wallet.csv", legacyHeader + "\r\n"))));

        assertEquals(HistoryImportException.Reason.UNSUPPORTED_LEGACY, error.getReason());
    }

    @Test
    public void damagedZipAndAnArchiveWithNoCsvAreWholeArchiveFailures() throws Exception {
        final HistoryImporter importer = importerCapturing(new ArrayList<>());

        final HistoryImportException notAZip = assertThrows(
                HistoryImportException.class,
                () -> importer.importArchive(new ByteArrayInputStream(
                        "not a zip".getBytes(StandardCharsets.UTF_8))));
        final HistoryImportException noCsv = assertThrows(
                HistoryImportException.class,
                () -> importer.importArchive(new ByteArrayInputStream(
                        zip("readme.txt", "nothing to import"))));

        assertEquals(HistoryImportException.Reason.INVALID_ARCHIVE, notAZip.getReason());
        assertEquals(HistoryImportException.Reason.INVALID_ARCHIVE, noCsv.getReason());
    }

    @Test
    public void malformedCsvSyntaxIsAWholeArchiveFailure() throws Exception {
        final String header = String.join(",", HistoryCsvWriter.requiredHeaders());
        final String unclosedQuotedField = header + "\r\n\"unterminated";

        final HistoryImportException error = assertThrows(
                HistoryImportException.class,
                () -> importerCapturing(new ArrayList<>()).importArchive(
                        new ByteArrayInputStream(zip("Wallet.csv", unclosedQuotedField))));

        assertEquals(HistoryImportException.Reason.INVALID_ARCHIVE, error.getReason());
    }

    @Test
    public void aLaterBrokenFilePreventsTheEntireArchiveReachingPersistence() throws Exception {
        final AtomicBoolean persistenceWasCalled = new AtomicBoolean(false);
        final HistoryImporter importer = new HistoryImporter(
                (rows, rowsRead, malformedRows, now) -> {
                    persistenceWasCalled.set(true);
                    return new HistoryImportResult(rowsRead, rows.size(), 0, malformedRows, 0);
                },
                () -> 1_800_000_000_000L);
        final List<String> currentHeaders = HistoryCsvWriter.requiredHeaders();
        final String current = String.join(",", currentHeaders)
                + "\r\n" + row("52.3702157", "valid") + "\r\n";
        final String legacy = String.join(",",
                currentHeaders.subList(0, currentHeaders.size() - 1)) + "\r\n";

        final HistoryImportException error = assertThrows(
                HistoryImportException.class,
                () -> importer.importArchive(new ByteArrayInputStream(
                        zip(List.of("valid.csv", "legacy.csv"), List.of(current, legacy)))));

        assertEquals(HistoryImportException.Reason.UNSUPPORTED_LEGACY, error.getReason());
        assertFalse("no file should be merged before the whole archive validates",
                persistenceWasCalled.get());
    }

    @Test
    public void databaseFailureHasItsOwnTypedResult() throws Exception {
        final HistoryImporter importer = new HistoryImporter(
                (rows, rowsRead, malformedRows, now) -> {
                    throw new IllegalStateException("database unavailable");
                },
                () -> 1_800_000_000_000L);

        final HistoryImportException error = assertThrows(
                HistoryImportException.class,
                () -> importer.importArchive(new ByteArrayInputStream(zip(
                        "Wallet.csv", String.join(",",
                                HistoryCsvWriter.requiredHeaders()) + "\r\n"))));

        assertEquals(HistoryImportException.Reason.DATABASE_FAILED, error.getReason());
    }

    private static HistoryImporter importerCapturing(final List<HistoryImportRow> received) {
        return new HistoryImporter(
                (rows, rowsRead, malformedRows, now) -> {
                    received.addAll(rows);
                    return new HistoryImportResult(rowsRead, rows.size(), 0, malformedRows, 0);
                },
                () -> 1_800_000_000_000L);
    }

    private static String row(final String latitude, final String description) {
        return "2026-08-15T12:34:56Z,2026-08-15 14:34:56+02:00,"
                + RECORDED_AT + "," + latitude + ",4.8951679,12,2,1,"
                + "2026-08-15T12:35:56Z," + description + ","
                + (RECORDED_AT + 60_000L) + "," + latitude + ",4.8951679,true,"
                + BEACON_ID;
    }

    private static byte[] zip(final String name, final String contents) throws Exception {
        return zip(List.of(name), List.of(contents));
    }

    private static byte[] zip(final List<String> names, final List<String> contents)
            throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int index = 0; index < names.size(); index++) {
                zip.putNextEntry(new ZipEntry(names.get(index)));
                zip.write(contents.get(index).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
