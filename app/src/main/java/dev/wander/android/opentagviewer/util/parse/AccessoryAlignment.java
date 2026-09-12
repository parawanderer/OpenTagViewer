package dev.wander.android.opentagviewer.util.parse;

import android.util.Log;

import androidx.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Where a tag's rolling key search will actually start, read out of its live accessory state.
 *
 * <p><b>This is the value {@link KeyAlignmentPlist} is a stand-in for, and it moves.</b> The plist
 * is the {@code KeyAlignmentRecord} the export was made with: written once, at import, and never
 * touched again. FindMy.py's serialised accessory is the state the app hands back to Python on
 * every fetch and stores again afterwards - see {@code OwnedBeaconDao#updateAccessoryJson} - so
 * its {@code alignment_date} is where the next search begins.
 *
 * <p>The two disagree from the first successful fetch onwards, and the disagreement grows. A tag
 * exported three weeks ago and fetched hourly ever since has a three-week-old plist and an
 * alignment date from this morning.
 *
 * <p><b>Jackson rather than {@code org.json}.</b> {@code org.json} ships inside {@code android.jar},
 * where the JVM test runtime stubs it and every getter answers a default - so a test would read
 * zero from a document that says otherwise and pass. Jackson is a real dependency on both, which
 * is what lets the whole of this be a JVM test. See AGENTS.md rule 13.
 */
public final class AccessoryAlignment {

    private static final String TAG = AccessoryAlignment.class.getSimpleName();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AccessoryAlignment() {
    }

    /**
     * When this accessory's keys were last aligned, from the state the last fetch left behind.
     *
     * @param accessoryJson {@code OwnedBeacon.accessoryJson}, or null if the row has none.
     * @return milliseconds since the epoch, or null if there is no usable date in it. Null is
     *         ordinary: a tag imported and never fetched has no alignment date yet, and rows
     *         predating the FindMy 0.9.x upgrade have no accessory JSON at all.
     */
    @Nullable
    public static Long alignedAtMillis(@Nullable final String accessoryJson) {
        final JsonNode value = read(accessoryJson, "alignment_date");
        if (value == null || !value.isTextual()) {
            return null;
        }

        try {
            // FindMy.py writes datetime.isoformat(), which carries an offset. Instant.parse
            // wants a 'Z', so this goes through OffsetDateTime and accepts either.
            return OffsetDateTime.parse(value.asText().trim()).toInstant().toEpochMilli();
        } catch (final Exception notADate) {
            try {
                return Instant.parse(value.asText().trim()).toEpochMilli();
            } catch (final Exception stillNotADate) {
                Log.w(TAG, "An accessory carried an alignment_date this cannot read", stillNotADate);
                return null;
            }
        }
    }

    /**
     * The rolling key index the last fetch reached, for the debug panel.
     *
     * <p>Shown rather than used: it is the number a bug report about a slow or empty fetch wants
     * quoted, because it says how far the search had got and therefore how far the next one has
     * to go. Keys step every fifteen minutes, so the index is roughly ninety-six per day since
     * pairing.
     *
     * @return the index, or null if the accessory has never been aligned.
     */
    @Nullable
    public static Integer alignedIndex(@Nullable final String accessoryJson) {
        final JsonNode value = read(accessoryJson, "alignment_index");
        return value == null || !value.isNumber() ? null : value.asInt();
    }

    @Nullable
    private static JsonNode read(@Nullable final String accessoryJson, final String field) {
        if (accessoryJson == null || accessoryJson.isBlank()) {
            return null;
        }

        try {
            final JsonNode node = MAPPER.readTree(accessoryJson).get(field);
            return node == null || node.isNull() ? null : node;
        } catch (final Exception unreadable) {
            // Deliberately broad, and for the same reason KeyAlignmentPlist is: this runs to
            // decide whether to show a banner and to fill a debug row. Neither is worth failing
            // a fetch over, and "unknown" is a perfectly good answer.
            Log.w(TAG, "Could not read " + field + " out of an accessory", unreadable);
            return null;
        }
    }
}
