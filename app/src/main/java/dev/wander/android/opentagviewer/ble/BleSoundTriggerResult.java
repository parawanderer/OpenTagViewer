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
     * The BLE address the accessory was found advertising as, or null if it was not found.
     *
     * <p><b>Reported rather than acted on, deliberately.</b> A sighting can pin the alignment,
     * which is what keeps the next scan cheap - but persisting it means Python and the database,
     * and this package has neither. The caller hands it to
     * {@code BeaconRepository#recordAccessorySighting}, which is where every other
     * accessory-state write already lives.
     *
     * <p><b>The address rather than the key index {@code currentMacAddresses} paired it with.</b>
     * That index is only trustworthy when the address came from a primary key - a secondary
     * key's index is a lower bound, not the true one - and this package has no way to tell the
     * two apart; only {@code main.py:recordAccessorySeen} can, by re-deriving the key at this
     * address and checking its type. Passing the raw index on would risk the caller trusting an
     * index this package cannot vouch for.
     *
     * <p>Set whenever the scan matched, <b>including when the GATT handshake then failed</b>: the
     * tag really was there, and that is true regardless of whether it made a noise.
     */
    private final String matchedMac;

    /**
     * An outcome from a stage that cannot know which candidate answered, which is every stage
     * but the scan.
     *
     * <p>{@link BleGattSoundTrigger} is handed a device and told to talk to it; which candidate
     * that device was is not its business and not in its scope. It reports the outcome, and
     * {@link BleAccessorySoundTrigger#playSound} - the one place that holds both the candidate
     * set and the device - attaches the address afterwards via
     * {@link BleSoundTriggerUpdate#withMatchedMac}.
     */
    public BleSoundTriggerResult(
            final BleSoundTriggerStatus status, final String protocol, final String message) {
        this(status, protocol, message, null);
    }
}
