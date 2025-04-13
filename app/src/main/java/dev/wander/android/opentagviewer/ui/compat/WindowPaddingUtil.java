package dev.wander.android.opentagviewer.ui.compat;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WindowPaddingUtil {
    /**
     * In UIs like Samsung Galaxy S25 Ultra, the top padding under the top list of icons in the UI
     * (the notifications, time, battery, ...) is absent, which results in a top bar that is too small
     *
     * @param rootView      The view that holds all of the UI for a given activity.
     */
    public static void insertUITopPadding(View rootView) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            v.setPadding(
                    0,
                    statusBarInsets.top,
                    0,
                    0
            );
            return insets;
        });
    }
}
