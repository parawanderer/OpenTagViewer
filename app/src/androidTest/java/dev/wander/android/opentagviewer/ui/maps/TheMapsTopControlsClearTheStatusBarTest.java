package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.MapsActivity;
import dev.wander.android.opentagviewer.R;

/**
 * The map's menu button and long-fetch banner stay below the status bar, whatever its height.
 *
 * <p><b>Reported on a Pixel 10 Fold (#286):</b> the menu button sat under the status bar, and a
 * tap on it opened the notification shade. The map draws under a transparent status bar, and
 * both controls were placed a fixed distance from the top of the screen - a guess at the bar's
 * height that a foldable's inner screen, or a large camera cutout, exceeds.
 *
 * <p>The bar is invented, as in {@code TheNavigationBar}: the managed device's own status bar is
 * short enough that the old fixed margins cleared it, so measuring it would pass the bug.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class TheMapsTopControlsClearTheStatusBarTest {

    /** Taller than any real status bar, so a fixed margin cannot clear it by luck. */
    private static final int A_TALL_STATUS_BAR = 240;

    private final AMapWithTagsOnIt theMap = new AMapWithTagsOnIt();

    @After
    public void putItBack() {
        this.theMap.putItBack();
    }

    @Test
    public void theMenuButtonAndBannerGoBelowATallStatusBar() {
        final ActivityScenario<MapsActivity> scenario = this.theMap.seed("A tag").open();
        giveItAStatusBar(scenario, A_TALL_STATUS_BAR);

        scenario.onActivity(activity -> {
            final View content = activity.findViewById(android.R.id.content);
            final View button = activity.findViewById(R.id.button_more_settings);

            final Rect bounds = new Rect(0, 0, button.getWidth(), button.getHeight());
            ((ViewGroup) content).offsetDescendantRectToMyCoords(button, bounds);

            assertTrue("the menu button's top is at " + bounds.top + "px, inside a "
                            + A_TALL_STATUS_BAR + "px status bar, where a tap opens the"
                            + " notification shade",
                    bounds.top >= A_TALL_STATUS_BAR);

            // Hidden until a fetch runs long, so its margin is what places it.
            assertTrue("the long-fetch banner would be drawn under the status bar",
                    topMarginOf(activity.findViewById(R.id.long_fetch_banner))
                            >= A_TALL_STATUS_BAR);
        });
    }

    /**
     * <b>On an ordinary 24dp status bar, nothing moves.</b> The layout's margins were turned into
     * gaps below the bar, chosen so the old position comes out the same there.
     */
    @Test
    public void onAnOrdinaryStatusBarTheyStayWhereTheyWere() {
        final ActivityScenario<MapsActivity> scenario = this.theMap.seed("A tag").open();
        final int[] bar = new int[1];
        scenario.onActivity(activity -> bar[0] = dp(activity, 24));
        giveItAStatusBar(scenario, bar[0]);

        // Within a pixel: a layout margin is truncated to whole pixels and the bar is rounded,
        // so 11dp + 24dp need not come to exactly the pixels of 35dp.
        scenario.onActivity(activity -> {
            assertEquals("the menu button's container used to sit 35dp from the top",
                    dp(activity, 35),
                    topMarginOf(activity.findViewById(R.id.more_dropdown_top_right)), 1);
            assertEquals("the banner used to sit 45dp from the top",
                    dp(activity, 45),
                    topMarginOf(activity.findViewById(R.id.long_fetch_banner)), 1);
        });
    }

    private static void giveItAStatusBar(final ActivityScenario<MapsActivity> scenario,
                                         final int height) {
        scenario.onActivity(activity -> ViewCompat.dispatchApplyWindowInsets(
                activity.findViewById(android.R.id.content),
                new WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.systemBars(),
                                Insets.of(0, height, 0, 0))
                        .build()));
        getInstrumentation().waitForIdleSync();
    }

    private static int topMarginOf(final View view) {
        return ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).topMargin;
    }

    private static int dp(final MapsActivity activity, final int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                activity.getResources().getDisplayMetrics()));
    }
}
