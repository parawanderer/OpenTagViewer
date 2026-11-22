package dev.wander.android.opentagviewer.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.Activity;
import android.os.SystemClock;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.AppleLoginActivity;
import dev.wander.android.opentagviewer.DeviceStateGuard;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.SettingsActivity;
import dev.wander.android.opentagviewer.Shot;
import dev.wander.android.opentagviewer.anisette.EmulatedMacAnisette;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.FakeAppleAuthService;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * The "couldn't download CoreADI" screens, for the wiki's Anisette Overview page: the sign-in
 * screen's offer, and the same in Settings -> Anisette Provider.
 *
 * <p>Nothing here talks to Apple: {@link FakeAnisetteSource} reports the download failure, as a
 * phone on a network that blocks Apple's download server would. Skipped unless capturing, like
 * the other wiki classes ({@link OnlyWhenCapturing}).
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class WikiScreenshotsOfCoreAdiTest {

    private ActivityScenario<?> scenario;
    private DeviceStateGuard deviceState;
    private boolean setUpRan;

    @Before
    public void aPhoneThatCannotDownloadCoreAdi() {
        OnlyWhenCapturing.wasAskedFor();
        this.setUpRan = true;

        this.deviceState = DeviceStateGuard.capture(getInstrumentation().getTargetContext());
        this.signEverybodyOut();
        new UserSettingsRepository(
                UserSettingsDataStore.getInstance(getInstrumentation().getTargetContext()))
                .storeUserSettings(UserSettings.builder()
                        .anisetteMode(UserSettings.ANISETTE_LOCAL).build())
                .blockingAwait();

        AppDependencies.replaceAuthService(FakeAppleAuthService.signsInImmediately());
        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.unavailable(
                EmulatedMacAnisette.NO_COREADI_REASON
                        + ": could not download the library from Apple: UnknownHostException"));
    }

    @After
    public void putItBack() {
        if (!this.setUpRan) {
            return;
        }
        if (this.scenario != null) {
            this.scenario.close();
        }
        AppDependencies.reset();
        getInstrumentation().waitForIdleSync();
        this.signEverybodyOut();
        this.deviceState.restore();
    }

    @Test
    public void athesignInScreensOffer() {
        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);
        Eventually.check(() -> onView(withId(R.id.anisetteCoreAdiContainer))
                .check(matches(isDisplayed())));
        // The window rather than the screen: the managed device is headless, and a screen
        // capture there comes back blank.
        final Activity[] activity = {null};
        this.scenario.onActivity(a -> activity[0] = a);
        Shot.of(activity[0], "anisette-coreadi-1-sign-in");
    }

    @Test
    public void btheSameInSettings() {
        this.scenario = ActivityScenario.launch(SettingsActivity.class);
        Eventually.check(() -> onView(withId(R.id.setting_app_anisette_server))
                .perform(scrollTo(), click()));
        // Settings shows it in the "your own file" box it already had, worded for CoreADI.
        final View[] inTheDialog = {null};
        Eventually.check(() -> onView(withId(R.id.anisetteOwnApkContainer))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
                .check((view, noMatch) -> inTheDialog[0] = view));
        Shot.ofTheWindowOf(inTheDialog[0], "anisette-coreadi-2-settings");
    }

    private void signEverybodyOut() {
        final UserAuthRepository auth = new UserAuthRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil());
        for (int attempt = 0; attempt < 40; attempt++) {
            if (auth.getUserAuth().blockingFirst().isEmpty()) {
                return;
            }
            auth.clearUser().blockingAwait();
            SystemClock.sleep(50);
        }
    }
}
