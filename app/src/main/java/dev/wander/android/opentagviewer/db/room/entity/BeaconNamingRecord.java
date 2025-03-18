package dev.wander.android.opentagviewer.db.room.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import lombok.AllArgsConstructor;
import lombok.Builder;

@Builder
@AllArgsConstructor
@Entity(
        tableName = "BeaconNamingRecord",
        foreignKeys = {
                @ForeignKey(
                        entity = Import.class,
                        parentColumns = {"id"},
                        childColumns = {"import_id"},
                        onUpdate = ForeignKey.CASCADE,
                        onDelete = ForeignKey.CASCADE
                )
        },
        indices = @Index(value = {"import_id"})
)
public class BeaconNamingRecord {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public String id;

    @ColumnInfo(name = "import_id")
    public Long importId;

    @ColumnInfo(name = "version")
    public String version;

    /**
     * Content is XML
     */
    @ColumnInfo(name = "content")
    public String content;

    @ColumnInfo(name = "is_removed")
    public boolean isRemoved;
}
