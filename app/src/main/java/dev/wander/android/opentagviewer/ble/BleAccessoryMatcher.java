package dev.wander.android.opentagviewer.ble;

import java.util.Locale;
import java.util.Set;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Whether a scanned BLE address is one of an accessory's currently-expected MAC addresses.
 *
 * <p>Pulled out as a pure function, deliberately not Android or Chaquopy: both sides of this
 * comparison are stated to be uppercase already - {@code BluetoothDevice.getAddress()} by
 * Android's own contract, {@code KeyPair.mac_address} by FindMy.py's implementation - but a
 * platform or library changing that quietly would fail silently as "tag never found" rather than
 * loudly, so this normalises rather than trusting it. Kept free of both dependencies so this,
 * the part that actually decides a match, is the part with a test that runs on plain JVM.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BleAccessoryMatcher {

    public static boolean matches(final String scannedDeviceAddress, final Set<String> candidateMacs) {
        if (scannedDeviceAddress == null || candidateMacs.isEmpty()) {
            return false;
        }

        final String normalised = scannedDeviceAddress.toUpperCase(Locale.ROOT);
        for (final String candidate : candidateMacs) {
            if (candidate != null && candidate.toUpperCase(Locale.ROOT).equals(normalised)) {
                return true;
            }
        }
        return false;
    }
}
