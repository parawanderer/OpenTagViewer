package dev.wander.android.opentagviewer.python;

import java.util.List;

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
     * @return the candidate MAC address(es), or an empty list if none could be resolved. An
     * unreadable or null {@code accessoryJson} reports empty rather than throwing, since a
     * beacon whose accessory JSON has not yet been backfilled (see
     * {@code OwnedBeacon.accessoryJson}) is a real state the caller must be able to show, not a
     * bug in this call.
     */
    List<String> currentMacAddresses(String accessoryJson);
}
