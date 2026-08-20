package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.FetchFromICloudActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.SettingsActivity;

/**
 * Reading the Apple account from Settings.
 *
 * <p><b>The third door to one screen, and the one people go looking for.</b> The empty state's
 * button disappears the moment anything is imported, and the device list's overflow menu is only
 * obvious to somebody who already knows it is there - so "how do I get my tags again" ends up
 * being asked of Settings, which is where somebody looks when a thing is not where they expected.
 *
 * <p>What is checked is that the row exists, says what it does, and reaches the screen. Where it
 * goes next is {@code FetchFromICloudFlowTest}'s business; the fetch screen itself is answered at
 * the door here, because it wants an Apple session and a Python interpreter and none of that is
 * what this is about.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class FetchFromAccountSettingTest {

    private ActivityScenario<SettingsActivity> scenario;

    @Before
    public void answerTheFetchScreenAtTheDoor() {
        Intents.init();
        intending(hasComponent(FetchFromICloudActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_CANCELED, null));

        this.scenario = ActivityScenario.launch(SettingsActivity.class);
    }

    @After
    public void closeIt() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
    }

    @Test
    public void settingsOffersToReadTheAppleAccount() {
        Eventually.check(() -> onView(withId(R.id.settings_fetch_from_account))
                .check(matches(isDisplayed())));
    }

    /**
     * And it says what it does, not just what it is called.
     *
     * <p>"Fetch my tags from my Apple account" alone leaves somebody who already has tags
     * wondering whether this would duplicate them. The subtitle is the answer.
     */
    @Test
    public void therowSaysWhatItWillDoToTagsAlreadyHere() {
        Eventually.check(() -> onView(allOf(
                withText(R.string.icloud_fetch_from_settings_subtitle),
                isDescendantOfA(withId(R.id.settings_fetch_from_account))))
                .check(matches(isDisplayed())));
    }

    /** <b>And tapping it gets there.</b> */
    @Test
    public void tappingItReachesTheAccountScreen() {
        Eventually.check(() -> onView(withId(R.id.settings_fetch_from_account))
                .check(matches(isDisplayed())));

        onView(withId(R.id.settings_fetch_from_account)).perform(click());

        Eventually.check(() -> intended(hasComponent(FetchFromICloudActivity.class.getName())));
    }
}
