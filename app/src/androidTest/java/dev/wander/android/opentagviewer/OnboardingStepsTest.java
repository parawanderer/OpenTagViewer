package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBackUnconditionally;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.VerificationModes.times;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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

import java.util.List;

import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.onboarding.OnboardingRecord;
import dev.wander.android.opentagviewer.python.icloud.KeychainMembership;
import dev.wander.android.opentagviewer.update.UpdateSettings;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * The steps after a first sign-in. The map and the iCloud screen are stubbed with
 * Espresso-Intents, so nothing here signs in or loads a map.
 *
 * <p>The test runner grants every permission and marks onboarding as done for the whole run,
 * so this class forgets it first and puts it back after, and forces the nearby step where it
 * wants to see it.
 */
@RunWith(AndroidJUnit4.class)
public class OnboardingStepsTest {

    private final Context context = getInstrumentation().getTargetContext();
    private ActivityScenario<OnboardingActivity> scenario;
    private int nightModeBefore;
    private UserSettings settingsBefore;

    @Before
    public void freshInstall() {
        new OnboardingRecord(this.context).forget();
        this.context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().clear().commit();
        this.memberships().forget().blockingAwait();
        this.settingsBefore = this.settings().getUserSettings();
        this.nightModeBefore = AppCompatDelegate.getDefaultNightMode();
        Intents.init();
        intending(hasComponent(MapsActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_CANCELED, null));
        intending(hasComponent(FetchFromICloudActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_CANCELED, null));
    }

    @After
    public void putItBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        OnboardingActivity.forcedSteps = null;
        new OnboardingRecord(this.context).markShown();
        this.context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().clear().commit();
        this.memberships().forget().blockingAwait();
        this.settings().storeUserSettings(this.settingsBefore).blockingAwait();
        getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setDefaultNightMode(this.nightModeBefore));
    }

    private KeychainMembershipRepository memberships() {
        return new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(this.context), new AppCryptographyUtil());
    }

    private UserSettingsRepository settings() {
        return new UserSettingsRepository(UserSettingsDataStore.getInstance(this.context));
    }

    private void open() {
        this.scenario = ActivityScenario.launch(OnboardingActivity.class);
    }

    private void seeTitle(final int title) {
        Eventually.check(() -> onView(withId(R.id.onboarding_title))
                .check(matches(withText(title))));
    }

    /**
     * One circle per step with a line between each pair, done steps checked, and "Step n of m"
     * for a screen reader, since the circles themselves are not read out.
     */
    private void seeTheStepper(final int at, final int of, final String... circles) {
        Eventually.check(() -> onView(withId(R.id.onboarding_stepper)).check(matches(
                withContentDescription(this.context.getString(R.string.onboarding_step, at, of)))));
        this.scenario.onActivity(a -> {
            final ViewGroup stepper = a.findViewById(R.id.onboarding_stepper);
            assertEquals("circles and the lines between them",
                    circles.length * 2 - 1, stepper.getChildCount());
            for (int i = 0; i < circles.length; i++) {
                assertEquals(circles[i], ((TextView) stepper.getChildAt(i * 2)).getText().toString());
            }
        });
    }

    private void tap(final int buttonId) {
        Eventually.check(() -> onView(withId(buttonId)).check(matches(isDisplayed())));
        onView(withId(buttonId)).perform(click());
    }

    @Test
    public void aFreshInstallIsDueAndOnlyOnce() {
        assertTrue(OnboardingActivity.isDue(this.context));
        this.open();
        this.seeTitle(R.string.onboarding_updates_title);
        assertFalse("shown once means shown once, even if never finished",
                OnboardingActivity.isDue(this.context));
    }

    @Test
    public void skippingEverythingEndsOnTheMapWithUpdatesOff() {
        this.open();

        // The runner granted every permission, so the nearby step is left out: updates, then tags.
        this.seeTitle(R.string.onboarding_updates_title);
        this.seeTheStepper(1, 2, "1", "2");
        this.tap(R.id.onboarding_skip_button);

        this.seeTitle(R.string.onboarding_tags_title);
        this.seeTheStepper(2, 2, "✓", "2");
        this.tap(R.id.onboarding_skip_button);

        Eventually.check(() -> intended(allOf(hasComponent(MapsActivity.class.getName()),
                hasExtra(MapsActivity.EXTRA_CONNECT_ICLOUD, false))));
        assertEquals(Boolean.FALSE, new UpdateSettings(this.context).enabled());
        assertEquals("the map must not make the iCloud offer again",
                Boolean.TRUE, this.settings().getUserSettings().getIcloudOfferMade());
    }

    @Test
    public void checkDailyTurnsUpdateChecksOn() {
        OnboardingActivity.forcedSteps = List.of(OnboardingActivity.Step.UPDATES);
        this.open();

        this.seeTitle(R.string.onboarding_updates_title);
        this.tap(R.id.onboarding_primary_button);

        Eventually.check(() -> intended(hasComponent(MapsActivity.class.getName())));
        assertEquals(Boolean.TRUE, new UpdateSettings(this.context).enabled());
    }

    /**
     * <b>Connecting hands over to the map, not to the iCloud screen.</b> That screen needs the
     * signed-in account restored, which only the map does: opened from here it found none and
     * closed at once, which looked like the button skipping a step (2026-11-22, on the emulator).
     * The map starts the flow itself; TheICloudOfferAppearsOnceTest covers that half.
     */
    @Test
    public void connectingOpensTheMapAskingItToStartTheICloudFlow() {
        OnboardingActivity.forcedSteps = List.of(OnboardingActivity.Step.TAGS);
        this.open();

        this.seeTitle(R.string.onboarding_tags_title);
        this.tap(R.id.onboarding_primary_button);

        Eventually.check(() -> intended(allOf(hasComponent(MapsActivity.class.getName()),
                hasExtra(MapsActivity.EXTRA_CONNECT_ICLOUD, true))));
        intended(hasComponent(FetchFromICloudActivity.class.getName()), times(0));
    }

    @Test
    public void aLinkedAccountSkipsTheTagsStep() {
        this.memberships().store(new KeychainMembership(
                "{\"peer_id\":\"peer-ours\"}", "ZW50cm9weQ==", "a-generated-passcode",
                "a-label", 2)).blockingAwait();
        this.open();

        // Updates is then the only step.
        this.seeTitle(R.string.onboarding_updates_title);
        this.seeTheStepper(1, 1, "1");
    }

    @Test
    public void anUpdateChoiceAlreadyMadeIsNotAskedAgain() {
        new UpdateSettings(this.context).setEnabled(false);
        this.memberships().store(new KeychainMembership(
                "{\"peer_id\":\"peer-ours\"}", "ZW50cm9weQ==", "a-generated-passcode",
                "a-label", 2)).blockingAwait();

        this.open();

        // Nothing left to ask: straight to the map.
        Eventually.check(() -> intended(hasComponent(MapsActivity.class.getName())));
    }

    @Test
    public void allowingTheNearbyStepMovesOn() {
        OnboardingActivity.forcedSteps = List.of(
                OnboardingActivity.Step.NEARBY, OnboardingActivity.Step.UPDATES);
        this.open();

        this.seeTitle(R.string.onboarding_nearby_title);
        // Already granted by the test runner, so Android answers at once without a dialog.
        this.tap(R.id.onboarding_primary_button);

        this.seeTitle(R.string.onboarding_updates_title);
    }

    /**
     * <b>No grey over the middle when the user typed the sign-in on a keyboard.</b>
     *
     * <p>Typing on a hardware keyboard (or the emulator's host keyboard) leaves touch mode, and
     * then the first focusable view gets focus when Setup opens: the scroll area. Android draws
     * its default focus highlight, a grey wash, over any focused view without a background of
     * its own. Seen on the emulator on 2026-11-22; every touch-mode test and screenshot missed
     * it. Drawn to a bitmap and sampled in the empty space above the icon.
     */
    @Test
    public void theMiddleIsNotGreyedOutAfterKeyboardInput() {
        getInstrumentation().setInTouchMode(false);
        try {
            this.open();
            this.seeTitle(R.string.onboarding_updates_title);
            getInstrumentation().waitForIdleSync();

            this.scenario.onActivity(a -> {
                final View root = a.findViewById(R.id.onboarding_root);
                final View step = a.findViewById(R.id.onboarding_step);
                final View icon = a.findViewById(R.id.onboarding_icon);
                final Bitmap drawn = Bitmap.createBitmap(
                        root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                final Canvas canvas = new Canvas(drawn);
                a.getWindow().getDecorView().getBackground().draw(canvas);
                root.draw(canvas);

                final int[] stepAt = new int[2];
                step.getLocationInWindow(stepAt);
                final int[] rootAt = new int[2];
                root.getLocationInWindow(rootAt);
                final int[] iconAt = new int[2];
                icon.getLocationInWindow(iconAt);
                // Halfway between the top of the scroll area and the icon: nothing drawn there.
                final int x = 10;
                final int y = (stepAt[1] + iconAt[1]) / 2 - rootAt[1];
                final int heading = a.findViewById(R.id.onboarding_heading).getTop() - 4;

                assertEquals("the empty middle is a different colour from the top of the screen:"
                                + " a focus highlight is drawn over it",
                        Integer.toHexString(drawn.getPixel(x, Math.max(1, heading))),
                        Integer.toHexString(drawn.getPixel(x, y)));
            });
        } finally {
            getInstrumentation().setInTouchMode(true);
        }
    }

    @Test
    public void backSkipsTheRestToTheMap() {
        this.open();
        this.seeTitle(R.string.onboarding_updates_title);

        // Unconditionally: it finishes this screen, and the map it opens is stubbed.
        pressBackUnconditionally();

        Eventually.check(() -> intended(hasComponent(MapsActivity.class.getName())));
    }

    private void shotOf(final OnboardingActivity.Step step, final boolean night, final String name) {
        OnboardingActivity.forcedSteps = List.of(
                OnboardingActivity.Step.TAGS, OnboardingActivity.Step.NEARBY,
                OnboardingActivity.Step.UPDATES);
        if (night) {
            getInstrumentation().runOnMainSync(
                    () -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
        }
        this.open();
        this.seeTitle(R.string.onboarding_tags_title);
        if (step != OnboardingActivity.Step.TAGS) {
            this.tap(R.id.onboarding_skip_button);
            this.seeTitle(R.string.onboarding_nearby_title);
        }
        if (step == OnboardingActivity.Step.UPDATES) {
            this.tap(R.id.onboarding_skip_button);
            this.seeTitle(R.string.onboarding_updates_title);
        }
        final Activity[] activity = {null};
        this.scenario.onActivity(a -> activity[0] = a);
        Shot.of(activity[0], name);
    }

    @Test
    public void shotTagsLight() {
        this.shotOf(OnboardingActivity.Step.TAGS, false, "onboarding_tags_light");
    }

    @Test
    public void shotNearbyLight() {
        this.shotOf(OnboardingActivity.Step.NEARBY, false, "onboarding_nearby_light");
    }

    @Test
    public void shotUpdatesLight() {
        this.shotOf(OnboardingActivity.Step.UPDATES, false, "onboarding_updates_light");
    }

    @Test
    public void shotTagsDark() {
        this.shotOf(OnboardingActivity.Step.TAGS, true, "onboarding_tags_dark");
    }

    @Test
    public void shotNearbyDark() {
        this.shotOf(OnboardingActivity.Step.NEARBY, true, "onboarding_nearby_dark");
    }

    @Test
    public void shotUpdatesDark() {
        this.shotOf(OnboardingActivity.Step.UPDATES, true, "onboarding_updates_dark");
    }
}
