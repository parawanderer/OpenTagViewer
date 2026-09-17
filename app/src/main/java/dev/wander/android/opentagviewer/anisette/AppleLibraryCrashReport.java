package dev.wander.android.opentagviewer.anisette;

import android.content.Context;
import android.os.Build;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * What a report of Apple's library crashing on this phone needs to say.
 *
 * <p><b>The phone is the evidence.</b> Issue #232 is a crash inside Apple's own code, in the same
 * binary that works on most phones, and nobody knows yet what the affected ones share. A report
 * that names the model, the Android version and the ABI is a data point; one that says "sign-in
 * uses a server" is not. Nothing here identifies the person - no account, no identifiers.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AppleLibraryCrashReport {

    public static String describe(final Context context, final AnisetteStatus status) {
        String libraries;
        try {
            libraries = AdiLibraryManifest.load(context).apkVersion();
        } catch (final Exception e) {
            libraries = "unknown";
        }

        return (status.detail() == null ? LocalAnisette.CRASHES_HERE_REASON : status.detail())
                + " (#232)"
                + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")"
                + "\nAndroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\nABI: " + Build.SUPPORTED_ABIS[0]
                + "\nApple Music libraries: " + libraries;
    }
}
