package dev.wander.android.opentagviewer.ui;

import androidx.annotation.DrawableRes;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.data.model.BeaconInformation;

/**
 * Which icon stands in for a tag that has no emoji.
 *
 * <p><b>Every tag without an emoji used to show Apple's logo</b>, including a Chipolo and
 * including an OpenHaystack-style tag whose keys have never been near an Apple account. That is
 * not a bland default, it is a wrong one - the icon is the only place the app says anything about
 * where a tag came from, and it was saying the same thing about all of them.
 *
 * <p><b>One place, because there are three surfaces.</b> The map carousel, the device list and
 * the device screen each render this, and each used to name {@code R.drawable.apple} itself.
 * Three copies of a default is how two of them end up stale.
 *
 * <p>Only reached when {@link BeaconInformation#isEmojiFilled()} is false - anything the user or
 * their Apple device has set wins, always. This is the fallback, not a category label.
 */
public final class BeaconIcon {

    private BeaconIcon() {
    }

    /**
     * Apple's Bluetooth SIG company identifier, which is what an {@code OwnedBeacons} plist
     * records for hardware Apple made.
     */
    private static final int APPLE_VENDOR_ID = 76;

    /**
     * The icon for a tag with no emoji of its own.
     *
     * <p><b>Decided from stored fields, not from the shared heuristic.</b> The heuristic gives a
     * better <i>name</i>, but it costs a Python interpreter and answers asynchronously - and an
     * icon that arrives late is an icon that visibly changes under the user. The vendor id is on
     * the row already and answers the only question this needs: who made it.
     *
     * <p>An unknown vendor is treated as third-party rather than as Apple. That is the honest
     * way round: claiming Apple for something we cannot identify is exactly the wrong answer
     * this replaces, and the arcs read as "a findable tag" for anything in the network.
     */
    @DrawableRes
    public static int forBeacon(final BeaconInformation beacon) {
        if (beacon.isCustomAccessory()) {
            return R.drawable.tag_self_generated;
        }
        if (beacon.getVendorId() == APPLE_VENDOR_ID) {
            return R.drawable.apple;
        }
        return R.drawable.tag_third_party;
    }
}
