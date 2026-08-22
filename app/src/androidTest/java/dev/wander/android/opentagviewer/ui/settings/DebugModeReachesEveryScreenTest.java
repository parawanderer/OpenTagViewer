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
