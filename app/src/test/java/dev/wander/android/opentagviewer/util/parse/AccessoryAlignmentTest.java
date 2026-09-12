package dev.wander.android.opentagviewer.util.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.Instant;

/**
 * Reading the alignment a fetch left behind, out of FindMy.py's serialised accessory.
 *
 * <p>This is the value that decides whether the "Locating your tags" banner is telling the truth,
 * and the one the debug panel shows. Both were reading the export's record instead, which never
 * moves.
 */
public class AccessoryAlignmentTest {

    /** The shape FindMy.py's FindMyAccessory.to_json writes, trimmed to what is read here. */
    private static String accessory(final String alignmentDate, final String alignmentIndex) {
        return "{\"type\":\"accessory\",\"master_key\":\"aa\",\"skn\":\"bb\",\"sks\":\"cc\","
                + "\"paired_at\":\"2024-03-11T09:00:00+00:00\",\"name\":\"Keys\","
                + "\"model\":\"AirTag\",\"identifier\":\"x\",\"group_identifier\":null,"
                + "\"serial_number\":\"HK7Q2M4XLPNV\","
                + "\"alignment_date\":" + alignmentDate + ","
                + "\"alignment_index\":" + alignmentIndex + "}";
    }

    @Test
    public void itreadsTheDateAndIndexAFetchWroteBack() {
        final String json = accessory("\"2026-09-02T07:15:00+00:00\"", "51234");

        assertEquals(Instant.parse("2026-09-02T07:15:00Z").toEpochMilli(),
                (long) AccessoryAlignment.alignedAtMillis(json));
        assertEquals(Integer.valueOf(51234), AccessoryAlignment.alignedIndex(json));
    }

    /**
     * FindMy.py writes {@code datetime.isoformat()}, which carries an offset rather than a Z.
     *
     * <p>{@code Instant.parse} rejects that, so a reader written against the obvious API would
     * answer null for every real accessory and quietly reinstate the bug this fixes.
     */
    @Test
    public void anoffsetIsAcceptedAsWellAsZuluTime() {
        final long withOffset = AccessoryAlignment.alignedAtMillis(
                accessory("\"2026-09-02T09:15:00+02:00\"", "1"));
        final long withZ = AccessoryAlignment.alignedAtMillis(
                accessory("\"2026-09-02T07:15:00Z\"", "1"));

        assertEquals(withZ, withOffset);
    }

    /** A tag imported and never fetched. Ordinary, and not an error. */
    @Test
    public void anaccessoryThatHasNeverBeenAlignedAnswersNull() {
        final String json = accessory("null", "null");

        assertNull(AccessoryAlignment.alignedAtMillis(json));
        assertNull(AccessoryAlignment.alignedIndex(json));
    }

    /** Rows predating the FindMy 0.9.x upgrade carry no accessory JSON at all. */
    @Test
    public void nothingAtAllAnswersNullRatherThanThrowing() {
        assertNull(AccessoryAlignment.alignedAtMillis(null));
        assertNull(AccessoryAlignment.alignedIndex(null));
        assertNull(AccessoryAlignment.alignedAtMillis(""));
        assertNull(AccessoryAlignment.alignedIndex("   "));
    }

    /**
     * Unreadable input is unknown, not a crash.
     *
     * <p>This runs to decide whether to show a banner and to fill a debug row. Neither is worth
     * failing a fetch over.
     */
    @Test
    public void rubbishIsUnknownRatherThanAFailure() {
        assertNull(AccessoryAlignment.alignedAtMillis("not json"));
        assertNull(AccessoryAlignment.alignedIndex("{\"alignment_index\":\"not a number\"}"));
        assertNull(AccessoryAlignment.alignedAtMillis("{\"alignment_date\":\"yesterday\"}"));
        assertNull(AccessoryAlignment.alignedAtMillis("{}"));
    }
}
