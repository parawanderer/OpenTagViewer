package dev.wander.android.opentagviewer.ble;

/** Where one {@link AccessorySoundTrigger#playSound} attempt currently is. */
public enum BleSoundTriggerPhase {
    /** Scanning for one of the accessory's candidate BLE addresses. */
    SCANNING,

    /** Found it; opening a GATT connection. */
    CONNECTING,

    /** Connected; writing the play-sound characteristic. */
    TRIGGERING,

    /** Finished - see the accompanying {@link BleSoundTriggerResult}. */
    DONE,
}
