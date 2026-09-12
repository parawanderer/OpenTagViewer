package dev.wander.android.opentagviewer.ble;

import android.content.Context;

import io.reactivex.rxjava3.core.Observable;

/**
 * Plays an owned accessory's sound directly over Bluetooth, without going through Apple's Find
 * My network - the same thing Find My itself does when a tag is close enough to reach.
 *
 * <p>Behind an interface for the reason every Chaquopy/hardware dependency in this app is: the
 * real implementation needs Bluetooth radio and a nearby accessory, neither of which a test can
 * arrange - see {@code AppDependencies}.
 */
public interface AccessorySoundTrigger {

    /**
     * @param context       used for the Bluetooth system service and permission checks.
     * @param accessoryJson the persisted {@code OwnedBeacon.accessoryJson} for this beacon.
     * @return an {@link Observable} of {@link BleSoundTriggerUpdate}s - progress phases
     * (scanning, connecting, triggering) followed by exactly one terminal
     * {@link BleSoundTriggerPhase#DONE} carrying the {@link BleSoundTriggerResult}, then
     * completes. Never errors - every failure this can hit (no permission, not in range,
     * connect/write failure) is a status on the DONE result, not an exception, so a caller only
     * ever needs {@code subscribe} with one lambda. The progress items exist so a caller can show
     * "connecting..." instead of nothing for however long the handshake takes.
     */
    Observable<BleSoundTriggerUpdate> playSound(Context context, String accessoryJson);

    /**
     * Repeats {@link #playSound} - scan, trigger (or fail), pause, scan again - for as long as
     * the returned {@link Observable} stays subscribed. For walking toward a tag by ear: a
     * single {@link #playSound} only ever gets one chance to be in range at the moment it scans.
     *
     * <p>Never errors, same as {@link #playSound} - each item is a progress phase or a DONE
     * outcome, not a terminal signal, so one failed cycle (e.g. briefly out of range) does not
     * end the loop. Dispose the subscription to stop.
     */
    Observable<BleSoundTriggerUpdate> playSoundContinuously(Context context, String accessoryJson);
}
