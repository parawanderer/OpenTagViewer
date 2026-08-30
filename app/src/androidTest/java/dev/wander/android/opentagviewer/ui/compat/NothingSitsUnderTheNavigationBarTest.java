package dev.wander.android.opentagviewer.ui.compat;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.Intent;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import dev.wander.android.opentagviewer.AppleLoginActivity;
import dev.wander.android.opentagviewer.DeviceInfoActivity;
import dev.wander.android.opentagviewer.HistoryViewActivity;
import dev.wander.android.opentagviewer.InformationActivity;
import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.SettingsActivity;
import dev.wander.android.opentagviewer.ui.error.ErrorReportActivity;
import dev.wander.android.opentagviewer.ui.maps.AMapWithTagsOnIt;

/**
 * Nothing a person has to press ends up underneath the navigation bar.
 *
 * <p><b>The bug, in @parawanderer's words:</b> "any button we put at the bottom of the page (or
 * random text) is barely to not clickable". The screenshot was the keychain unlock screen, its
 * Unlock button behind the gesture pill. The theme draws under a transparent navigation bar, so
 * a screen that does not pad for it puts its last control where the system takes the touches.
 *
 * <p>{@link TheNavigationBar} does the asking, and explains why the bar is invented rather than
 * measured. This class brings the screens.
 *
 * <p><b>Every activity in the manifest except two.</b> The map is out deliberately - it draws
 * tiles edge to edge under the bar and pads only the card row above it, which
 * {@code TagCardLayoutTest} covers. The iCloud flow is checked in
 * {@code FetchFromICloudFlowTest} instead, because it closes itself without a usable session and
 * that test already knows how to give it one; rebuilding that here would be the copy that drifts.
 *
 * <p><b>The list is still the weak point.</b> A screen added later is not covered until somebody
 * adds it - the same rot that let this happen. What protects the common case is that
 * {@link WindowPaddingUtil#insetForSystemBars} does both bars at once, so a screen cannot handle
 * the status bar and silently miss the navigation bar; the top-only helper it replaced is gone
 * rather than left there to be called.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class NothingSitsUnderTheNavigationBarTest {

    /** Screens that open with nothing arranged. */
    private static final Class<?>[] SCREENS_THAT_NEED_NOTHING = {
            AppleLoginActivity.class,
            MyDevicesListActivity.class,
            InformationActivity.class,
            SettingsActivity.class,
    };

    private final AMapWithTagsOnIt theMap = new AMapWithTagsOnIt();

    @After
    public void putItBack() {
        this.theMap.putItBack();
    }

    /**
     * <b>The signed-out screens first, before anything stores a session.</b>
     *
     * <p>{@code AMapWithTagsOnIt.seed} writes one, and {@code AppleLoginActivity} finishes itself
     * and leaves for the map the moment one exists - so checking it after seeding gave "Activity
     * has been destroyed already" rather than an answer. Two phases, in this order, for that
     * reason alone.
     */
    @Test
    public void everyScreenKeepsItsControlsAboveTheNavigationBar() {
        final Context context = getInstrumentation().getTargetContext();

        final List<Intent> beforeAnybodySignsIn = new ArrayList<>();
        for (final Class<?> screen : SCREENS_THAT_NEED_NOTHING) {
            beforeAnybodySignsIn.add(new Intent(context, screen));
        }

        // **The report page, which is what asking this question properly turned up.** It handled
        // neither bar, and a search-and-replace over the screens that padded for the status bar
        // could not find it precisely because it did none of it. Its Close and Share buttons are
        // the last things on a scrolling page.
        beforeAnybodySignsIn.add(
                ErrorReportActivity.intentFor(context, "a made-up failure, for the test"));

        this.check(beforeAnybodySignsIn);

        // And the two that need a tag to look at, which comes with a session.
        this.theMap.seed("A tag");
        final String beaconId = this.theMap.tagIds().get(0);

        this.check(List.of(
                new Intent(context, DeviceInfoActivity.class).putExtra("beaconId", beaconId),
                new Intent(context, HistoryViewActivity.class).putExtra("beaconId", beaconId)));
    }

    private void check(final List<Intent> screens) {
        for (final Intent screen : screens) {
            final String name = screen.getComponent().getShortClassName();

            try (ActivityScenario<?> scenario = ActivityScenario.launch(screen)) {
                TheNavigationBar.doesNotCover(scenario, name);
            }
        }
    }

    /**
     * <b>And applying them twice does not double the gap.</b>
     *
     * <p>Insets are delivered more than once - a rotation, the keyboard opening, somebody
     * switching to three-button navigation - so a helper that added the inset to whatever padding
     * it found would grow the gap on every delivery. It reads its own starting padding once, and
     * this is what says so.
     */
    @Test
    public void repeatedInsetsDoNotAccumulate() {
        final int[] afterOne = new int[1];
        final int[] afterThree = new int[1];

        getInstrumentation().runOnMainSync(() -> {
            final FrameLayout view = new FrameLayout(getInstrumentation().getTargetContext());
            view.setPadding(0, 0, 0, 17);
            WindowPaddingUtil.insetForSystemBars(view);

            TheNavigationBar.putABarUnder(view);
            afterOne[0] = view.getPaddingBottom();

            TheNavigationBar.putABarUnder(view);
            TheNavigationBar.putABarUnder(view);
            afterThree[0] = view.getPaddingBottom();
        });

        assertEquals("the view's own 17px should be kept, with the bar's height added to it",
                17 + TheNavigationBar.A_TALL_ONE, afterOne[0]);
        assertEquals("three deliveries of the same insets must leave the same padding as one",
                afterOne[0], afterThree[0]);
    }
}
