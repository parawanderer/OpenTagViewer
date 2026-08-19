package dev.wander.android.opentagviewer.python.icloud;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One of the user's Apple devices whose passcode could unlock the keychain.
 *
 * <p>The serial is how the unlock step names it back to Python - the escrow record itself stays
 * on the Python side, because it is a live object holding key material and not a thing to
 * reconstruct from a string.
 */
@Getter
@AllArgsConstructor
public class RecoverableDevice {
    private final String serial;

    /**
     * FindMy.py's own description of the record - what kind of device, and when.
     *
     * <p>Shown as-is rather than reassembled here. It is the only thing telling two of a user's
     * iPhones apart, and this app knows nothing about escrow records that the library does not.
     */
    private final String description;
}
