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

    /**
     * Keeps a bottom-anchored view clear of the navigation bar.
     *
     * <p>Activities that go edge to edge with
     * {@code WindowCompat.setDecorFitsSystemWindows(window, false)} draw underneath the
     * navigation bar, so anything aligned to the parent's bottom edge ends up behind it. That is
     * invisible on gesture navigation, where the inset is only a few dp, and obvious on
     * three-button navigation, where it is around 48dp.
     *
     * @param view  The bottom-anchored view to pad.
     */
    public static void insertUIBottomPadding(View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    v.getPaddingLeft(),
                    v.getPaddingTop(),
                    v.getPaddingRight(),
                    systemBars.bottom
            );
            return insets;
        });
    }
}
