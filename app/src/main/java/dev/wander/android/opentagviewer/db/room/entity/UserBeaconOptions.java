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
}
