package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasData;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Context;
import android.content.Intent;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.BuildConfig;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.SettingsActivity;
import dev.wander.android.opentagviewer.Shot;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.update.UpdateCheck;
import dev.wander.android.opentagviewer.update.UpdateSettings;

/**
 * Settings -> App updates. Nothing here reaches GitHub: the row reads what a check stored, and
 * the tests store that themselves. "Check now" is the one action that would ask, so it is not
 * pressed.
 */
@RunWith(AndroidJUnit4.class)
public class AppUpdatesSettingTest {

    private static final String PAGE =
            "https://github.com/parawanderer/OpenTagViewer/releases/tag/android-app-v99.0.0";

    private final Context context = getInstrumentation().getTargetContext();
    private ActivityScenario<SettingsActivity> scenario;
    private int nightModeBefore;

    @Before
    public void setUp() {
        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());
        this.clear();
        this.nightModeBefore = AppCompatDelegate.getDefaultNightMode();
        Intents.init();
        intending(hasAction(Intent.ACTION_VIEW))
                .respondWith(new ActivityResult(Activity.RESULT_CANCELED, null));
    }

    @After
    public void tearDown() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();
        this.clear();
        getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setDefaultNightMode(this.nightModeBefore));
    }

    private void clear() {
        this.context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private void givenANewerReleaseWasFound() {
        final UpdateSettings updates = new UpdateSettings(this.context);
        updates.setEnabled(true);
        updates.recordCheck(System.currentTimeMillis(), new UpdateCheck.Release("99.0.0", PAGE));
    }

    private void open() {
        this.scenario = ActivityScenario.launch(SettingsActivity.class);
    }

    private void seeSubtitle(final String text) {
        Eventually.check(() -> onView(allOf(withId(R.id.textView5),
                isDescendantOfA(withId(R.id.settings_app_updates))))
                .check(matches(withText(text))));
    }

    private void tapTheRow() {
        onView(withId(R.id.settings_app_updates)).perform(scrollTo(), click());
    }

    @Test
    public void itSaysTheVersionAndThatCheckingIsOff() {
        this.open();
        this.seeSubtitle(this.context.getString(
                R.string.updates_subtitle_off, BuildConfig.VERSION_NAME));
    }

    @Test
    public void aNewerVersionIsNamedAndOpensItsReleasePage() {
        this.givenANewerReleaseWasFound();
        this.open();

        this.seeSubtitle(this.context.getString(
                R.string.updates_subtitle_available, BuildConfig.VERSION_NAME, "99.0.0"));
        this.tapTheRow();
        Eventually.check(() -> onView(withText(R.string.updates_open_release))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withText(R.string.updates_open_release)).inRoot(isDialog()).perform(click());

        Eventually.check(() -> intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(PAGE))));
    }

    @Test
    public void turningItOffFromTheDialogSaysSo() {
        new UpdateSettings(this.context).setEnabled(true);
        this.open();
        this.seeSubtitle(this.context.getString(
                R.string.updates_subtitle_unchecked, BuildConfig.VERSION_NAME));

        this.tapTheRow();
        Eventually.check(() -> onView(withText(R.string.updates_turn_off))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withText(R.string.updates_turn_off)).inRoot(isDialog()).perform(click());

        this.seeSubtitle(this.context.getString(
                R.string.updates_subtitle_off, BuildConfig.VERSION_NAME));
        assertEquals(Boolean.FALSE, new UpdateSettings(this.context).enabled());
    }

    private void shot(final String name) {
        final Activity[] activity = {null};
        this.scenario.onActivity(a -> activity[0] = a);
        Shot.of(activity[0], name);
    }

    @Test
    public void shotAvailableLight() {
        this.givenANewerReleaseWasFound();
        this.open();
        this.seeSubtitle(this.context.getString(
                R.string.updates_subtitle_available, BuildConfig.VERSION_NAME, "99.0.0"));
        onView(withId(R.id.settings_app_updates)).perform(scrollTo());
        this.shot("settings_updates_available_light");
    }

    @Test
    public void shotOffDark() {
        getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
        this.open();
        this.seeSubtitle(this.context.getString(
                R.string.updates_subtitle_off, BuildConfig.VERSION_NAME));
        onView(withId(R.id.settings_app_updates)).perform(scrollTo());
        this.shot("settings_updates_off_dark");
    }

    @Test
    public void shotTheDialog() {
        this.givenANewerReleaseWasFound();
        this.open();
        this.tapTheRow();
        Eventually.check(() -> onView(withText(R.string.updates_open_release))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        Shot.ofTheScreen("settings_updates_dialog_light");
    }
}
