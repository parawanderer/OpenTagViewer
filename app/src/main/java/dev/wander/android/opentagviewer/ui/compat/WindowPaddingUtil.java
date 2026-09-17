package dev.wander.android.opentagviewer.ui.compat;

import android.view.View;
import android.view.ViewGroup;

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
     * {@link #insetForSystemBars(View)}, for a scrolling screen somebody types into: it also gets
     * out of the keyboard's way.
     *
     * <p><b>Android 15 stopped doing this for the app.</b> Targeting SDK 35 makes every window edge
     * to edge, and an edge-to-edge window is not resized when the keyboard opens - so
     * {@code adjustResize}, which is what used to push a text field up above the keyboard, quietly
     * does nothing. The keyboard is reported as an inset like the navigation bar, and a screen that
     * ignores it has its field drawn underneath. The sign-in screen's Anisette server field was
     * covered that way: the text being typed was hidden behind the keys typing it.
     *
     * <p><b>A margin, not padding, and that is the fix rather than a detail.</b> A
     * {@code ScrollView} brings the focused field into view when it gets <i>shorter</i> - the same
     * thing a resized window used to cause. Padding its bottom leaves its height alone, so the
     * field would stay under the keyboard with the content merely allowed to scroll past it.
     *
     * <p>Only the keyboard's own inset is used, so this is a no-op wherever the system still
     * resizes the window: there, nothing of the window is under the keyboard and the inset is zero.
     *
     * @param scrollingScreen the scrolling root, whose parent gives it margins
     */
    public static void insetForSystemBarsAndKeyboard(final View scrollingScreen) {
        final int ownLeft = scrollingScreen.getPaddingLeft();
        final int ownTop = scrollingScreen.getPaddingTop();
        final int ownRight = scrollingScreen.getPaddingRight();
        final int ownBottom = scrollingScreen.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(scrollingScreen, (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            final int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;

            // The keyboard covers the navigation bar, so while it is up the bar needs no room.
            v.setPadding(
                    ownLeft + bars.left,
                    ownTop + bars.top,
                    ownRight + bars.right,
                    ownBottom + (keyboard > 0 ? 0 : bars.bottom)
            );

            final ViewGroup.LayoutParams params = v.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams
                    && ((ViewGroup.MarginLayoutParams) params).bottomMargin != keyboard) {
                ((ViewGroup.MarginLayoutParams) params).bottomMargin = keyboard;
                v.setLayoutParams(params);
            }
            return insets;
        });
    }

    /**
     * For a screen whose bottom edge belongs to something that has to reach it.
     *
     * <p><b>A bottom sheet is the case this exists for.</b> Padding the root's bottom shortens
     * everything inside it, so the sheet stops above the navigation bar and the activity's own
     * background shows through underneath - a strip in the window's colour, below a sheet in the
     * sheet's colour, which reads as a rendering fault rather than as padding. The history
     * screen shipped that way: a grey sheet, its last row clipped, and a white band under it.
     *
     * <p><b>Both views are arguments because one of them always gets forgotten otherwise.</b>
     * That is not hypothetical - see {@link #insetForSystemBars(View)}, which exists in that
     * shape because a top-only helper was applied to seven screens and the matching bottom call
     * to one. A caller here cannot pad the top and quietly skip the bottom: there is nowhere to
     * put the omission.
     *
     * <p>{@code content} is padded rather than the sheet itself, so the sheet's background still
     * runs to the bottom of the screen. Give it {@code clipToPadding="false"} when it scrolls,
     * or the padding becomes a dead band the list cannot use instead of somewhere the last row
     * can scroll into.
     *
     * @param root    Gets the status bar, and the left and right insets. Not the bottom.
     * @param content Gets the bottom inset, added to whatever padding it already asks for.
     */
    public static void insetForSystemBars(final View root, final View content) {
        final int rootLeft = root.getPaddingLeft();
        final int rootTop = root.getPaddingTop();
        final int rootRight = root.getPaddingRight();
        final int rootBottom = root.getPaddingBottom();

        final int contentLeft = content.getPaddingLeft();
        final int contentTop = content.getPaddingTop();
        final int contentRight = content.getPaddingRight();
        final int contentBottom = content.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            v.setPadding(
                    rootLeft + bars.left,
                    rootTop + bars.top,
                    rootRight + bars.right,
                    rootBottom
            );
            content.setPadding(
                    contentLeft,
                    contentTop,
                    contentRight,
                    contentBottom + bars.bottom
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
