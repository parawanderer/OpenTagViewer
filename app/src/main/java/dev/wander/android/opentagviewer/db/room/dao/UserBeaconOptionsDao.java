package dev.wander.android.opentagviewer.db.room.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;
import java.util.Map;

import dev.wander.android.opentagviewer.db.room.entity.UserBeaconOptions;

@Dao
public interface UserBeaconOptionsDao {
    @Query("SELECT * FROM UserBeaconOptions")
    List<UserBeaconOptions> getAll();

    @Query("SELECT * FROM UserBeaconOptions WHERE beacon_id = :beaconId")
    UserBeaconOptions getById(String beaconId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(UserBeaconOptions... options);

    /**
     * Drop one tag's overrides.
     *
     * <p><b>What makes a real rename real.</b> An accessory renamed in iCloud has its actual name
     * changed, so any nickname sitting over the top of it has to go - a leftover would win at
     * display time and hide the value that was just written.
     */
    @Query("DELETE FROM UserBeaconOptions WHERE beacon_id = :beaconId")
    void deleteById(String beaconId);

    /**
     * Drop the nickname and emoji, and <b>keep everything else</b>.
     *
     * <p>What {@link #deleteById} used to be used for, and the reason it no longer is. The row
     * holds two unrelated kinds of preference now: what the user called a tag, and where they
     * dragged it to. A rename that iCloud accepted invalidates the first - the real name has
     * changed, so an override sitting over it would hide the value just written - and has
     * nothing whatever to say about the second. Deleting the row threw the arrangement away as
     * a side effect of renaming, which is not a thing the user asked for and not a thing they
     * would connect to what they just did.
     */
    @Query("UPDATE UserBeaconOptions SET ui_name = NULL, ui_emoji = NULL, last_update = :now"
            + " WHERE beacon_id = :beaconId")
    void clearNameAndEmoji(String beaconId, long now);

    /**
     * Store the arrangement the user just dragged the list into.
     *
     * <p>Every visible tag is written, not only the one that moved - {@code TagOrder} explains
     * why that is what makes the ordering coherent.
     *
     * <p><b>Two statements per tag rather than one upsert, and neither of them is
     * {@code INSERT OR REPLACE}.</b> Most tags have no row here at all, because most people
     * never rename anything, so a position often has to create one. The tempting shapes are
     * both wrong for this table:
     *
     * <ul>
     *   <li>{@code INSERT ... ON CONFLICT DO UPDATE} needs SQLite 3.24, and this app runs to
     *       API 24, which ships 3.9. It would compile, ship, and fail only on old phones.</li>
     *   <li>{@code INSERT OR REPLACE} deletes the conflicting row before inserting, and that
     *       delete cascades - see the long note on {@code OwnedBeaconDao.insertAll}. Here it
     *       would erase the very nickname and emoji this row exists to hold, so dragging a
     *       renamed tag would rename it back.</li>
     * </ul>
     *
     * <p>{@code INSERT OR IGNORE} then {@code UPDATE} is the shape that works on every version
     * and touches nothing else. In a transaction, so a list is never half-arranged.
     */
    @Transaction
    default void storeArrangement(final Map<String, Integer> positions, final long now) {
        for (final Map.Entry<String, Integer> entry : positions.entrySet()) {
            this.createIfAbsent(entry.getKey(), now);
            this.setOrder(entry.getKey(), entry.getValue(), now);
        }
    }

    /** A row to hang a position on, for a tag the user has never renamed. See above. */
    @Query("INSERT OR IGNORE INTO UserBeaconOptions (beacon_id, last_update, ui_name, ui_emoji,"
            + " ui_order) VALUES (:beaconId, :now, NULL, NULL, NULL)")
    void createIfAbsent(String beaconId, long now);

    /** Writes only the position, leaving the nickname and emoji exactly as they are. */
    @Query("UPDATE UserBeaconOptions SET ui_order = :position, last_update = :now"
            + " WHERE beacon_id = :beaconId")
    void setOrder(String beaconId, Integer position, long now);
}
