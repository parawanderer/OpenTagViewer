package dev.wander.android.opentagviewer.ble;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Outcome of one {@link BleAccessorySoundTrigger#playSound} attempt. */
@AllArgsConstructor
@Getter
public class BleSoundTriggerResult {
    private final BleSoundTriggerStatus status;

    /** Which GATT protocol answered - "DULT", "FindMy (fd44)" or "AirTag" - null unless SUCCESS. */
    private final String protocol;

    /** Detail for logs, in whatever language the underlying failure happened to arrive in. */
    private final String message;

    /**
     * The rolling-key index the accessory was found advertising at, or null if it was not found.
     *
     * <p><b>Reported rather than acted on, deliberately.</b> A sighting pins the alignment, which
     * is what keeps the next scan cheap - but persisting it means Python and the database, and
     * this package has neither. The caller hands it to
     * {@code BeaconRepository#recordAccessorySighting}, which is where every other
     * accessory-state write already lives.
     *
     * <p>Set whenever the scan matched, <b>including when the GATT handshake then failed</b>: the
     * tag really was there, and that is true regardless of whether it made a noise.
     */
    private final Integer matchedKeyIndex;

    /**
     * An outcome from a stage that cannot know the index, which is every stage but the scan.
     *
     * <p>{@link BleGattSoundTrigger} is handed a device and told to talk to it; which candidate
     * that device was is not its business and not in its scope. It reports the outcome, and
     * {@link BleAccessorySoundTrigger#playSound} - the one place that holds both the candidate
     * map and the device - attaches the index afterwards via
     * {@link BleSoundTriggerUpdate#withMatchedKeyIndex}.
     */
    public BleSoundTriggerResult(
            final BleSoundTriggerStatus status, final String protocol, final String message) {
        this(status, protocol, message, null);
    }
}
