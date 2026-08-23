package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.ScrollView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.DeviceInfoActivity;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.HistoryViewActivity;
import dev.wander.android.opentagviewer.MapsActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.SettingsActivity;
import dev.wander.android.opentagviewer.python.PythonAppleService;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.ui.maps.AMapWithTagsOnIt;

/**
 * One switch in Settings, and the three unrelated screens that have to obey it.
 *
 * <p><b>A setting is only as good as the screens that read it, and each reads it separately.</b>
 * {@code DeviceInfoActivity} toggles a block's visibility, {@code MapsActivity} decides whether
 * to put an item in a menu, and {@code HistoryItemsAdapter} decides whether each row carries a
 * second line. Three independent {@code getEnableDebugData() == Boolean.TRUE} checks, none of
 * which knows about the others - so a change to how the setting is stored or read can leave one
 * of them behind, and nothing complains.
 *
 * <p><b>Turning it off again is half the test, and the half that rots.</b> Showing something new
 * gets noticed the moment somebody enables the setting; the hiding path only runs for people who
 * change their mind, so a screen that shows debug output permanently once enabled can survive a
 * long time. Every assertion here has its mirror.
 *
 * <p>The switch is driven rather than the value written, because the wire from the control to
 * the stored setting is part of what is under test - {@code SettingsActivity} saves on change
 * rather than on exit, and that is easy to break while tidying.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class DebugModeReachesEveryScreenTest {

    private final AMapWithTagsOnIt theMap = new AMapWithTagsOnIt();

    private Context context;
    private ActivityScenario<?> screen;

    @Before
    public void seedATagAndStartFromDebugOff() {
        this.context = getInstrumentation().getTargetContext();

        this.theMap.seed("Bike");
        this.setDebugTo(false);
    }

    @After
    public void putEverythingBack() {
        this.closeTheScreen();
        // Restores the settings this overwrote, including the debug flag.
        this.theMap.putItBack();
    }

    // ------------------------------------------------------------------ the journey

    /**
     * <b>On: the switch sticks, and all three screens start showing more.</b>
     */
    @Test
    public void turningItOnReachesTheTagPageTheMapMenuAndTheHistoryRows() {
        this.flipTheSwitchInSettings();

        assertTrue("the switch did not reach the stored settings",
                this.storedDebugSetting());

        assertTrue("the tag page is not showing its debug block",
                this.tagPageShowsItsDebugBlock());
        assertTrue("Export Logs is missing from the map's menu",
                this.mapMenuOffersExportLogs());
        assertTrue("the history rows carry no extra detail",
                this.historyRowsCarryExtraDetail());
    }

    /**
     * <b>Off again: all three go back, which is the part nobody exercises by hand.</b>
     */
    @Test
    public void turningItOffAgainTakesAllOfItAway() {
        this.setDebugTo(true);

        this.flipTheSwitchInSettings();

        assertEquals("the switch did not turn the setting back off",
                Boolean.FALSE, this.storedDebugSetting());

        assertTrue("the tag page kept its debug block after debug mode was turned off",
                !this.tagPageShowsItsDebugBlock());
        assertTrue("Export Logs stayed in the map's menu after debug mode was turned off",
                !this.mapMenuOffersExportLogs());
        assertTrue("the history rows kept their extra detail after debug mode was turned off",
                !this.historyRowsCarryExtraDetail());
    }

    // ------------------------------------------------------------------ each screen, asked

    /** Open Settings, tap the debug switch, and let it save. */
    /**
     * <b>Scrolled to the bottom, the switch is above the navigation bar rather than under it.</b>
     *
     * <p>The theme makes the navigation bar transparent, so the scrolling area runs underneath
     * it. The debug switch is the last row and had nothing below it, so it came to rest behind
     * the gesture pill: drawn, reachable by Espresso, and awkward or impossible to tap by hand -
     * the failure this repo keeps meeting, where the build is green and the screen is wrong.
     *
     * <p><b>Asserted in pixels against the measured inset, not by eye and not by isDisplayed().</b>
     * A view under a translucent navigation bar is still on screen as far as Espresso is
     * concerned, so {@code isCompletelyDisplayed} passes for exactly the case this is about. The
     * question is whether the switch ends above where the navigation bar begins, which is a
     * number the device can be asked for.
     *
     * <p><b>Scrolled to the end, not merely scrolled to.</b> Espresso's {@code scrollTo()} moves
     * the minimum needed to make a view visible, and
     * {@code ScrollView.computeScrollDeltaToGetChildRectOnScreen} does that arithmetic against
     * the full height with no regard for bottom padding - so with {@code clipToPadding="false"}
     * it happily parks the row inside the padded strip and calls it visible. Written that way
     * this failed against a correct fix, reporting the switch ending at exactly the screen
     * height. What a person can do is keep scrolling, so the test does too.
     *
     * <p><b>And the geometry alone is not enough, which took measuring to find out.</b> On the
     * Pixel 9 emulator with gesture navigation the row clears the bar by eleven pixels even with
     * the fix removed, so a purely geometric assertion is green either way and says nothing. The
     * assertion that actually holds the fix in place is the one about reserved space - see the
     * comment on it. Both are here: the geometry is what a user experiences, the reserved space
     * is what makes it true on a device this suite never runs on.
     */
    @Test
    public void theLastRowEndsAboveTheNavigationBar() {
        this.openTheScreen(SettingsActivity.class);

        Eventually.check(() -> onView(withId(R.id.settings_app_debug_data_enabled))
                .perform(scrollTo())
                .check(matches(isDisplayed())));

        // **The content's own height, not Integer.MAX_VALUE.** ScrollView.scrollTo clamps to the
        // scroll range, which sounds like it makes the argument's size irrelevant - but the clamp
        // adds the viewport height to it first, so MAX_VALUE overflows and the view lands at a
        // huge negative offset. That reported the switch ending at -2147480706, which is below
        // every threshold, so the assertion passed no matter what the layout did. A test that
        // cannot fail is worse than no test; this is the shape it takes.
        //
        // Not fullScroll() either, which smooth-scrolls by default and would need waiting out.
        this.screen.onActivity(activity -> {
            final ScrollView scrollArea = activity.findViewById(R.id.settings_scroll_area);
            scrollArea.scrollTo(0, scrollArea.getChildAt(0).getBottom());
        });
        getInstrumentation().waitForIdleSync();

        final int[] switchEndsAt = new int[1];
        final int[] navigationBarStartsAt = new int[1];
        final int[] navigationBarNeeds = new int[1];
        final int[] scrollAreaReserves = new int[1];
        this.screen.onActivity(activity -> {
            final View switcher = activity.findViewById(R.id.settings_app_debug_data_enabled);
            final int[] onScreen = new int[2];
            switcher.getLocationOnScreen(onScreen);
            switchEndsAt[0] = onScreen[1] + switcher.getHeight();

            // **In screen coordinates, because that is what the switch was measured in.**
            // decor.getHeight() is a height, not a screen position, and the two coincide only
            // when the decor view starts at y=0. On the managed aosp-atd device it does not, and
            // this reported a 54px overlap that was purely the difference between two origins -
            // on a device whose inset is zero and which therefore has nothing to overlap with.
            final View decor = activity.getWindow().getDecorView();
            final int[] decorOnScreen = new int[2];
            decor.getLocationOnScreen(decorOnScreen);
            final Insets bars = ViewCompat.getRootWindowInsets(decor)
                    .getInsets(WindowInsetsCompat.Type.systemBars());
            navigationBarStartsAt[0] = decorOnScreen[1] + decor.getHeight() - bars.bottom;

            navigationBarNeeds[0] = bars.bottom;
            scrollAreaReserves[0] =
                    activity.findViewById(R.id.settings_scroll_area).getPaddingBottom();
        });

        // **The reserved space, as well as the geometry, and this is the assertion that bites.**
        // Measured on the Pixel 9 emulator, gesture navigation: without the inset the switch ends
        // at 2350 with the bar starting at 2361 - eleven pixels clear, so the geometric check
        // below passes and proves nothing. Three-button navigation asks for around 126px instead
        // of 63, and those eleven pixels become a switch underneath the bar.
        //
        // So this asks the question that has the same answer on every device: is the scrolling
        // area reserving at least what the navigation bar takes? Confirmed to fail without the
        // fix - "reserves 0px, needs 63px". Zero rather than the child's 20dp because that
        // padding is inside the scrolling content, which is why it gives the eleven pixels above
        // and no protection at all from a bar that wants more.
        //
        // **On the managed device this proves nothing, and that is worth saying out loud.**
        // aosp-atd reports a systemBars bottom inset of 0 - no navigation bar - so both
        // assertions here are vacuously true on the emulator CI actually runs. It earns its keep
        // on a device that has one, which is every real phone and the windowed emulator the wiki
        // captures use. Do not read a green CI run as evidence this screen is fine.
        assertTrue("the scroll area reserves " + scrollAreaReserves[0] + "px at the bottom, but "
                        + "the navigation bar takes " + navigationBarNeeds[0] + "px - the last "
                        + "row is one taller navigation bar away from being unreachable",
                scrollAreaReserves[0] >= navigationBarNeeds[0]);

        assertTrue("the debug switch ends at " + switchEndsAt[0] + ", but the navigation bar "
                        + "starts at " + navigationBarStartsAt[0] + " - it is underneath it",
                switchEndsAt[0] <= navigationBarStartsAt[0]);
    }

    private void flipTheSwitchInSettings() {
        this.openTheScreen(SettingsActivity.class);

        // **scrollTo first, and this test is the reason the screen scrolls at all.** It used to
        // say the opposite - that activity_settings had no ScrollView, so the switch was simply
        // on screen once the page laid out. That was true only while the page happened to fit,
        // and it stopped being true the moment a setting was added: the switch went below the
        // fold, this went red, and nothing could reach it - not Espresso and not a user either.
        Eventually.check(() -> onView(withId(R.id.settings_app_debug_data_enabled))
                .perform(scrollTo())
                .check(matches(isDisplayed())));
        onView(withId(R.id.settings_app_debug_data_enabled)).perform(scrollTo(), click());

        // Saved on change rather than on exit, and on a background scheduler - so the write is
        // in flight when the click returns.
        Eventually.check(() -> assertTrue("the setting never reached storage",
                this.storedDebugSetting() != null));

        this.closeTheScreen();
    }

    private boolean tagPageShowsItsDebugBlock() {
        final Intent intent = new Intent(this.context, DeviceInfoActivity.class);
        intent.putExtra("beaconId", this.theMap.tagIds().get(0));

        this.screen = ActivityScenario.launch(intent);

        final boolean[] shown = {false};
        Eventually.check(() -> {
            this.screen.onActivity(activity -> {
                final View block = activity.findViewById(R.id.device_debug_info);
                shown[0] = block != null && block.getVisibility() == View.VISIBLE;
            });
            // Asked once the screen has drawn something, so "not yet built" is not read as
            // "deliberately hidden".
            onView(withId(R.id.device_debug_info)).check(matches(
                    shown[0] ? isDisplayed() : not(isDisplayed())));
        });

        this.closeTheScreen();
        return shown[0];
    }

    /**
     * Whether the map's overflow offers Export Logs.
     *
     * <p>Answered by looking for the item and catching its absence, because "not in the menu"
     * is a missing view rather than a hidden one - {@code MapsActivity} calls
     * {@code setVisible(false)} on the {@code MenuItem}, and a {@code PopupMenu} does not
     * inflate a view for an invisible item at all.
     */
    private boolean mapMenuOffersExportLogs() {
        this.screen = ActivityScenario.launch(new Intent(this.context, MapsActivity.class));

        Eventually.check(() -> onView(withId(R.id.button_more_settings))
                .check(matches(isDisplayed())));
        onView(withId(R.id.button_more_settings)).perform(click());

        boolean offered;
        try {
            onView(withText(R.string.export_logs)).inRoot(isPlatformPopup())
                    .check(matches(isDisplayed()));
            offered = true;
        } catch (final RuntimeException | AssertionError absent) {
            offered = false;
        }

        this.closeTheScreen();
        return offered;
    }

    /**
     * Whether a history row carries its second line.
     *
     * <p>Opened after the map, deliberately: {@code HistoryViewActivity} takes its Apple session
     * from {@code PythonAppleService.getInstance()}, which only the map sets up, so launching it
     * cold gives a screen that cannot fetch anything.
     */
    private boolean historyRowsCarryExtraDetail() {
        // **Waited on the singleton, not on the map object.** The map is opened only to set up
        // PythonAppleService, which HistoryViewActivity reads and cannot work without - and
        // `theMap.map()` is non-null from the moment the fixture is built, so checking that
        // let this close the map before it had set anything up. The history screen then had no
        // service, its fetch failed, and the row count was zero for a reason nowhere near the
        // setting under test.
        this.screen = ActivityScenario.launch(new Intent(this.context, MapsActivity.class));
        Eventually.check(() -> assertTrue("the map never set up the Python service",
                PythonAppleService.getInstance() != null));
        this.closeTheScreen();

        final Intent intent = new Intent(this.context, HistoryViewActivity.class);
        intent.putExtra("beaconId", this.theMap.tagIds().get(0));
        this.screen = ActivityScenario.launch(intent);

        final boolean[] shown = {false};
        Eventually.check(() -> {
            this.screen.onActivity(activity -> {
                final View detail = activity.findViewById(R.id.history_item_location_detail);
                shown[0] = detail != null && detail.getVisibility() == View.VISIBLE;
            });
            assertTrue("no history row was built at all, so there is nothing to look at",
                    this.aHistoryRowExists());
        });

        this.closeTheScreen();
        return shown[0];
    }

    private boolean aHistoryRowExists() {
        final boolean[] any = {false};
        this.screen.onActivity(activity -> {
            final androidx.recyclerview.widget.RecyclerView list =
                    activity.findViewById(R.id.recycler_view_history_items);
            any[0] = list != null && list.getAdapter() != null
                    && list.getAdapter().getItemCount() > 0;
        });
        return any[0];
    }

    // ------------------------------------------------------------------ plumbing

    private void openTheScreen(final Class<?> activity) {
        this.closeTheScreen();
        this.screen = ActivityScenario.launch(new Intent(this.context, activity));
    }

    private void closeTheScreen() {
        if (this.screen != null) {
            this.screen.close();
            this.screen = null;
        }
    }

    private Boolean storedDebugSetting() {
        return new UserSettingsRepository(UserSettingsDataStore.getInstance(this.context))
                .getUserSettings()
                .getEnableDebugData();
    }

    private void setDebugTo(final boolean enabled) {
        final UserSettingsRepository settings = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.context));

        final var current = settings.getUserSettings();
        current.setEnableDebugData(enabled);
        settings.storeUserSettings(current).blockingAwait();
    }
}
