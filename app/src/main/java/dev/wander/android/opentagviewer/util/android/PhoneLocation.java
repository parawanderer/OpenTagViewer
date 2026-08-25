package dev.wander.android.opentagviewer.util.android;

import androidx.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Where this phone is, for the moment a tag is heard over Bluetooth.
 *
 * <p><b>A seam, because the real answer needs Google Play services and a granted permission.</b>
 * The rule that uses it - is this position worth writing down, and what accuracy does it claim -
 * is ordinary logic that should not need a device to exercise.
 *
 * <p>Null is an ordinary answer, not a failure: location may be off, the permission may not have
 * been granted, or the phone may simply have no fix yet. A sighting then records what it always
 * did (battery, alignment) and no position, which is the honest outcome.
 */
public interface PhoneLocation {

    /**
     * The last position the system already has, or null.
     *
     * <p><b>Deliberately the cached fix rather than a fresh one.</b> Asking for a new fix per
     * sighting would be the most expensive thing on a passive scan path, and the accuracy it
     * buys is far below what the claim needs: hearing the tag at all already places it within
     * Bluetooth range, so a fix good to a few metres is not the limiting factor.
     *
     * <p>Blocking. Called from the sighting path, which runs on an Rx io thread.
     */
    @Nullable
    Fix lastKnown();

    /** A position with the accuracy the system claims for it, in metres. */
    @AllArgsConstructor
    @Getter
    final class Fix {
        private final double latitude;
        private final double longitude;

        /**
         * Radius in metres the system claims for this position.
         *
         * <p>Written straight into a report's {@code horizontal_accuracy}, which is the same
         * field Apple's reports carry, so the two are comparable on the same scale.
         */
        private final long accuracyMetres;
    }
}
