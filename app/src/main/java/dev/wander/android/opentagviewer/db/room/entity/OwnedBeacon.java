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
     * How many times in a row a full-history search for this tag has come back with nothing.
     *
     * <p><b>What stops the app scanning a silent tag every single refresh.</b> A tag with no key
     * alignment record searches from its pairing date, and that search costs a request per ~290
     * keys - so a tag nobody has walked past is not merely uninformative, it is the most
     * expensive thing in the batch, repeated on every tick. The count drives a backoff: the
     * longer it has said nothing, the less often it is asked. See {@code WideScanBackoff}.
     *
     * <p>Reset to zero the moment anything is found, because a tag that reports again is a
     * normal tag again - the silence may have been a fortnight in a drawer.
     */
    @ColumnInfo(name = "fruitless_scans", defaultValue = "0")
    public int fruitlessScans;

    /** When this tag was last searched for, so the backoff knows whether it is due. */
    @ColumnInfo(name = "last_scan_at")
    public Long lastScanAt;

    /**
     * When the app gave up on this tag, or null if it has not.
     *
     * <p>Set only when a search that covered <b>months</b> of history found nothing anywhere -
     * not merely when a search found nothing, which for a young tag means almost nothing. Such a
     * tag is skipped entirely rather than backed off, and says so on screen with a button to try
     * again; anything found clears this.
     */
    @ColumnInfo(name = "ignored_at")
    public Long ignoredAt;

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
