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
     * Keep a screen's content clear of <b>both</b> system bars.
     *
     * <p><b>The bottom is the one that gets forgotten, and it is the one people notice.</b> The
     * theme draws under a transparent navigation bar, so anything at the bottom of a screen ends
     * up beneath it - and unlike a clipped heading, a button underneath the bar is not merely
     * ugly. It is hard to press, or impossible: the bar takes the touch. Reported on a Samsung
     * phone as "any button we put at the bottom of the page is barely to not clickable", with a
     * screenshot of the keychain unlock screen's Unlock button sitting behind the gesture pill.
     *
     * <p><b>Both bars in one call, because two calls is what let this happen.</b> There used to
     * be a top-only helper; it was applied to seven screens and the bottom inset to one, and
     * nothing about writing the first suggests you owe the second. Anything that pads for the
     * status bar has the same problem at the other end of the screen, so the top-only version
     * is gone rather than left available to be called again.
     *
     * <p>The view's own padding is kept and the insets are added to it, so a layout that already
     * asks for breathing room does not lose it - and the values are read once, here, rather than
     * inside the listener. Insets arrive more than once (a rotation, a keyboard, switching to
     * three-button navigation), and adding to the current padding each time would grow the gap
     * on every delivery.
     *
     * <p>Not for a screen that deliberately draws edge to edge. The map is the example: it wants
     * tiles under the bar and pads only the card row above it, with
     * {@link #insertUIBottomPadding}.
     */
    public static void insetForSystemBars(final View view) {
        final int ownLeft = view.getPaddingLeft();
        final int ownTop = view.getPaddingTop();
        final int ownRight = view.getPaddingRight();
        final int ownBottom = view.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    ownLeft + bars.left,
                    ownTop + bars.top,
                    ownRight + bars.right,
                    ownBottom + bars.bottom
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
