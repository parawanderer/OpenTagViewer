package dev.wander.android.opentagviewer.ui;

import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.appcompat.content.res.AppCompatResources;

import com.google.android.material.button.MaterialButton;

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

        final int ownDevice = forAnApplemodel(beacon.getModel());
        if (ownDevice != 0) {
            return ownDevice;
        }

        if (beacon.getVendorId() == APPLE_VENDOR_ID) {
            return R.drawable.apple;
        }
        return R.drawable.findmy_accessory;
    }

    /**
     * The picture for one of the owner's own devices, or 0 if this is not one.
     *
     * <p><b>Checked before the vendor id, because the vendor id does not identify these.</b> An
     * iPad's record carries {@code vendorId} -1 and an AirTag's carries 76, so a version of this
     * that asked about the vendor first sent every iPad, iPhone and Mac on the account down the
     * third-party branch and drew them as generic Find My accessories - which is what an account
     * read produces most of, and looked like several identical unknown tags in a list.
     *
     * <p><b>The model is the direct evidence and is only ever there for these.</b> An accessory
     * leaves {@code model} empty and says what it is through its product and vendor ids; a device
     * fills it with an Apple model identifier - {@code iPad13,18}, {@code MacBookAir10,1}. That
     * makes this a lookup rather than a heuristic, which is why it can stay here rather than
     * crossing to {@code hardware.is_own_device}: that answers whether exporting one is a
     * different act, this only chooses a picture, and an icon that arrived asynchronously would
     * visibly change under the user.
     */
    @DrawableRes
    private static int forAnApplemodel(final String model) {
        if (model == null || model.isEmpty()) {
            return 0;
        }

        if (model.startsWith("iPad")) {
            return R.drawable.tablet_24px;
        }
        if (model.startsWith("iPhone") || model.startsWith("iPod")) {
            return R.drawable.smartphone_24px;
        }
        if (model.startsWith("Mac") || model.startsWith("iMac")) {
            return R.drawable.laptop_24px;
        }

        // A Watch, a Vision Pro, something not shipped yet: Apple hardware whose shape this has
        // no drawing of. Apple's own logo says "one of your Apple devices" and is not a guess.
        return R.drawable.apple;
    }

    /**
     * Put the icon on a view, <b>with the right tint or none at all</b>.
     *
     * <p><b>The tint has to be decided here, next to the resource.</b> Two of these drawables are
     * single-colour paths that are flattened to whatever colour the screen wants;
     * {@code findmy_accessory} is not - it carries its own blues and its own themed greys, and a
     * tint over it collapses every path into one colour and produces a featureless blob. So the
     * two answers are one decision, and they cannot drift apart.
     *
     * <p><b>Both branches always set the tint</b>, and that is not defensive tidiness. The device
     * list is a RecyclerView: a row that showed a Find My accessory is handed straight to the
     * next tag, so a version of this that only <i>cleared</i> the tint would leave that row
     * painting an Apple logo with no tint at all - invisible in one theme and wrong in the other,
     * on whichever rows happened to be recycled. It would look like a scrolling bug.
     */
    public static void applyTo(final ImageView view, final BeaconInformation beacon) {
        final int icon = forBeacon(beacon);

        view.setImageResource(icon);
        view.setImageTintList(tintFor(view.getContext(), icon));
    }

    /** The same, for the one surface that shows this on a button rather than an image. */
    public static void applyTo(final MaterialButton button, final BeaconInformation beacon) {
        final int icon = forBeacon(beacon);

        button.setIcon(AppCompatResources.getDrawable(button.getContext(), icon));
        button.setIconTint(tintFor(button.getContext(), icon));
    }

    /**
     * {@code colorOutline} for the flat icons, and null for the one that colours itself.
     *
     * <p>Resolved from the view's own context so it follows the theme the view is actually in -
     * including a context forced to night, which is how this gets rendered both ways in a test
     * without touching the device.
     */
    private static ColorStateList tintFor(final android.content.Context context,
                                          @DrawableRes final int icon) {
        if (icon == R.drawable.findmy_accessory) {
            return null;
        }

        final TypedValue resolved = new TypedValue();
        context.getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorOutline, resolved, true);

        return ColorStateList.valueOf(resolved.data);
    }
}
