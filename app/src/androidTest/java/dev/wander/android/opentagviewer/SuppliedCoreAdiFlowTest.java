package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import dev.wander.android.opentagviewer.anisette.EmulatedMacAnisette;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.FakeAppleAuthService;
import dev.wander.android.opentagviewer.service.web.FakeAnisetteServerTester;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Signing in on a phone that could not download CoreADI.
 *
 * <p>The trap this guards against: a working server signs in, and then every location fetch
 * answers 401. So the screen has to stop on the Anisette step and offer the file, even when a
 * stored server answers, which used to send it straight to the account form.
 *
 * <p>A file that passes cannot be driven here: only Apple's real disk image matches the pins.
 * {@code MacCoreAdiFetcherTest} covers that on the JVM. What this covers is the screen.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class SuppliedCoreAdiFlowTest {

    private static final String SERVER = "https://ani.example.com";

    private ActivityScenario<AppleLoginActivity> scenario;
    private DeviceStateGuard deviceState;
    private int nightModeBefore;
    private final AtomicInteger sourcesMade = new AtomicInteger();

    private static FakeAnisetteSource noCoreAdi() {
        return FakeAnisetteSource.unavailable(EmulatedMacAnisette.NO_COREADI_REASON
                + ": could not download the library from Apple: UnknownHostException");
    }

    @Before
    public void aPhoneThatCannotDownloadCoreAdi() {
        this.deviceState = DeviceStateGuard.capture(getInstrumentation().getTargetContext());
        this.nightModeBefore = AppCompatDelegate.getDefaultNightMode();

        signEverybodyOut();
        // A stored server that answers: the case that used to skip straight past the offer.
        new UserSettingsRepository(
                UserSettingsDataStore.getInstance(getInstrumentation().getTargetContext()))
                .storeUserSettings(UserSettings.builder().anisetteServerUrl(SERVER).build())
                .blockingAwait();

        AppDependencies.replaceAuthService(FakeAppleAuthService.signsInImmediately());
        AppDependencies.replaceServerTester(FakeAnisetteServerTester.thatIsUp());
        AppDependencies.replaceAnisette(settings -> {
            this.sourcesMade.incrementAndGet();
            return noCoreAdi();
        });

        Intents.init();
    }

    @After
    public void putTheRealOnesBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();
        getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setDefaultNightMode(this.nightModeBefore));

        getInstrumentation().waitForIdleSync();
        signEverybodyOut();
        this.deviceState.restore();
    }

    private void launch() {
        this.scenario = ActivityScenario.launch(AppleLoginActivity.class);
        TestPace.afterAStep();
    }

    private void shot(final String name) {
        final AppleLoginActivity[] activity = {null};
        this.scenario.onActivity(a -> activity[0] = a);
        Shot.of(activity[0], name);
    }

    @Test
    public void aStoredServerThatAnswersDoesNotSkipTheOffer() {
        launch();

        Eventually.check(() -> onView(withId(R.id.anisetteCoreAdiContainer))
                .perform(scrollTo()).check(matches(isDisplayed())));
        onView(withId(R.id.email_or_phone_input_field)).check(matches(not(isDisplayed())));
        // The explanation and every button are inside the card, and the loose line that
        // explains the other fallbacks stays hidden, so it is not said twice.
        onView(withId(R.id.anisetteCoreAdiExplanation))
                .check(matches(withText(R.string.anisette_login_needs_coreadi)));
        onView(withId(R.id.anisetteLoginFallbackReason)).check(matches(not(isDisplayed())));
        for (final int inside : new int[]{R.id.anisetteCoreAdiExplanation,
                R.id.anisetteCoreAdiLink, R.id.anisetteCopyCoreAdiLinkButton,
                R.id.anisetteChooseCoreAdiButton, R.id.anisetteRetryCoreAdiButton}) {
            onView(withId(inside)).check(matches(
                    isDescendantOfA(withId(R.id.anisetteCoreAdiContainer))));
        }

        // The icon sits beside the title only: the explanation starts where the icon does.
        this.scenario.onActivity(a -> {
            final int[] icon = new int[2];
            final int[] text = new int[2];
            a.findViewById(R.id.anisetteCoreAdiIcon).getLocationOnScreen(icon);
            a.findViewById(R.id.anisetteCoreAdiExplanation).getLocationOnScreen(text);
            assertEquals("explanation left edge", icon[0], text[0]);
        });

        this.shot("coreadi_1_offer_light");
    }

    @Test
    public void theOfferInDarkMode() {
        getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
        launch();

        Eventually.check(() -> onView(withId(R.id.anisetteCoreAdiContainer))
                .perform(scrollTo()).check(matches(isDisplayed())));

        this.shot("coreadi_2_offer_dark");
    }

    @Test
    public void choosingAFileAsksTheSystemForOne() {
        stubThePickerWith(null);
        launch();

        Eventually.check(() -> onView(withId(R.id.anisetteChooseCoreAdiButton))
                .perform(scrollTo(), click()));

        Eventually.check(() -> intended(hasAction(Intent.ACTION_OPEN_DOCUMENT)));
    }

    @Test
    public void aFileThatIsNotCoreAdiIsRefusedWithTheReason() throws Exception {
        stubThePickerWith(aHolidayPhoto());
        launch();

        Eventually.check(() -> onView(withId(R.id.anisetteChooseCoreAdiButton))
                .perform(scrollTo(), click()));

        Eventually.check(() -> onView(withId(R.id.anisetteCoreAdiRejection))
                .perform(scrollTo())
                .check(matches(allOf(isDisplayed(), withText(containsString("too small"))))));
        assertFalse("nothing may be stored from a refused file",
                EmulatedMacAnisette.libraryFile(getInstrumentation().getTargetContext()).exists());

        this.shot("coreadi_3_refused");
    }

    /** Retry after the file arrived (or the network came back) moves on to the account form. */
    @Test
    public void retryingOnceItWorksMovesOn() {
        launch();
        Eventually.check(() -> onView(withId(R.id.anisetteRetryCoreAdiButton))
                .perform(scrollTo()).check(matches(isDisplayed())));

        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());
        Eventually.perform("retry",
                () -> isShown(R.id.login_maininfo_container),
                () -> onView(withId(R.id.anisetteRetryCoreAdiButton)).perform(scrollTo(), click()));

        Eventually.check(() -> onView(withId(R.id.email_or_phone_input_field))
                .perform(scrollTo()).check(matches(isDisplayed())));
    }

    private boolean isShown(final int id) {
        final boolean[] shown = {false};
        this.scenario.onActivity(a -> shown[0] = a.findViewById(id).isShown());
        return shown[0];
    }

    private void stubThePickerWith(final Uri chosen) {
        final Intent data = chosen == null ? null : new Intent().setData(chosen);
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(new ActivityResult(
                chosen == null ? Activity.RESULT_CANCELED : Activity.RESULT_OK, data));
    }

    private static Uri aHolidayPhoto() throws Exception {
        final File photo = new File(getInstrumentation().getTargetContext().getCacheDir(),
                "holiday.jpg");
        try (FileOutputStream out = new FileOutputStream(photo)) {
            out.write("not a disk image".getBytes(StandardCharsets.US_ASCII));
        }
        return Uri.fromFile(photo);
    }

    private static void signEverybodyOut() {
        final UserAuthRepository auth = new UserAuthRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil());

        int consecutivelyEmpty = 0;
        for (int attempt = 0; attempt < 40 && consecutivelyEmpty < 3; attempt++) {
            if (auth.getUserAuth().blockingFirst().isEmpty()) {
                consecutivelyEmpty++;
            } else {
                consecutivelyEmpty = 0;
                auth.clearUser().blockingAwait();
            }
            SystemClock.sleep(50);
        }
        if (consecutivelyEmpty < 3) {
            throw new IllegalStateException("a stored session kept coming back");
        }
    }
}
