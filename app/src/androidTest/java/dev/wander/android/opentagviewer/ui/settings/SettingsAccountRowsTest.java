package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
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

    private void shot(final String name) {
        final Activity[] activity = {null};
        this.scenario.onActivity(a -> activity[0] = a);
        Shot.of(activity[0], name);
    }
}
