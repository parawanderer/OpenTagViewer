package dev.wander.android.opentagviewer.ui.maps;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.widget.ImageView;

import androidx.annotation.Nullable;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.ble.FindMyAdvertisement;
import dev.wander.android.opentagviewer.ble.NearbyTagLabel;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Puts a sighting's battery level on a tag card's status line as an icon.
 *
 * <p>Split out of {@code MapsActivity#showNearbyStatusOn} so a layout test can drive it on an
 * inflated card without starting the activity, which needs Maps and an Apple session.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NearbyBatteryIcon {

    /**
     * Shows the icon for {@code level}, or hides it when there is none to show.
     *
     * <p>{@code setImageResource} loads through the view's own context, so the vector's
     * {@code ?attr/colorControlNormal} resolves against the card's theme. The content description
     * carries the word the icon replaced, so a screen reader still says "Battery medium".
     *
     * @param level null for a sighting without battery flags; Google trackers encrypt theirs.
     */
    public static void show(final ImageView icon, @Nullable final FindMyAdvertisement.BatteryLevel level) {
        if (level == null) {
            icon.setVisibility(GONE);
            icon.setContentDescription(null);
            return;
        }
        icon.setImageResource(NearbyTagLabel.batteryIcon(level));
        icon.setContentDescription(icon.getContext().getString(
                R.string.battery_icon_description,
                icon.getContext().getString(NearbyTagLabel.shortBatteryLabel(level))));
        icon.setVisibility(VISIBLE);
    }
}
