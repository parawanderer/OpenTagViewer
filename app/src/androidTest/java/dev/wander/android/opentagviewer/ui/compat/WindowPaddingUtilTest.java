package dev.wander.android.opentagviewer.ui.compat;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Where the system-bar insets land.
 *
 * <p><b>This is a screenshot bug that no screenshot test catches</b>, because the insets a test
 * device reports are not the ones that break it: a gesture-navigation phone has a bottom inset of
 * a few dp, and three-button navigation has around 48. So the values are dispatched here rather
 * than waited for, which is also the only way to assert what happens on a *second* delivery.
 *
 * <p>Both failures being pinned have shipped. Padding applied twice grew the gap on every
 * rotation; padding the root of a screen with a bottom sheet left the sheet stopping short of the
 * screen edge, showing a band of the activity's background beneath it and clipping the last row
 * of the list.
 */
@RunWith(AndroidJUnit4.class)
public class WindowPaddingUtilTest {

    private static final int STATUS_BAR = 60;
    private static final int NAV_BAR = 48;

    private Context context;

    @Before
    public void setUp() {
        this.context = getInstrumentation().getTargetContext();
    }

    private static WindowInsetsCompat systemBars() {
        return new WindowInsetsCompat.Builder()
                .setInsets(
                        WindowInsetsCompat.Type.systemBars(),
                        Insets.of(0, STATUS_BAR, 0, NAV_BAR))
                .build();
    }

    /** Insets arrive on their own schedule, so a test has to hand them over itself. */
    private static void deliverInsetsTo(final View view) {
        ViewCompat.dispatchApplyWindowInsets(view, systemBars());
    }

    @Test
    public void awholeScreenIsKeptClearOfBothBars() {
        final View screen = new FrameLayout(this.context);

        WindowPaddingUtil.insetForSystemBars(screen);
        deliverInsetsTo(screen);

        assertEquals("the status bar would cover the heading", STATUS_BAR, screen.getPaddingTop());
        assertEquals("a button here would be behind the navigation bar",
                NAV_BAR, screen.getPaddingBottom());
    }

    /**
     * <b>And the padding a layout already asked for is kept rather than replaced.</b>
     */
    @Test
    public void bitaddsToThePaddingTheLayoutAlreadyHad() {
        final View screen = new FrameLayout(this.context);
        screen.setPadding(0, 7, 0, 11);

        WindowPaddingUtil.insetForSystemBars(screen);
        deliverInsetsTo(screen);

        assertEquals(STATUS_BAR + 7, screen.getPaddingTop());
        assertEquals(NAV_BAR + 11, screen.getPaddingBottom());
    }

    /**
     * <b>Delivered twice, the gap does not double.</b>
     *
     * <p>Insets arrive more than once - a rotation, a keyboard, switching to three-button
     * navigation - and reading the view's current padding inside the listener would add to a
     * value that already includes the last delivery.
     */
    @Test
    public void ctwodeliveriesDoNotStack() {
        final View screen = new FrameLayout(this.context);
        screen.setPadding(0, 7, 0, 11);

        WindowPaddingUtil.insetForSystemBars(screen);
        deliverInsetsTo(screen);
        deliverInsetsTo(screen);

        assertEquals(STATUS_BAR + 7, screen.getPaddingTop());
        assertEquals(NAV_BAR + 11, screen.getPaddingBottom());
    }

    /**
     * <b>The paired form puts the bottom on the content and leaves the root's alone.</b>
     *
     * <p>The history screen's sheet has to reach the bottom of the display. Padding its root
     * instead shortened everything inside it, so the sheet stopped above the navigation bar with
     * the activity's background showing beneath it - a white band under a grey sheet - and the
     * last row of the list clipped by the same gap.
     */
    @Test
    public void dthepairedFormGivesTheBottomToTheContent() {
        final View root = new FrameLayout(this.context);
        final View list = new FrameLayout(this.context);

        WindowPaddingUtil.insetForSystemBars(root, list);
        deliverInsetsTo(root);

        assertEquals("the root still clears the status bar", STATUS_BAR, root.getPaddingTop());
        assertEquals("the root must reach the bottom edge, or the sheet stops short",
                0, root.getPaddingBottom());
        assertEquals("the list has to clear the navigation bar itself",
                NAV_BAR, list.getPaddingBottom());
    }

    /** The content's own padding survives, and the root's does too. */
    @Test
    public void ethepairedFormKeepsBothViewsOwnPadding() {
        final View root = new FrameLayout(this.context);
        final View list = new FrameLayout(this.context);
        root.setPadding(0, 3, 0, 5);
        list.setPadding(0, 0, 0, 9);

        WindowPaddingUtil.insetForSystemBars(root, list);
        deliverInsetsTo(root);

        assertEquals(STATUS_BAR + 3, root.getPaddingTop());
        assertEquals("the root's own bottom padding is not the navigation bar's, and stays",
                5, root.getPaddingBottom());
        assertEquals(NAV_BAR + 9, list.getPaddingBottom());
    }

    /** And it does not stack on a second delivery either. */
    @Test
    public void fthepairedFormDoesNotStackOnTheContent() {
        final View root = new FrameLayout(this.context);
        final View list = new FrameLayout(this.context);
        list.setPadding(0, 0, 0, 9);

        WindowPaddingUtil.insetForSystemBars(root, list);
        deliverInsetsTo(root);
        deliverInsetsTo(root);

        assertEquals(NAV_BAR + 9, list.getPaddingBottom());
    }
}
