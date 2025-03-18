package dev.wander.android.opentagviewer.ui.maps;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.util.Log;
import android.widget.FrameLayout;
import android.widget.ImageView;

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
