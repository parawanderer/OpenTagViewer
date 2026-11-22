package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.ExtraSourcesSettingsActivity;
import dev.wander.android.opentagviewer.ICloudLinkActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.SettingsActivity;
import dev.wander.android.opentagviewer.Shot;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.python.icloud.KeychainMembership;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.python.AppDependencies;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.color.MaterialColors;

/**
 * The account rows under the card in Settings: one per account source, lined up with the rows
 * below the divider, and each going to its own page.
 *
 * <p>The rows used to sit 8dp to the right of Theme and the rest, because the card's padding was
 * on their whole section. {@link #theAccountRowsLineUpWithTheRowsBelow} fails against that.
 */
@RunWith(AndroidJUnit4.class)
public class SettingsAccountRowsTest {

    private ActivityScenario<?> scenario;
    private int nightModeBefore;

    @Before
    public void setUp() {
        // Or Settings loads Apple's real ADI library for its Anisette row. See issue #135.
        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());
        this.nightModeBefore = AppCompatDelegate.getDefaultNightMode();
        Intents.init();
    }

    @After
    public void tearDown() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();
        this.memberships().forget().blockingAwait();
        getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setDefaultNightMode(this.nightModeBefore));
    }

    /** The title's left edge on screen, inside one settings_item_clickable row. */
    private int titleXOf(final int rowId) {
        final int[] x = {0};
        this.scenario.onActivity(a -> {
            final ViewGroup row = (ViewGroup) a.findViewById(rowId);
            final TextView title = row.findViewById(R.id.textView4);
            final int[] at = new int[2];
            title.getLocationOnScreen(at);
            x[0] = at[0];
        });
        return x[0];
    }

    private void openSettings() {
        this.scenario = ActivityScenario.launch(SettingsActivity.class);
        Eventually.check(() -> onView(withId(R.id.settings_icloud_link))
                .check(matches(isDisplayed())));
    }

    @Test
    public void theAccountRowsLineUpWithTheRowsBelow() {
        this.openSettings();

        final int theme = this.titleXOf(R.id.setting_app_theme);
        assertEquals("Apple iCloud link", theme, this.titleXOf(R.id.settings_icloud_link));
        assertEquals("Google Find Hub", theme, this.titleXOf(R.id.settings_extra_sources));
    }

    /** A view's top or left edge on screen. */
    private int[] at(final int viewId) {
        final int[] xy = new int[2];
        this.scenario.onActivity(a -> a.findViewById(viewId).getLocationOnScreen(xy));
        return xy;
    }

    /**
     * Each heading starts where the rows' titles start, and sits above the first row it names.
     * Fails if a heading is indented to the icon, or if a row is moved out from under it.
     */
    @Test
    public void theSectionHeadingsLineUpAndNameTheRowsBelowThem() {
        this.openSettings();
        final int titles = this.titleXOf(R.id.setting_app_theme);

        final int[][] headingAndFirstRow = {
                {R.id.settings_section_appearance, R.id.setting_app_theme},
                {R.id.settings_section_tags, R.id.settings_show_apple_devices},
                {R.id.settings_section_app, R.id.settings_app_updates},
        };
        int previousY = Integer.MIN_VALUE;
        for (final int[] pair : headingAndFirstRow) {
            final int[] heading = this.at(pair[0]);
            // Within a pixel: the rows reach their titles through 22dp + 28dp + 22dp, each
            // rounded to pixels on its own, and the heading through one 72dp margin.
            assertTrue("heading lines up with the row titles: " + heading[0] + " vs " + titles,
                    Math.abs(titles - heading[0]) <= 1);
            assertTrue("heading above its first row", heading[1] < this.at(pair[1])[1]);
            assertTrue("headings in order", heading[1] > previousY);
            previousY = heading[1];
        }
        assertTrue("the Anisette row moved to App",
                this.at(R.id.setting_app_anisette_server)[1] > previousY);
    }

    @Test
    public void theBottomOfSettingsInLightMode() {
        this.openSettings();
        onView(withId(R.id.settings_app_debug_data_enabled)).perform(scrollTo());
        this.shot("settings_sections_bottom_light");
    }

    @Test
    public void aDividerSeparatesTheAccountsFromTheRest() {
        this.openSettings();

        onView(withId(R.id.settings_accounts_divider)).check(matches(isDisplayed()));
        final int[] dividerY = {0};
        final int[] themeY = {0};
        this.scenario.onActivity(a -> {
            final int[] at = new int[2];
            a.findViewById(R.id.settings_accounts_divider).getLocationOnScreen(at);
            dividerY[0] = at[1];
            a.findViewById(R.id.setting_app_theme).getLocationOnScreen(at);
            themeY[0] = at[1];
        });
        assertTrue("the divider sits above Theme", dividerY[0] < themeY[0]);
    }

    @Test
    public void googleFindHubOpensItsPage() {
        intending(hasComponent(ExtraSourcesSettingsActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_CANCELED, null));
        this.openSettings();

        onView(withId(R.id.settings_extra_sources)).perform(click());

        Eventually.check(() -> intended(
                hasComponent(ExtraSourcesSettingsActivity.class.getName())));
    }

    @Test
    public void settingsInLightMode() {
        this.openSettings();
        this.shot("settings_accounts_light");
    }

    @Test
    public void settingsInDarkMode() {
        getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
        this.openSettings();
        this.shot("settings_accounts_dark");
    }

    @Test
    public void theLinkPageInLightMode() {
        this.scenario = ActivityScenario.launch(ICloudLinkActivity.class);
        Eventually.check(() -> onView(withId(R.id.icloud_link_primary_button))
                .check(matches(isDisplayed())));
        this.shot("icloud_link_light");
    }

    @Test
    public void theLinkPageInDarkMode() {
        getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
        this.scenario = ActivityScenario.launch(ICloudLinkActivity.class);
        Eventually.check(() -> onView(withId(R.id.icloud_link_primary_button))
                .check(matches(isDisplayed())));
        this.shot("icloud_link_dark");
    }

    private KeychainMembershipRepository memberships() {
        return new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil());
    }

    private void openTheLinkPageLinked(final boolean night) {
        this.memberships().store(new KeychainMembership(
                "{\"peer_id\":\"peer-ours\"}", "ZW50cm9weQ==", "a-generated-passcode",
                "a-label", 2)).blockingAwait();
        if (night) {
            getInstrumentation().runOnMainSync(
                    () -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
        }
        this.scenario = ActivityScenario.launch(ICloudLinkActivity.class);
        Eventually.check(() -> onView(withId(R.id.icloud_link_unlink_button))
                .check(matches(isDisplayed())));
    }

    @Test
    public void theLinkedPageInLightMode() {
        this.openTheLinkPageLinked(false);
        this.shot("icloud_link_linked_light");
    }

    @Test
    public void theLinkedPageInDarkMode() {
        this.openTheLinkPageLinked(true);
        this.shot("icloud_link_linked_dark");
    }

    /**
     * The card's title is in the account name's colour (colorOnPrimaryContainer), on the card's
     * own background. Both resolved from the theme, so checked as a number, in both modes.
     */
    private void titleIsReadableOnTheCard() {
        this.scenario.onActivity(a -> {
            final TextView title = a.findViewById(R.id.icloud_link_status);
            final int background = MaterialColors.getColor(
                    title, com.google.android.material.R.attr.colorSurfaceContainerLow);
            final double ratio = ColorUtils.calculateContrast(
                    title.getCurrentTextColor(), background);
            assertTrue("title contrast on the card is " + ratio, ratio >= 4.5);
            assertEquals("the same blue as the account name",
                    MaterialColors.getColor(title,
                            com.google.android.material.R.attr.colorOnPrimaryContainer),
                    title.getCurrentTextColor());
        });
    }

    @Test
    public void theLinkCardTitleIsReadableInLightMode() {
        this.openTheLinkPageLinked(false);
        this.titleIsReadableOnTheCard();
    }

    @Test
    public void theLinkCardTitleIsReadableInDarkMode() {
        this.openTheLinkPageLinked(true);
        this.titleIsReadableOnTheCard();
    }

    private void shot(final String name) {
        final Activity[] activity = {null};
        this.scenario.onActivity(a -> activity[0] = a);
        Shot.of(activity[0], name);
    }
}
