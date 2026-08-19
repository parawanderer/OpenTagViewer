package dev.wander.android.opentagviewer.python.icloud;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * What one account holds: what can be imported, and what was set aside.
 *
 * <p>Both halves reach the screen. "Fewer tags than expected" and "some of those were never tags"
 * look identical from outside, and the second is the common one - an account's own iPhones and
 * Macs come back in the same records and are dropped for having no private key.
 */
@Getter
@AllArgsConstructor
public class ICloudFetch {
    private final List<ICloudAccessory> accessories;
    private final List<SkippedAccessory> skipped;

    /**
     * An account that owns no tags at all.
     *
     * <p>Worth its own screen. In practice the flow usually stops earlier, at
     * {@link ICloudFailure#NOTHING_TO_RECOVER_FROM}, because an account with no Apple device on
     * it has no escrow record either - but an account that has a Mac and no tags reaches here
     * instead, and lands on the same advice: import a bundle from somebody who owns them.
     */
    public boolean isEmpty() {
        return this.accessories.isEmpty();
    }

    @Getter
    @AllArgsConstructor
    public static class SkippedAccessory {
        private final String beaconId;

        /** What it appears to be, with the evidence rather than a verdict. */
        private final String reason;
    }
}
