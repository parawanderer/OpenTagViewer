package dev.wander.android.opentagviewer.db.room.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

import lombok.AllArgsConstructor;
import lombok.Builder;

@Builder
@AllArgsConstructor
@Entity(
        tableName = "UserBeaconOptions",
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
public class UserBeaconOptions {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "beacon_id")
    public String beaconId;

    @ColumnInfo(name = "last_update")
    public long lastUpdate;

    @ColumnInfo(name = "ui_name")
    public String uiName;

    @ColumnInfo(name = "ui_emoji")
    public String uiEmoji;

    /**
     * Where the user dragged this tag to, or null if they never have.
     *
     * <p><b>Here rather than on {@code OwnedBeacons} because it is the user's, not Apple's.</b>
     * This table is what the owner has decided about a tag - its nickname, its emoji - and an
     * account refresh is careful to leave all of it alone ({@code OwnedBeaconDao.refreshFromAccount}
     * is an UPDATE for exactly that reason). A column over there would sit among fields that get
     * rewritten from what Apple last said, which is the wrong company for a preference.
     *
     * <p><b>Null is not zero.</b> It means unarranged, and unarranged tags sort after arranged
     * ones rather than at the front - see {@link dev.wander.android.opentagviewer.util.TagOrder}.
     * Every existing row is null after the migration, which is correct: nobody has dragged
     * anything yet, so everyone keeps the order they had.
     */
    @ColumnInfo(name = "ui_order")
    public Integer uiOrder;
}
