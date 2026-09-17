package dev.wander.android.opentagviewer.ui.compat;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

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

    private static final int KEYBOARD = 600;

    private static WindowInsetsCompat withTheKeyboard(final int height) {
        return new WindowInsetsCompat.Builder()
                .setInsets(
                        WindowInsetsCompat.Type.systemBars(),
                        Insets.of(0, STATUS_BAR, 0, NAV_BAR))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, height))
                .build();
    }

    /** A screen inside something that gives it margins, as an activity's content view is. */
    private FrameLayout windowAround(final View screen) {
        final FrameLayout window = new FrameLayout(this.context);
        window.addView(screen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return window;
    }

    /**
     * <b>The keyboard takes room from the bottom of the screen, as a margin.</b>
     *
     * <p>Targeting Android 15 means the window is no longer resized for the keyboard, so nothing
     * else will. A margin rather than padding: a {@code ScrollView} only brings the focused field
     * into view when it gets shorter.
     */
    @Test
    public void gtheKeyboardShortensTheScreenRatherThanCoveringIt() {
        final View screen = new FrameLayout(this.context);
        this.windowAround(screen);

        WindowPaddingUtil.insetForSystemBarsAndKeyboard(screen);
        ViewCompat.dispatchApplyWindowInsets(screen, withTheKeyboard(KEYBOARD));

        final ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) screen.getLayoutParams();
        assertEquals("the field being typed into would be drawn under the keyboard",
                KEYBOARD, params.bottomMargin);
        assertEquals("the keyboard covers the navigation bar, so there is nothing to clear",
                0, screen.getPaddingBottom());
        assertEquals(STATUS_BAR, screen.getPaddingTop());
    }

    /** Closing it gives the room back, and the navigation bar its padding. */
    @Test
    public void hclosingTheKeyboardGivesTheRoomBack() {
        final View screen = new FrameLayout(this.context);
        this.windowAround(screen);

        WindowPaddingUtil.insetForSystemBarsAndKeyboard(screen);
        ViewCompat.dispatchApplyWindowInsets(screen, withTheKeyboard(KEYBOARD));
        ViewCompat.dispatchApplyWindowInsets(screen, withTheKeyboard(0));

        assertEquals(0, ((ViewGroup.MarginLayoutParams) screen.getLayoutParams()).bottomMargin);
        assertEquals(NAV_BAR, screen.getPaddingBottom());
    }

    /**
     * <b>What the user actually sees: the field they are typing into ends up above the keyboard.</b>
     *
     * <p>The two tests above pin the numbers; this one pins that the numbers do the job, because
     * padding the same amount would pass an arithmetic test and leave the field covered.
     */
    @Test
    public void itheFocusedFieldIsScrolledAboveTheKeyboard() {
        getInstrumentation().runOnMainSync(() -> {
            final ScrollView screen = new ScrollView(this.context);
            // Smooth scrolling animates, and this asserts where it lands.
            screen.setSmoothScrollingEnabled(false);

            final LinearLayout content = new LinearLayout(this.context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.addView(new View(this.context), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 900));
            final EditText field = new EditText(this.context);
            content.addView(field, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 100));
            content.addView(new View(this.context), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1000));
            screen.addView(content);

            final FrameLayout window = this.windowAround(screen);
            WindowPaddingUtil.insetForSystemBarsAndKeyboard(screen);
            ViewCompat.dispatchApplyWindowInsets(screen, withTheKeyboard(0));
            layOut(window);

            assertTrue("the test needs a focused field", field.requestFocus());
            assertTrue("the field starts on screen, as one somebody just tapped is",
                    content.getTop() + field.getBottom() - screen.getScrollY()
                            <= screen.getHeight());

            ViewCompat.dispatchApplyWindowInsets(screen, withTheKeyboard(KEYBOARD));
            layOut(window);

            final int fieldBottomOnScreen =
                    content.getTop() + field.getBottom() - screen.getScrollY();
            assertTrue("the field is " + fieldBottomOnScreen + "px down a screen the keyboard"
                            + " leaves " + screen.getHeight() + "px of",
                    fieldBottomOnScreen <= screen.getHeight());
        });
    }

    private static void layOut(final View window) {
        window.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
        window.layout(0, 0, 1080, 1200);
    }
}
