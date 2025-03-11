package dev.wander.android.airtagforall.db.room.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import lombok.AllArgsConstructor;
import lombok.Builder;

@Builder
@AllArgsConstructor
@Entity(
        tableName = "Import"
)
public class Import {
    @PrimaryKey(autoGenerate = true)
    public Long id;

    @ColumnInfo(name = "version")
    public String version;

    /**
     * Unix timestamp of time of import into app
     */
    @ColumnInfo(name = "imported_at")
    public long importedAt;

    /**
     * Unix timestamp of time exported from source
     */
    @ColumnInfo(name = "exported_at")
    public long exportedAt;

    @ColumnInfo(name = "source_user")
    public String sourceUser;

    @ColumnInfo(name = "via")
    public String exportedVia;
}
