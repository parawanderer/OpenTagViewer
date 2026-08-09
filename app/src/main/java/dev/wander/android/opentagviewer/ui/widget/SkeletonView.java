package dev.wander.android.opentagviewer.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.Nullable;

/**
 * A placeholder bar that pulses while the real content is still loading.
 * <br>
 * Screens here fill themselves from RxJava chains backed by DataStore and Room, so their
 * text views start empty and get their content a frame or several later. Left alone that
 * reads as the page glitching: labels appear from nowhere and the surrounding card resizes
 * under the reader. Reserving the space up front and animating it removes both the jump and
 * the ambiguity about whether anything is happening.
 * <br>
 * Self-driving: it animates whenever it is attached and visible, and stops when detached or
 * hidden, so callers only toggle visibility. An animator left running on a detached view is
 * a leak that also quietly burns frames, which is why this is a view rather than a helper
 * someone has to remember to stop.
 */
public class SkeletonView extends View {

    private static final long PULSE_DURATION_MS = 900;
    private static final float MIN_ALPHA = 0.25f;
    private static final float MAX_ALPHA = 0.65f;

    @Nullable
    private ValueAnimator animator;

    public SkeletonView(Context context) {
        super(context);
    }

    public SkeletonView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public SkeletonView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        this.startPulsing();
    }

    @Override
    protected void onDetachedFromWindow() {
        this.stopPulsing();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        if (visibility == VISIBLE && isAttachedToWindow()) {
            this.startPulsing();
        } else {
            this.stopPulsing();
        }
    }

    private void startPulsing() {
        if (this.animator != null || getVisibility() != VISIBLE) {
            return;
        }

        ValueAnimator pulse = ValueAnimator.ofFloat(MIN_ALPHA, MAX_ALPHA);
        pulse.setDuration(PULSE_DURATION_MS);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.addUpdateListener(a -> setAlpha((float) a.getAnimatedValue()));
        pulse.start();

        this.animator = pulse;
    }

    private void stopPulsing() {
        if (this.animator == null) {
            return;
        }

        this.animator.cancel();
        this.animator = null;
        // Otherwise it keeps whatever alpha the animation was cancelled on.
        setAlpha(MAX_ALPHA);
    }
}
