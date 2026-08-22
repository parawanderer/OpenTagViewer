package dev.wander.android.opentagviewer.ble;

import android.content.Context;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;

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
     * @return a {@link Single} emitting exactly one {@link BleSoundTriggerResult}. Never errors -
     * every failure this can hit (no permission, not in range, connect/write failure) is a
     * status on the result, not an exception, so a caller only ever needs {@code subscribe} with
     * one lambda.
     */
    Single<BleSoundTriggerResult> playSound(Context context, String accessoryJson);

    /**
     * Repeats {@link #playSound} - scan, trigger (or fail), pause, scan again - for as long as
     * the returned {@link Observable} stays subscribed. For walking toward a tag by ear: a
     * single {@link #playSound} only ever gets one chance to be in range at the moment it scans.
     *
     * <p>Never errors, same as {@link #playSound} - each attempt's outcome is an item, not a
     * terminal signal, so one failed attempt (e.g. briefly out of range) does not end the loop.
     * Dispose the subscription to stop.
     */
    Observable<BleSoundTriggerResult> playSoundContinuously(Context context, String accessoryJson);
}
