package dev.wander.android.opentagviewer.ble;

import android.content.Context;

import dev.wander.android.opentagviewer.source.ExternalAccessory;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;

/**
 * Picks the sound protocol by the kind of device: Google Find Hub trackers ring through FMDN
 * ({@link FmdnRingTrigger}), everything else through the Find My / DULT path.
 */
public final class RoutingSoundTrigger implements AccessorySoundTrigger {
    private final AccessorySoundTrigger findMy;
    private final AccessorySoundTrigger fmdn;

    public RoutingSoundTrigger(final AccessorySoundTrigger findMy, final AccessorySoundTrigger fmdn) {
        this.findMy = findMy;
        this.fmdn = fmdn;
    }

    private AccessorySoundTrigger forDevice(final String accessoryJson) {
        return ExternalAccessory.isGoogle(accessoryJson) ? this.fmdn : this.findMy;
    }

    @Override
    public Observable<BleSoundTriggerUpdate> playSound(final Context context, final String accessoryJson) {
        return this.forDevice(accessoryJson).playSound(context, accessoryJson);
    }

    @Override
    public Observable<BleSoundTriggerUpdate> playSoundContinuously(final Context context, final String accessoryJson) {
        return this.forDevice(accessoryJson).playSoundContinuously(context, accessoryJson);
    }

    @Override
    public Completable stopSound(final Context context, final String accessoryJson) {
        return this.forDevice(accessoryJson).stopSound(context, accessoryJson);
    }
}
