package dev.wander.android.opentagviewer.util.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;

/**
 * The zip a user is handed by the storage picker.
 *
 * <p>The failures worth guarding here are the ones that produce an archive some readers accept
 * and others refuse - a duplicate entry name, a name carrying a character a filesystem will not
 * take - and the one where the whole thing unpacks fine and is simply empty.
 */
public class HistoryZipWriterTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static BeaconLocationReport at(final String isoInstant) {
        return BeaconLocationReport.builder()
                .timestamp(Instant.parse(isoInstant).toEpochMilli())
                .publishedAt(Instant.parse(isoInstant).toEpochMilli())
                .latitude(52.37).longitude(4.89)
                .horizontalAccuracy(10).confidence(2).status(1)
                .description("somewhere")
                .build();
    }

    private static List<String> entryNamesOf(final byte[] zipBytes) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static byte[] write(final List<HistoryExportEntry> history)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new HistoryZipWriter(UTC).write(out, history);
        return out.toByteArray();
    }

    @Test
    public void entryNamesCarryTheTagAndTheRangeItCovers() throws Exception {
        List<HistoryExportEntry> history = List.of(new HistoryExportEntry(
                "wallet-id", "Shane's Wallet", List.of(
                at("2026-07-02T08:00:00Z"), at("2026-08-15T20:00:00Z"))));

        assertEquals(List.of("Shane's Wallet_2026-07-02_2026-08-15.csv"),
                entryNamesOf(write(history)));
    }

    @Test
    public void twoTagsSharingANameDoNotProduceADuplicateEntry() throws Exception {
        // Nothing stops somebody naming two tags "Keys", and some readers reject an archive
        // outright when it contains the same entry twice.
        List<HistoryExportEntry> history = List.of(
                new HistoryExportEntry("keys-1", "Keys",
                        List.of(at("2026-08-15T10:00:00Z"))),
                new HistoryExportEntry("keys-2", "Keys",
                        List.of(at("2026-08-15T10:00:00Z"))));

        List<String> names = entryNamesOf(write(history));

        assertEquals(2, names.size());
        assertFalse("entry names must be unique, got: " + names,
                names.get(0).equals(names.get(1)));
    }

    @Test
    public void charactersFilesystemsRejectAreStrippedFromEntryNames() {
        assertEquals("Shanes Wallet", HistoryZipWriter.safeName("Shane/s: Wallet?"));
        // Emoji are ordinary in tag names here and must survive.
        assertEquals("🎒 Backpack", HistoryZipWriter.safeName("🎒 Backpack"));
        // A name made only of illegal characters would otherwise produce ".csv".
        assertEquals("tag", HistoryZipWriter.safeName("///"));
        assertEquals("tag", HistoryZipWriter.safeName("   "));
    }

    @Test
    public void aTagWithNoHistoryStillGetsAFileWithItsColumns() throws Exception {
        // A tag with no locations is a normal state - see the beacons that get no card - and
        // silently omitting it would read as the export having lost something.
        List<HistoryExportEntry> history = List.of(
                new HistoryExportEntry("never-seen", "Never Seen", List.of()));

        byte[] zipBytes = write(history);
        assertEquals(List.of("Never Seen.csv"), entryNamesOf(zipBytes));

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            zip.getNextEntry();
            final String contents = new String(zip.readAllBytes());
            assertTrue("the header row was expected, got: " + contents,
                    contents.startsWith("timestamp_utc,"));
        }
    }

    @Test
    public void everySelectedTagGetsItsOwnFile() throws Exception {
        List<HistoryExportEntry> history = List.of(
                new HistoryExportEntry("wallet", "Wallet",
                        List.of(at("2026-08-15T10:00:00Z"))),
                new HistoryExportEntry("backpack", "Backpack",
                        List.of(at("2026-08-14T10:00:00Z"))),
                new HistoryExportEntry("keys", "Keys",
                        List.of(at("2026-08-13T10:00:00Z"))));

        assertEquals(3, entryNamesOf(write(history)).size());
    }
}
