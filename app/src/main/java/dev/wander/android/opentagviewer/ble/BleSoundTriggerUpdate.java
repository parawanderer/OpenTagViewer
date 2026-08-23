package dev.wander.android.opentagviewer.ble;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One item of an {@link AccessorySoundTrigger#playSound} stream: either a progress phase with no
 * result yet, or the terminal {@link BleSoundTriggerPhase#DONE} carrying the outcome.
 *
 * <p>A stream rather than a single terminal value so a caller can show "found, connecting..."
 * instead of going silent for however long the GATT handshake takes - which otherwise reads as
 * nothing happening, especially the first time someone uses this.
 */
@AllArgsConstructor
@Getter
public class BleSoundTriggerUpdate {
    private final BleSoundTriggerPhase phase;

    /** Non-null if and only if {@link #phase} is {@link BleSoundTriggerPhase#DONE}. */
    private final BleSoundTriggerResult result;

    public static BleSoundTriggerUpdate progress(final BleSoundTriggerPhase phase) {
        return new BleSoundTriggerUpdate(phase, null);
    }

    public static BleSoundTriggerUpdate done(final BleSoundTriggerResult result) {
        return new BleSoundTriggerUpdate(BleSoundTriggerPhase.DONE, result);
    }

    /**
     * The same update with the index the scan matched at, if this is the terminal one.
     *
     * <p>Applied after the fact because the sighting happens at the end of the scan and the
     * outcome only exists at the end of the GATT exchange - which may be several retries later,
     * and may fail. Attaching it here means every DONE that followed a real sighting carries it,
     * without the retry logic having to know the index exists.
     */
    public BleSoundTriggerUpdate withMatchedKeyIndex(final Integer keyIndex) {
        if (this.phase != BleSoundTriggerPhase.DONE || keyIndex == null) {
            return this;
        }
        return done(new BleSoundTriggerResult(this.result.getStatus(), this.result.getProtocol(),
                this.result.getMessage(), keyIndex));
    }
}
