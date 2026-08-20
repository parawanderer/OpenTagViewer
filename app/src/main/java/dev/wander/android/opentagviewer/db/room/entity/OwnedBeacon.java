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
    @ColumnInfo(name = "id")
    public String id;

    @ColumnInfo(name = "import_id")
    public Long importId;

    /**
     * Content is XML
     */
    @ColumnInfo(name = "content")
    public String content;

    @ColumnInfo(name = "version")
    public String version;

    @ColumnInfo(name = "is_removed")
    public boolean isRemoved;

    /**
     * Whether this beacon was read from the user's Apple account rather than imported from a file.
     *
     * <p><b>The two are different kinds of row, and the difference decides what may delete them.</b>
     * An account beacon is a cache of what Apple holds: the list is re-read, so one that has left
     * the account goes from here too. A file-imported beacon is the only copy in existence -
     * nobody else has it and the export it came from may be long gone - so a refresh must never
     * touch it.
     *
     * <p>False for every row that predates this, which is correct: they all came from a file.
     */
    @ColumnInfo(name = "from_account")
    public boolean fromAccount;

    /**
     * Serialized FindMyAccessory state (JSON) for FindMy.py 0.9.x. Includes the
     * rolling-key alignment that updates after every fetch — persisting it back
     * across calls is what fixes the key-drift bug from issue #30.
     *
     * Nullable for rows imported under FindMy 0.7.6: lazily backfilled from
     * {@link #content} on first fetch via {@code main.py:convertPlistToJson}.
     */
    @ColumnInfo(name = "accessory_json")
    public String accessoryJson;

    /**
     * The KeyAlignmentRecord plist for this accessory, as exported from macOS.
     * <br>
     * Supplies the rolling-key index macOS last observed, which
     * {@code FindMyAccessory.from_plist(plist, key_alignment_plist)} uses as its starting
     * point. Without it an accessory starts at index 0 from its pairing date, so the first
     * fetch searches the tag's whole history - tens of thousands of keys for an older tag.
     * <br>
     * Nullable: exports made before format 0.0.2 do not contain one, and macOS has none for
     * accessories it has never observed. Retained rather than only converted, so a future
     * FindMy.py that reads more of this record can re-derive from it.
     */
    @ColumnInfo(name = "alignment_plist")
    public String alignmentPlist;
}
