package dev.wander.android.opentagviewer.python;

import java.util.Map;

/**
 * The BLE MAC address(es) an accessory might currently be advertising.
 *
 * <p>Behind an interface for the same reason as {@link HardwareDescriber}: the real one is
 * Chaquopy, starts an interpreter and runs an EC point derivation, so a screen that called it
 * directly could not be launched in a test without all of that working.
 *
 * <p>Used to recognise an owned accessory's own advertisement in a BLE scan - see the {@code ble}
 * package - so it can be triggered directly (playing a sound) without going through Apple's Find
 * My network, the same thing Find My itself does when a tag is close enough to reach over
 * Bluetooth.
 */
public interface AccessoryMacResolver {

    /**
     * @param accessoryJson the persisted {@code OwnedBeacon.accessoryJson} for this beacon.
     * @return each candidate MAC address mapped to <b>the key index it came from</b>, or an
     * empty map if none could be resolved. An unreadable or null {@code accessoryJson} reports
     * empty rather than throwing, since a beacon whose accessory JSON has not yet been
     * backfilled (see {@code OwnedBeacon.accessoryJson}) is a real state the caller must be able
     * to show, not a bug in this call.
     *
     * <p>The index is what {@link #recordSeen} needs, and the reason this is a map rather than
     * the list it was: the search runs with a twelve-hour margin either side of the believed
     * alignment, and feeding a match back is what collapses the next call to a single index.
     */
    Map<String, Integer> currentMacAddresses(String accessoryJson);

    /**
     * Record that this accessory was seen advertising at {@code keyIndex}, and return its new
     * serialized state for the caller to persist.
     *
     * <p>A BLE sighting is an observation of the same kind as a decrypted location report, and
     * worth the same thing: it pins the rolling-key alignment, so the next scan derives three
     * keys instead of a twelve-hour range. Persisting it is the caller's job - see
     * {@code BeaconRepository#recordAccessorySighting}.
     *
     * @return the re-serialized accessory, or null if it could not be recorded. Null is not
     * worth failing a caller over: the sighting is an optimisation, and the sound either played
     * or it did not regardless.
     */
    String recordSeen(String accessoryJson, int keyIndex, long seenAtUnixMs);
}
