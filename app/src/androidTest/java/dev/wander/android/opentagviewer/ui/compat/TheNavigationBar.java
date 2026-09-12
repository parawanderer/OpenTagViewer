package dev.wander.android.opentagviewer.ui.compat;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertNull;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks a screen whether it would keep its controls out of a navigation bar.
 *
 * <p><b>The bar is invented, and that is the point.</b> The managed device this suite runs on
 * reports a {@code systemBars} bottom inset of <b>zero</b> - measured, while chasing a different
 * bug - so asking the real device where its navigation bar is proves nothing on CI, and a
 * geometric assertion would pass on any layout at all. Dispatching a synthetic inset asks the
 * question that actually matters: <i>if</i> there were a bar this tall, would this screen keep
 * its buttons out of it? Same answer on every device.
 *
 * <p><b>Here rather than in one test class because the screens live in different setups.</b> Most
 * open with nothing arranged; the iCloud flow needs a session its own flow test knows how to
 * build. Copying the check into that test would be the version that drifts, so the check is
 * shared and each test brings its own screen.
 */
public final class TheNavigationBar {

    /** Taller than any real navigation bar, so a screen that ignores it cannot pass by luck. */
    public static final int A_TALL_ONE = 240;

    private static final int A_STATUS_BAR = 90;

    private TheNavigationBar() {
    }

    /**
     * Pretend this screen has a tall navigation bar, and fail if anything a person must press
     * would end up underneath it.
     *
     * <p><b>Takes the scenario, not the activity, and that is not a style choice.</b> This has to
     * hop to the main thread to touch views and back off it to wait for a layout pass, and
     * {@code runOnMainSync} throws "This method can not be called from the main application
     * thread" if it is already there. Handing it an {@code Activity} invited exactly that: the
     * obvious way to get one is inside {@code onActivity}, which is on the main thread.
     */
    public static void doesNotCover(final ActivityScenario<?> scenario, final String screen) {
        scenario.onActivity(activity -> putABarUnder(activity.findViewById(android.R.id.content)));
        getInstrumentation().waitForIdleSync();

        final String[] problem = new String[1];
        scenario.onActivity(activity -> {
            problem[0] = whatSitsUnderIt(activity, screen);
            if (problem[0] == null) {
                problem[0] = didNothingReserveIt(activity, screen);
            }
        });

        assertNull(problem[0], problem[0]);
    }

    public static void putABarUnder(final View view) {
        ViewCompat.dispatchApplyWindowInsets(view, new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(),
                        Insets.of(0, A_STATUS_BAR, 0, A_TALL_ONE))
                .build());
    }

    /** @return a description of the first control found inside the bar, or null if all is well. */
    private static String whatSitsUnderIt(final Activity activity, final String screen) {
        final View root = activity.findViewById(android.R.id.content);
        final int barStartsAt = root.getHeight() - A_TALL_ONE;

        for (final View control : clickableThingsIn(root)) {
            if (control == root) {
                continue;
            }

            final Rect bounds = new Rect(0, 0, control.getWidth(), control.getHeight());
            ((ViewGroup) root).offsetDescendantRectToMyCoords(control, bounds);

            // **A full-height container that happens to be clickable is not a control.**
            // InformationActivity's root carries android:clickable and spans the screen, so it
            // "ends below the bar" by definition - and it is the very view whose padding keeps
            // the real controls out of the bar. Judging it as a button failed a correct screen.
            if (bounds.height() > root.getHeight() * 0.7) {
                continue;
            }

            // **Something inside a scrolling list is not stuck there.** Settings' last switches
            // sit under the bar at rest and a flick brings them up, which is ordinary and fine.
            // The complaint is about controls that cannot be moved - a button anchored at the
            // bottom, like the keychain Unlock button in the report.
            if (canBeScrolledClear(control, root)) {
                continue;
            }

            if (bounds.bottom > barStartsAt) {
                return screen + ": a control ends at " + bounds.bottom + " but the navigation bar"
                        + " starts at " + barStartsAt + " (" + describe(activity, control) + ")"
                        + " - it would be under the bar and hard or impossible to press";
            }
        }
        return null;
    }

    /**
     * <b>And the screen has to reserve the bar's height somewhere.</b>
     *
     * <p>The check above only looks at controls that exist and are anchored, so a screen whose
     * bottom happens to be empty passes it while handling no insets at all - and then puts a
     * button under the bar the moment somebody adds one.
     *
     * <p>Loose on purpose: which view holds the padding differs by screen - the root on most, the
     * scroll container on the iCloud flow - and pinning that per screen would be one more
     * per-screen thing to keep in step.
     */
    private static String didNothingReserveIt(final Activity activity, final String screen) {
        for (final View view : everythingIn(activity.findViewById(android.R.id.content))) {
            if (view.getPaddingBottom() >= A_TALL_ONE) {
                return null;
            }
        }
        return screen + ": nothing on this screen reserved the " + A_TALL_ONE + "px navigation"
                + " bar, so it is not handling window insets at all - anything put at the bottom"
                + " of it will end up under the bar";
    }

    private static boolean canBeScrolledClear(final View control, final View root) {
        for (ViewGroup parent = (ViewGroup) control.getParent();
                parent != null && parent != root.getParent();
                parent = parent.getParent() instanceof ViewGroup
                        ? (ViewGroup) parent.getParent() : null) {

            if (parent instanceof android.widget.ScrollView
                    || parent instanceof android.widget.HorizontalScrollView
                    || parent instanceof androidx.core.widget.NestedScrollView
                    || parent instanceof androidx.recyclerview.widget.RecyclerView
                    || parent instanceof android.widget.ListView) {
                return true;
            }

            // **A bottom sheet is dragged, which is the same kind of movable.** The history
            // sheet's retry button measures inside the bar at rest, and a drag upwards brings it
            // out - so it is not the stuck button this is about.
            //
            // Known gap, deliberately: a sheet is positioned by its BottomSheetBehavior rather
            // than by its parent's padding, so the screen-level inset does not reach into it and
            // padding the sheet's own content did not move it either - measured, both ways. Doing
            // it properly means the behaviour's peek height, which is a bigger change than the
            // one this test was written for.
            if (parent.getLayoutParams()
                    instanceof androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
                    && ((androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)
                            parent.getLayoutParams()).getBehavior() != null) {
                return true;
            }
        }
        return false;
    }

    private static List<View> clickableThingsIn(final View view) {
        final List<View> found = new ArrayList<>();
        if (view.getVisibility() == View.VISIBLE && view.isClickable() && view.getWidth() > 0) {
            found.add(view);
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                found.addAll(clickableThingsIn(group.getChildAt(i)));
            }
        }
        return found;
    }

    private static List<View> everythingIn(final View view) {
        final List<View> found = new ArrayList<>();
        found.add(view);
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                found.addAll(everythingIn(group.getChildAt(i)));
            }
        }
        return found;
    }

    private static String describe(final Activity activity, final View view) {
        try {
            return activity.getResources().getResourceEntryName(view.getId());
        } catch (final Exception noName) {
            return view.getClass().getSimpleName();
        }
    }
}
