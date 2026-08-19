package dev.wander.android.opentagviewer.python.icloud;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One accessory as the three plists a bundle carries for it.
 *
 * <p><b>The same documents the zip importer already reads</b>, which is the point of the whole
 * exercise: an accessory read from an account and one read from a bundle become the same rows in
 * the same tables, with no second format and no zip in the middle.
 */
@Getter
@AllArgsConstructor
public class AccessoryRecords {
    private final String beaconId;

    /** The {@code OwnedBeacons} record. Carries the key material. */
    private final String ownedBeaconPlist;

    /**
     * Its {@code BeaconNamingRecord}, or <b>null where CloudKit holds none</b>.
     *
     * <p>Genuinely optional here, unlike in a bundle. A zip's importer inner-joins the two, so an
     * accessory exported without one goes silently missing - but nothing is being written to a
     * zip, the app left-joins, and a tag nothing ever named is a thing it already knows how to
     * show. Inventing a name would put a tag in the user's list as though they had named it.
     */
    private final String namingRecordPlist;

    /** Its {@code KeyAlignmentRecord}, if it has one. Absence is normal. */
    private final String keyAlignmentPlist;
}
