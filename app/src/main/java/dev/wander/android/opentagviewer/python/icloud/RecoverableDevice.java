package dev.wander.android.opentagviewer.python.icloud;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One of the user's Apple devices whose passcode could unlock the keychain.
 *
 * <p>The serial is how the unlock step names it back to Python - the escrow record itself stays
 * on the Python side, because it is a live object holding key material and not a thing to
 * reconstruct from a string.
 *
 * <p>The rest is here so the screen can lay out a tile rather than print a sentence.
 */
@Getter
@AllArgsConstructor
public class RecoverableDevice {
    private final String serial;

    /**
     * FindMy.py's own one-line description.
     *
     * <p>Kept as the fallback for anything the tile cannot express, and because it is the
     * library's opinion about how a record should read.
     */
    private final String description;

    /**
     * What the user called this device, which is <b>often empty</b>.
     *
     * <p>Somebody who never renamed their phone has no name on the record. Use
     * {@link #displayName()} rather than this.
     */
    private final String name;

    /** The hardware model, e.g. {@code iPhone15,2}. Not something to show a person unaided. */
    private final String model;

    /** {@code iPhone}, {@code iPad}, {@code Mac}… - what the icon is chosen from. */
    private final String modelClass;

    /**
     * When the escrow record was created, in Unix milliseconds, or 0 if unknown.
     *
     * <p><b>Not when the device was last used</b>, and it must never be labelled that way. A
     * record escrowed three months ago says nothing about whether that phone was used this
     * morning, and telling somebody their daily iPhone was "last used 3 months ago" would send
     * them to the wrong device.
     */
    private final long escrowedAtMs;

    /**
     * What to put on the tile.
     *
     * <p>The user's own name where there is one; otherwise the class - "iPhone" is a real word
     * and tells them something, where FindMy.py's "unnamed device" fallback tells them nothing.
     * The model only as a last resort, because {@code iPhone15,2} is not a name.
     */
    public String displayName() {
        if (this.name != null && !this.name.isBlank()) {
            return this.name;
        }
        if (this.modelClass != null && !this.modelClass.isBlank()) {
            return this.modelClass;
        }
        if (this.model != null && !this.model.isBlank()) {
            return this.model;
        }

        return this.description;
    }

    /** Whether the name shown is the user's own, so a tile can add the model without repeating. */
    public boolean hasUserGivenName() {
        return this.name != null && !this.name.isBlank();
    }
}
