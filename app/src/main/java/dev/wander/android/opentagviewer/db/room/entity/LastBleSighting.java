package dev.wander.android.opentagviewer.db.room.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * The last thing this phone heard a tag say over Bluetooth, and when it heard it.
 *
 * <p><b>Named for the sighting, not for the battery, although the battery is all it holds
 * today.</b> The row is "what the tag last told us directly", and the battery level is one field
 * of that. Anything else worth keeping from a sighting - the position the phone was at when it
 * heard it is the obvious candidate, and the one already asked for in PR #139 - is another column
 * here rather than another table, and a plain additive migration. Naming the table after its
 * first column would have meant a rename, and renaming a table is the one migration SQLite makes
 * genuinely awkward.
 *
 * <p><b>Why any of it is kept.</b> The battery value on the accessory record comes from Apple's
 * devices as they walk past the tag, so for anyone without one it is either years old or never
 * written at all - see {@code BatteryLevelDescription}, and note that both of the real tags this
 * was developed against still report 0, "not yet reported". For those users the advertisement is
 * the only source there is. Keeping what it said means a tag heard this morning can still say
 * what it said this morning, instead of the screen going blank the moment the tag is out of
 * earshot.
 *
 * <p><b>Only what stays true is kept.</b> A battery level heard an hour ago is still roughly the
 * battery level; a signal strength heard an hour ago is about a distance that no longer exists,
 * so the RSSI on the sighting is deliberately not stored. Persisting it would invite showing it,
 * and {@code NearbyTagLabel} explains at length why even a live RSSI may not be presented as a
 * distance.
 *
 * <p><b>One row per tag, overwritten, not a history.</b> Every advertisement carries the same two
 * bits, so a log of them would be thousands of rows saying "full" to answer a question that only
 * ever needs the most recent one. If a genuine sighting history is ever built - as a local
 * alternative to Apple's location reports - it is a different shape, many rows per tag, and it
 * wants its own table; this one would stay as the cheap "what is the latest" lookup.
 *
 * <p><b>Its own table rather than a column elsewhere.</b> {@code UserBeaconOptions} is what the
 * owner has decided about a tag and an account refresh is careful never to touch it, which is
 * the wrong company for a measurement. {@code OwnedBeacons} is the cache of what Apple said,
 * rewritten from the account, and a reading taken by this phone is not Apple's to overwrite.
 */
@Builder
@AllArgsConstructor
@Entity(
        tableName = "LastBleSighting",
        foreignKeys = {
                @ForeignKey(
                        entity = OwnedBeacon.class,
                        parentColumns = {"id"},
                        childColumns = {"beacon_id"},
                        onUpdate = ForeignKey.CASCADE,
                        onDelete = ForeignKey.CASCADE
                )
        }
)
public class LastBleSighting {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "beacon_id")
    public String beaconId;

    /** When the advertisement was heard, so whatever it carried can be shown with its age. */
    @ColumnInfo(name = "heard_at")
    public long heardAt;

    /**
     * The battery level it reported, as the <b>name</b> of a
     * {@code FindMyAdvertisement.BatteryLevel}.
     *
     * <p>Not its ordinal. An ordinal is a position in a source file, so reordering the enum - a
     * change that looks harmless and compiles - would silently reinterpret every row already
     * written on every user's phone. A name is only ever wrong if somebody renames a constant,
     * which is a rename the compiler cannot hide either.
     *
     * <p>Read back through {@code BeaconRepository}, which treats an unrecognised name as no
     * reading rather than guessing: a row written by a later version that knows a level this one
     * does not must not be shown as some neighbouring level.
     */
    @NonNull
    @ColumnInfo(name = "battery_level")
    public String batteryLevel;

    /**
     * The whole status byte {@link #batteryLevel} was read out of.
     *
     * <p>Redundant on purpose, and cheap. The battery is two bits of it, decoded per a table that
     * nobody outside Apple has confirmed in full - {@code LocationReportFields} is explicit about
     * which parts of that byte are documented and which are inferred. Keeping the byte means a
     * disputed reading can be re-derived from what was actually received, and that a bug report
     * can quote the source rather than only this app's reading of it. The same reason the debug
     * panel always shows the raw number beside the label.
     */
    @ColumnInfo(name = "status_byte")
    public int statusByte;
}
