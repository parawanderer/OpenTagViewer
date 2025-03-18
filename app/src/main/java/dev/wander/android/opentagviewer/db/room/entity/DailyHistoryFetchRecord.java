package dev.wander.android.opentagviewer.db.room.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NonNull;

@Builder
@AllArgsConstructor
@Entity(
        tableName = "DailyHistoryFetchRecord",
        primaryKeys = { "day_start_time", "beacon_id" },
        foreignKeys = {
                @ForeignKey(
                        entity = OwnedBeacon.class,
                        parentColumns = {"id"},
                        childColumns = {"beacon_id"},
                        onUpdate = ForeignKey.CASCADE,
                        onDelete = ForeignKey.CASCADE
                )
        },
        indices = @Index(value = {"beacon_id"})
)
public class DailyHistoryFetchRecord {
    @ColumnInfo(name = "day_start_time")
    public long dayStartTime;

    @androidx.annotation.NonNull
    @NonNull
    @ColumnInfo(name = "beacon_id")
    public String beaconId;

    @ColumnInfo(name = "last_update")
    public long lastUpdate;
}
