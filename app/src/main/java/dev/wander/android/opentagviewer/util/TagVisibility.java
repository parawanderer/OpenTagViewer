package dev.wander.android.opentagviewer.util;

import java.util.List;
import java.util.stream.Collectors;

import dev.wander.android.opentagviewer.data.model.BeaconInformation;

/**
 * Which tags the app shows, and therefore which tags it looks for.
 *
 * <p><b>One rule, in one place, because hiding and not-fetching have to be the same decision.</b>
 * They are two behaviours - a row missing from a list, and a request never sent - and it would be
 * entirely possible to implement them separately and have them disagree. Both ways round are bad:
 * a hidden tag that is still searched for burns the fetch budget on something nobody can see, and
 * a visible tag that is never searched for is a row that silently never updates, which is the
 * exact complaint this whole setting exists to prevent.
 *
 * <p>So both call this, and there is nothing else to call.
 *
 * @see dev.wander.android.opentagviewer.db.repo.model.UserSettings#showAppleDevices
 */
public final class TagVisibility {

    private TagVisibility() {
    }

    /**
     * The tags to show and to search for, out of everything the database holds.
     *
     * <p>Order is preserved: this filters, it does not sort. What decides the order is
     * {@link TagOrder}, and keeping the two apart means a change to either cannot quietly become
     * a change to both.
     *
     * @param showAppleDevices whether the owner's own iPhones, iPads and Macs are included.
     *                         Off by default - see {@link BeaconInformation#isOwnDevice()} for
     *                         what counts as one, and why an unclassifiable tag counts as an
     *                         accessory rather than a device.
     */
    public static List<BeaconInformation> visible(
            final List<BeaconInformation> all, final boolean showAppleDevices) {

        if (showAppleDevices) {
            return all;
        }

        return all.stream()
                .filter(beacon -> !beacon.isOwnDevice())
                .collect(Collectors.toList());
    }
}
