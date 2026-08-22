package dev.wander.android.opentagviewer.ble;

/** How a {@link BleAccessorySoundTrigger#playSound} attempt ended. */
public enum BleSoundTriggerStatus {
    /** The start command was written; the accessory is (or was) playing its sound. */
    SUCCESS,

    /** The accessory's resolved candidate MAC address set was empty; nothing to scan for. */
    NO_CANDIDATE_MACS,

    /** The scan window ended without seeing any of the candidate MACs advertise. */
    NOT_NEARBY,

    /** Connected, but none of the known GATT sound services (DULT, FindMy, AirTag) were found. */
    NO_SOUND_SERVICE,

    /** A required runtime permission (scan or connect) is not granted. */
    MISSING_PERMISSION,

    /** Bluetooth is off, or connecting/writing otherwise failed. */
    FAILED,
}
