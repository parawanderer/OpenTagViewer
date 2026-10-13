package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.ExtraSourcesSettingsActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.source.ExtraSourcesSettings;

/**
 * The Google Find Hub settings screen, driven.
 *
 * <p>The warning is the one that matters: the app permits cleartext HTTP, so an http:// address
 * sends the API token, which reads every location the service holds, unencrypted.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ExtraSourcesSettingsScreenTest {

    private Context context;
    private ActivityScenario<ExtraSourcesSettingsActivity> scenario;

    @Before
    public void openItEmpty() {
        this.context = getInstrumentation().getTargetContext();
        this.clearSettings();
        this.scenario = ActivityScenario.launch(ExtraSourcesSettingsActivity.class);
    }

    @After
    public void closeIt() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        this.clearSettings();
    }

    private void clearSettings() {
        this.context.getSharedPreferences("extra_sources", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void anHttpAddressWarnsThatTheTokenTravelsUnencrypted() {
        onView(withId(R.id.extra_sources_google_url)).perform(replaceText("http://192.168.1.2:8090"));

        Eventually.check(() -> onView(withText(R.string.server_url_not_https)).check(matches(isDisplayed())));
    }

    @Test
    public void anHttpsAddressDoesNot() {
        onView(withId(R.id.extra_sources_google_url)).perform(replaceText("http://192.168.1.2:8090"));
        Eventually.check(() -> onView(withText(R.string.server_url_not_https)).check(matches(isDisplayed())));

        onView(withId(R.id.extra_sources_google_url)).perform(replaceText("https://find.example"));

        // Gone or hidden, whichever TextInputLayout does with a null helper text
        Eventually.check(() -> onView(withText(R.string.server_url_not_https)).check((view, noMatch) ->
                assertTrue("the warning is still showing", view == null || !view.isShown())));
    }

    @Test
    public void testingWithNoAddressSaysToEnterOne() {
        onView(withId(R.id.extra_sources_test)).perform(click());

        Eventually.check(() -> onView(withId(R.id.extra_sources_test_result))
                .check(matches(withText(R.string.test_nothing_configured))));
    }

    /** Saved when the screen is left, like the rest of Settings. */
    @Test
    public void whatWasTypedSurvivesLeavingTheScreen() {
        onView(withId(R.id.extra_sources_google_url)).perform(replaceText("https://find.example"));
        onView(withId(R.id.extra_sources_google_token)).perform(replaceText("s3cret-token"), closeSoftKeyboard());

        this.scenario.recreate();

        Eventually.check(() -> onView(withId(R.id.extra_sources_google_url))
                .check(matches(withText("https://find.example"))));
        assertEquals("s3cret-token", new ExtraSourcesSettings(this.context).get(ExtraSourcesSettings.GOOGLE_TOKEN));
    }
}
