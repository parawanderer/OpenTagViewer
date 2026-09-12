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

    /**
     * Where this report came from: {@code apple} or {@code local}.
     *
     * <p><b>An Apple report and a locally heard sighting are the same shape and not the same
     * claim.</b> An Apple row says some stranger's iPhone overheard the tag and reported a
     * position it worked out for itself, typically to within a hundred metres or worse. A local
     * row says this phone heard the tag directly, which puts it inside Bluetooth range - tens of
     * metres - and records the phone's own position as the tag's.
     *
     * <p>Both belong in this table, because everything that draws a tag reads from here: the map
     * marker, the "last updated" line, the navigate button and the history. A separate table
     * would mean teaching all of them about a second source.
     *
     * <p><b>The column exists because the history is exported.</b> Without it the CSV hands
     * somebody a file where their own phone's positions sit unlabelled among Apple's, and
     * nothing in the file says which is which.
     *
     * <p>Defaults to {@code apple}, which is correct for every row written before this existed:
     * they all came from the network.
     */
    @NonNull
    @ColumnInfo(name = "provenance", defaultValue = PROVENANCE_APPLE)
    public String provenance;

    /** Decrypted from Apple's Find My network. */
    public static final String PROVENANCE_APPLE = "apple";

    /** Heard by this phone's own radio, positioned from this phone's own location. */
    public static final String PROVENANCE_LOCAL = "local";
}
