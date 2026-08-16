package dev.wander.android.opentagviewer.ui.login;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

import dev.wander.android.opentagviewer.ui.login.StepTransition.Direction;

/**
 * What the sign-in flow's step transition guarantees, separately from how it looks.
 *
 * <p>The login flow tests drive these transitions for real, but they cannot tell an animation
 * apart from a plain visibility swap - which is the whole question here. An animation that got
 * the final state wrong, or left a view half-faded for the next time it was shown, would pass
 * every one of them.
 */
@RunWith(AndroidJUnit4.class)
public class StepTransitionTest {

    private static Context context() {
        return getInstrumentation().getTargetContext();
    }

    /** Animations touch view properties, so everything here runs where views live. */
    private static void onMainThread(final Runnable what) {
        getInstrumentation().runOnMainSync(what);
    }

    private static View shown() {
        final View view = new View(context());
        view.setVisibility(View.VISIBLE);
        return view;
    }

    private static View hidden() {
        final View view = new View(context());
        view.setVisibility(View.GONE);
        return view;
    }

    /**
     * <b>"Remove animations" is honoured</b> - the flow stops animating rather than animating
     * quickly.
     *
     * <p>Settings &gt; Accessibility &gt; Remove animations zeroes the global animation scales,
     * which is what {@link ValueAnimator#areAnimatorsEnabled()} reports. Somebody who turns
     * that on has usually done so because motion makes the screen hard to use or makes them
     * unwell, so shortening the duration is not good enough: nothing may move at all.
     *
     * <p>Forced here through the same scale the setting writes, because a test cannot toggle an
     * accessibility preference and a claim in a comment is not a guarantee.
     */
    @Test
    public void removeAnimationsStopsTheStepsMovingAtAll() throws Exception {
        final View outgoing = shown();
        final View incoming = hidden();

        withAnimatorsOff(() ->
                onMainThread(() -> StepTransition.swap(outgoing, incoming, Direction.FORWARD)));

        assertEquals("nothing should have been left animating out",
                View.GONE, outgoing.getVisibility());
        assertEquals(View.VISIBLE, incoming.getVisibility());
        assertSettled(outgoing);
        assertSettled(incoming);
    }

    /**
     * <b>The step being left goes at once, animating or not.</b>
     *
     * <p>Not a detail - it is why the transition looks right. The step containers are siblings
     * in a vertical {@code LinearLayout}, so one still fading out still occupies its height,
     * and the arriving step gets laid out below it and then jumps into place when the old one
     * finally goes. Leaving it visible for even one animation is visible as a snap.
     *
     * <p>It is also what keeps Espresso out of trouble here, and that is worth knowing because
     * the obvious assumption is wrong: {@code testOptions.animationsDisabled} passes
     * {@code --no-window-animation} to {@code am instrument}, which turns off window and
     * transition animations and leaves {@code animator_duration_scale} - what
     * {@link android.view.ViewPropertyAnimator} obeys - exactly as the device had it. So these
     * animations do run under test, and only this leaves no window in which two steps are both
     * on screen.
     */
    @Test
    public void theStepBeingLeftIsGoneBeforeTheCallReturns() throws Exception {
        final View outgoing = shown();

        withAnimatorsOn(() ->
                onMainThread(() -> StepTransition.swap(outgoing, hidden(), Direction.FORWARD)));

        assertEquals(
                "the step being left may not linger in the layout",
                View.GONE,
                outgoing.getVisibility());
        assertSettled(outgoing);
    }

    /** Not navigation, so nothing moves at all. */
    @Test
    public void aSwapWithNoDirectionDoesNotAnimate() {
        final View outgoing = shown();
        final View incoming = hidden();

        onMainThread(() -> StepTransition.swap(outgoing, incoming, Direction.NONE));

        assertEquals(View.GONE, outgoing.getVisibility());
        assertEquals(View.VISIBLE, incoming.getVisibility());
        assertSettled(incoming);
        assertSettled(outgoing);
    }

    /**
     * True on every path, animated or not: the step being navigated to is on screen by the time
     * the call returns, so nothing downstream has to wait for an animation to answer "which
     * step am I on".
     */
    @Test
    public void theArrivingStepIsVisibleBeforeTheCallReturns() throws Exception {
        for (final Direction direction : Direction.values()) {
            final View incoming = hidden();

            withAnimatorsOn(() ->
                    onMainThread(() -> StepTransition.swap(shown(), incoming, direction)));

            assertEquals(
                    "arriving step not visible for " + direction,
                    View.VISIBLE,
                    incoming.getVisibility());
        }
    }

    @Test
    public void forwardBringsTheNextStepInFromTheRight() throws Exception {
        final View incoming = hidden();

        withAnimatorsOn(() ->
                onMainThread(() -> StepTransition.swap(shown(), incoming, Direction.FORWARD)));

        assertTrue(
                "going forwards, the next step should start off to the right",
                incoming.getTranslationX() > 0f);
    }

    @Test
    public void backBringsThePreviousStepInFromTheLeft() throws Exception {
        final View incoming = hidden();

        withAnimatorsOn(() ->
                onMainThread(() -> StepTransition.swap(shown(), incoming, Direction.BACK)));

        assertTrue(
                "going back, the previous step should start off to the left",
                incoming.getTranslationX() < 0f);
    }

    /**
     * The heading names the step, so it travels with it rather than switching under it.
     *
     * <p>Its text is set by the caller before this runs and is never touched here, so what the
     * screen says is readable the moment the step changes - only the movement is decorative.
     */
    @Test
    public void theHeadingTravelsWithTheStepItNames() throws Exception {
        final View heading = shown();

        withAnimatorsOn(() -> onMainThread(() -> StepTransition.enter(heading, Direction.FORWARD)));

        assertTrue("the heading should arrive from the same side as the step",
                heading.getTranslationX() > 0f);
        assertEquals("the heading never leaves the screen", View.VISIBLE, heading.getVisibility());
    }

    /**
     * A step interrupted mid-animation has to come back whole.
     *
     * <p>These containers are reused rather than re-inflated, so a view abandoned at alpha 0
     * would be shown again as nothing at all - which reads as a blank screen, not as a bug in
     * an animation.
     */
    @Test
    public void aStepInterruptedMidAnimationIsNotLeftHalfFaded() throws Exception {
        final View page = hidden();

        withAnimatorsOn(() -> onMainThread(() -> {
            StepTransition.swap(shown(), page, Direction.FORWARD);
            // Interrupted before it could finish, which is what a fast tap does.
            StepTransition.swap(page, null, Direction.NONE);
        }));

        assertEquals(View.GONE, page.getVisibility());
        assertSettled(page);
    }

    /** Leaving a step with nothing to replace it - the spinner taking over - is allowed. */
    @Test
    public void aStepCanBeLeftWithNothingArriving() {
        final View outgoing = shown();

        onMainThread(() -> StepTransition.swap(outgoing, null, Direction.FORWARD));

        assertEquals(View.GONE, outgoing.getVisibility());
    }

    /** As "Remove animations" leaves the device. */
    private static void withAnimatorsOff(final Runnable what) throws Exception {
        withDurationScale(0f, () -> {
            assertFalse("the scale did not take effect, so this proves nothing",
                    ValueAnimator.areAnimatorsEnabled());
            what.run();
        });
    }

    /**
     * As a device with animations on.
     *
     * <p>Needed explicitly rather than assumed: the scale is a global, and by the time this
     * class runs inside the whole suite something earlier has already turned animators off. A
     * test of what an animation does cannot be at the mercy of what ran before it.
     */
    private static void withAnimatorsOn(final Runnable what) throws Exception {
        withDurationScale(1f, () -> {
            assertTrue("the scale did not take effect, so this proves nothing",
                    ValueAnimator.areAnimatorsEnabled());
            what.run();
        });
    }

    /**
     * Run something at a given animator duration scale, and put the scale back afterwards
     * however that turns out.
     *
     * <p>By reflection because there is no public setter, and there should not be: the scale is
     * a global the user owns, and an app is expected to read it rather than write it - which is
     * the right rule everywhere except in the tests proving the app reads it. If a future
     * platform closes this off for good, the honest response is to delete these tests rather
     * than to assert the behaviour without checking it.
     */
    private static void withDurationScale(final float scale, final Runnable what)
            throws Exception {
        final Method setDurationScale =
                ValueAnimator.class.getDeclaredMethod("setDurationScale", float.class);
        setDurationScale.setAccessible(true);

        final Method getDurationScale =
                ValueAnimator.class.getDeclaredMethod("getDurationScale");
        getDurationScale.setAccessible(true);
        final float previous = (float) getDurationScale.invoke(null);

        setDurationScale.invoke(null, scale);
        try {
            what.run();
        } finally {
            setDurationScale.invoke(null, previous);
        }
    }

    private static void assertSettled(final View view) {
        assertEquals("left offset sideways", 0f, view.getTranslationX(), 0.001f);
        assertEquals("left translucent", 1f, view.getAlpha(), 0.001f);
    }
}
