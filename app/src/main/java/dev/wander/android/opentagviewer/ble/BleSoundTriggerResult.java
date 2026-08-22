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
}
