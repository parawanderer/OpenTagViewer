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

    /**
     * Whether to warn when this tag is left behind, or null if the user has not decided.
     *
     * <p><b>Null means yes.</b> Turning background scanning on is already a deliberate act, and
     * somebody who did it wants to be told - a feature that alerts for nothing until each tag is
     * enabled separately looks broken on the day it is set up.
     *
     * <p>Per tag because the answer genuinely differs per tag. Keys and a wallet are worth a
     * noise; a tag that lives in a car, or on something that is meant to stay behind, would
     * alert every time its owner walks into the house. One switch for all of them would be
     * turned off by the first tag that cried wolf, taking the useful ones with it.
     *
     * <p>Here rather than on {@code OwnedBeacons} for the reason this whole table exists: it is
     * the user's decision, and an account refresh must not touch it.
     */
    @ColumnInfo(name = "alert_on_separation")
    public Boolean alertOnSeparation;
}
