package dev.wander.android.opentagviewer.python.icloud;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One accessory found in the account, described well enough to choose from.
 *
 * <p><b>No key material.</b> A picker needs names; the records themselves come separately, for
 * the ones the user actually picks - see {@link ICloudService#records}.
 */
@Getter
@AllArgsConstructor
public class ICloudAccessory {
    private final String beaconId;

    /** What the owner called it, or null if nothing ever named it. */
    private final String name;

    private final String emoji;

    /** How to show it in a list, which for a nameless one is still not much. */
    private final String label;

    /**
     * What kind of thing it is, its serial, and when it was paired.
     *
     * <p>This is what an accessory with no name has instead of one. "unnamed" three times over
     * is not a list anybody can choose from, and a pairing date is often the thing a person
     * recognises, because they remember buying it.
     */
    private final String details;

    /**
     * Whether a key alignment record came with it.
     *
     * <p>Without one, the first locate searches the tag's whole key history - tens of thousands
     * of keys for an older tag, which is slow enough to look like abuse of the account.
     */
    private final boolean hasAlignment;

    /** False when nothing ever named it. Not a problem to solve before importing. */
    private final boolean hasName;
}
