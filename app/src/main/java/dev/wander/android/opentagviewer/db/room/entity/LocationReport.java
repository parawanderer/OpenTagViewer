package dev.wander.android.opentagviewer.db.room.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * See: {@link dev.wander.android.opentagviewer.data.model.BeaconLocationReport}
 */
@Builder
@AllArgsConstructor
@Entity(
        tableName = "LocationReport",
        foreignKeys = {
                @ForeignKey(
                        entity = OwnedBeacon.class,
                        parentColumns = {"id"},
                        childColumns = {"beacon_id"},
                        onUpdate = ForeignKey.CASCADE,
                        onDelete = ForeignKey.CASCADE
                )
        },
        indices = @Index(value = {"hash_id", "beacon_id", "timestamp"})
)
public class LocationReport {
    /**
     * This is both a hash
     * (see: {@link dev.wander.android.opentagviewer.util.BeaconLocationReportHasher}
     * and an id. Making identifying duplicates easier.
     */
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "hash_id")
    public String hashId;

    @NonNull
    @ColumnInfo(name = "beacon_id")
    public String beaconId;

    @ColumnInfo(name = "published_at")
    public long publishedAt;

    @ColumnInfo(name = "description")
    public String description;

    @ColumnInfo(name = "timestamp")
    public long timestamp;

    @ColumnInfo(name = "confidence")
    public long confidence;

    @ColumnInfo(name = "latitude")
    public double latitude;

    @ColumnInfo(name = "longitude")
    public double longitude;

    @ColumnInfo(name = "horizontal_accuracy")
    public long horizontalAccuracy;

    @ColumnInfo(name = "status")
    public long status;

    @ColumnInfo(name = "last_update")
    public long lastUpdate;
}
