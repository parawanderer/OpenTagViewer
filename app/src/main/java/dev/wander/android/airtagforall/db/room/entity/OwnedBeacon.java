package dev.wander.android.airtagforall.db.room.entity;

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
        tableName = "OwnedBeacons",
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
public class OwnedBeacon {
    @PrimaryKey
    @NonNull
    public String id;

    @ColumnInfo(name = "import_id")
    public Long importId;

    /**
     * Content is XML
     */
    public String content;

    public String version;
}
