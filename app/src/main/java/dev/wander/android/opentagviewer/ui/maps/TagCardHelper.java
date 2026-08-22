package dev.wander.android.opentagviewer.ui.maps;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.res.ColorStateList;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.Map;

import dev.wander.android.opentagviewer.R;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TagCardHelper {
    public static final String TAG = TagCardHelper.class.getSimpleName();

    public static void toggleRefreshLoading(FrameLayout container, boolean isLoading) {
        try {
            ImageView icon = container.findViewById(R.id.refresh_icon);
            CircularProgressIndicator progressIndicator = container.findViewById(R.id.refresh_loading_indicator);

            if (isLoading) {
                icon.setVisibility(GONE);
                progressIndicator.setVisibility(VISIBLE);
            } else {
                icon.setVisibility(VISIBLE);
                progressIndicator.setVisibility(GONE);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failure while trying to toggle the loading status on a refresh button", e);
        }
    }

    /**
     * Shows whether continuous ping (repeated scan + play-sound-nearby) is running for this
     * card's tag - the icon becomes a stop glyph, tinted with the theme's error colour so it
     * reads as "tap to stop" at a glance, and the label swaps to match.
     */
    public static void toggleRingActive(FrameLayout container, boolean active) {
        try {
            ImageView icon = container.findViewById(R.id.perform_ring_icon);
            TextView label = container.findViewById(R.id.ringText);

            icon.setImageResource(active ? R.drawable.close_24px : R.drawable.volume_24);
            icon.setImageTintList(ColorStateList.valueOf(active
                    ? MaterialColors.getColor(container, com.google.android.material.R.attr.colorError)
                    : MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant)));
            label.setText(active ? R.string.stop_ringing : R.string.do_ring);
        } catch (Exception e) {
            Log.e(TAG, "Failure while trying to toggle the ring button's active state", e);
        }
    }

    /**
     * Updates only the ring button's label text, leaving its icon/tint alone - for showing
     * continuous ping's current phase (scanning/connecting/sending) between the on/off states
     * {@link #toggleRingActive} sets.
     */
    public static void setRingLabel(FrameLayout container, CharSequence text) {
        try {
            TextView label = container.findViewById(R.id.ringText);
            label.setText(text);
        } catch (Exception e) {
            Log.e(TAG, "Failure while trying to update the ring button's label", e);
        }
    }

    /**
     * Swaps the ring icon for a spinner while a scan/connect/trigger attempt is actually in
     * flight - the label alone ("Scanning...", "Connecting...") was mistaken for a stall,
     * since it can sit on screen for several seconds with nothing else moving. Independent of
     * {@link #toggleRingActive}: this toggles per attempt, that toggles per on/off.
     */
    public static void setRingLoading(FrameLayout container, boolean loading) {
        try {
            ImageView icon = container.findViewById(R.id.perform_ring_icon);
            CircularProgressIndicator progressIndicator = container.findViewById(R.id.ring_loading_indicator);

            icon.setVisibility(loading ? GONE : VISIBLE);
            progressIndicator.setVisibility(loading ? VISIBLE : GONE);
        } catch (Exception e) {
            Log.e(TAG, "Failure while trying to toggle the loading status on the ring button", e);
        }
    }

    public static void toggleRefreshLoadingAll(Map<String, FrameLayout> containers, boolean isLoading) {
        try {
            for (var frameLayout : containers.values()) {
                toggleRefreshLoading(frameLayout, isLoading);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failure while trying to toggle the loading status on the refresh buttons", e);
        }

    }
}
