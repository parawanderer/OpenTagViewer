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
     * Record that this accessory was seen advertising as {@code mac}, and return its new
     * serialized state for the caller to persist.
     *
     * <p>A BLE sighting is an observation of the same kind as a decrypted location report, and
     * can be worth the same thing: it can pin the rolling-key alignment, so the next scan
     * derives three keys instead of a twelve-hour range. Persisting it is the caller's job - see
     * {@code BeaconRepository#recordAccessorySighting}.
     *
     * <p><b>The address decides, the index is only a hint.</b> An index is trustworthy only
     * when {@code mac} came from a primary key - a secondary key's index is a lower bound, not
     * the true one - and this side of the bridge cannot tell the two apart. So Python re-derives
     * the keys itself and reads the type there; {@code hintIndex} only says <i>where to look
     * first</i>, and a wrong one costs nothing but the wide search that used to happen anyway.
     *
     * <p><b>The hint is what makes this affordable to call.</b> Checking one index is three key
     * derivations; searching the whole candidate window is around 1150, measured at 1.15s on
     * desktop and several times that under Chaquopy. Called on the sighting cadence without it,
     * the app sat at 135% CPU with two tags in range until Android killed it for not answering
     * input.
     *
     * @param hintIndex the index {@code currentMacAddresses} paired {@code mac} with, or null
     *                  when the caller does not know - the search then runs as it did before.
     *
     * @return the re-serialized accessory, or null if there was nothing worth recording - no
     * match, only a secondary-key match, or a failure. Null is not worth failing a caller over:
     * the sighting is an optimisation, and the sound either played or it did not regardless.
     *
     * <p>Defaulted to "records nothing" rather than a second required method, so a
     * {@code currentMacAddresses}-only lambda - most of this interface's test doubles, which
     * only ever care about the candidate set - keeps compiling. {@link ChaquopyAccessoryMacResolver}
     * overrides it for real.
     */
    default String recordSeen(
            String accessoryJson, String mac, long seenAtUnixMs, Integer hintIndex) {
        return null;
    }
}
