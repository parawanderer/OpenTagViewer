package dev.wander.android.opentagviewer.ble;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * What BLE scanning and GATT connection need at runtime, in one place.
 *
 * <p>Shared between the activity that requests these permissions and
 * {@link BleAccessorySoundTrigger}, which depends on them being granted, so the two cannot
 * silently disagree about what "enough" means - the same reasoning as AGENTS.md's rule on
 * putting a provider decision behind one abstraction rather than branching in more than one
 * place.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BlePermissions {

    /**
     * Android 12+ (API 31) needs {@code BLUETOOTH_SCAN}/{@code BLUETOOTH_CONNECT}; below that,
     * BLE scanning is gated on location instead. Both are already declared unconditionally in
     * the manifest, for the map feature - this only asks whether they are granted *yet*.
     */
    public static String[] required() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    public static boolean granted(final Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return isGranted(context, Manifest.permission.BLUETOOTH_SCAN)
                    && isGranted(context, Manifest.permission.BLUETOOTH_CONNECT);
        }
        return isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
                || isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private static boolean isGranted(final Context context, final String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}
