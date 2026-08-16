package dev.wander.android.opentagviewer.ui.login;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.animation.ValueAnimator;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator;

/**
 * Sliding the next step of the sign-in flow in, in the direction the user is travelling.
 *
 * <p>The whole flow is one activity swapping visibility on sibling containers, so there is no
 * activity or fragment transition to hang an animation off. This does it directly: the step
 * arriving slides in from the edge it would have come from and fades up.
 *
 * <p><b>Only the arriving step is animated, and the one being left is hidden outright.</b> That
 * is not a shortcut - those containers are siblings in a vertical {@code LinearLayout}, so a
 * step still fading out still occupies its height, and the arriving one gets laid out
 * underneath it and then jumps up the moment the old one is finally gone. Cross-fading them
 * properly needs them stacked in a {@code FrameLayout} instead; until somebody wants that badly
 * enough to restructure the layout, the old step going at once is the honest version.
 *
 * <p>It buys two other things. The flow's state is readable the instant a call returns, so no
 * test has to wait for an animation, and there is no window in which two steps are both on
 * screen for Espresso to catch.
 *
 * <p>The slide is deliberately short. These containers sit inside a {@code ScrollView} whose
 * children are {@code match_parent}, so anything translated sideways is clipped at the parent's
 * edge; 30dp keeps the clipped sliver under the part of the fade where it is not visible
 * anyway. A full-width slide would look like the view was being cut in half.
 */
public final class StepTransition {

    /**
     * Which way the user is travelling, which is the only thing the caller has to decide.
     *
     * <p>{@link #NONE} is not "no preference" - it means the screen is being drawn rather than
     * navigated, such as the first render or a restore after rotation. Animating those makes
     * the app look like it is replaying steps the user did not take.
     */
    public enum Direction {
        FORWARD,
        BACK,
        NONE
    }

    /** Long enough to read as movement, short enough not to sit between the user and the form. */
    private static final long DURATION_MS = 220L;

    private static final float SLIDE_DP = 30f;

    private StepTransition() {}

    /**
     * Hide {@code outgoing} and show {@code incoming}, sliding the latter in.
     *
     * <p>Either may be null - leaving a step to show a spinner has no incoming view, and the
     * first step of the flow has no outgoing one.
     *
     * <p><b>Both views are in their final state by the time this returns</b>, animation or no
     * animation. Nothing about the flow is allowed to depend on an animation having run.
     */
    public static void swap(
            @Nullable final View outgoing,
            @Nullable final View incoming,
            final Direction direction) {
        if (outgoing == incoming) {
            return;
        }

        if (outgoing != null) {
            // At once, and not animated: see the class comment. It has to leave the layout in
            // the same frame the next step enters it, or the next step is positioned below it.
            outgoing.animate().cancel();
            settle(outgoing, GONE);
        }

        if (incoming == null) {
            return;
        }

        incoming.animate().cancel();
        settle(incoming, VISIBLE);

        if (direction != Direction.NONE && animatorsEnabled()) {
            enter(incoming, direction);
        }
    }

    /**
     * Slide a view in from the edge it would have travelled from, fading up as it arrives.
     *
     * <p>Public because the step is not the only thing that moves: the heading above it names
     * the step, so it travels with it. It is already on screen and stays that way - only its
     * offset and opacity are touched - so the caller can set whatever it says beforehand and
     * nothing has to wait for the animation to read it.
     */
    public static void enter(@Nullable final View view, final Direction direction) {
        if (view == null || direction == Direction.NONE || !animatorsEnabled()) {
            return;
        }

        final float slide = dpToPx(view, SLIDE_DP);

        view.animate().cancel();
        view.setTranslationX(direction == Direction.FORWARD ? slide : -slide);
        view.setAlpha(0f);
        view.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(DURATION_MS)
                .setInterpolator(new LinearOutSlowInInterpolator())
                .withEndAction(() -> settle(view, view.getVisibility()));
    }

    /** Put a view back where the layout expects it, at the given visibility. */
    private static void settle(final View view, final int visibility) {
        view.setTranslationX(0f);
        view.setAlpha(1f);
        view.setVisibility(visibility);
    }

    /**
     * Whether animating is worth doing at all.
     *
     * <p>False when the user has turned animations off in developer or accessibility settings,
     * and when instrumented tests run - AGP's {@code animationsDisabled} sets the same scale.
     * The check needs API 26; below that the setting existed but was not readable, so the
     * animation runs, which is what those versions did before this class existed.
     */
    private static boolean animatorsEnabled() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled();
    }

    private static float dpToPx(final View view, final float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, view.getResources().getDisplayMetrics());
    }
}
