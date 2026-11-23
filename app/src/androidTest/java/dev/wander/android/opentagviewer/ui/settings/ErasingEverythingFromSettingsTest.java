package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isNotChecked;
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

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import dev.wander.android.opentagviewer.DeviceStateGuard;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.SettingsActivity;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.LocalDataEraser;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import io.reactivex.rxjava3.core.Completable;

/**
 * Erasing the user's tags, keys and history from Settings: the "Erase all data" row, and the box
 * in the Logout dialog.
 *
 * <p><b>The eraser is a fake, on purpose.</b> This suite shares the real on-device database, and
 * on somebody's own phone that holds their tags. What the real eraser deletes is pinned against an
 * in-memory database by {@code RemovingATagForgetsItsKeysTest}; this checks the screen asks first,
 * calls it only when told to, and does not sign anybody out when it fails.
 *
 * <p>Signing out here is real, so the stored session is captured first and put back afterwards.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ErasingEverythingFromSettingsTest {

    private final Context context = getInstrumentation().getTargetContext();
    private final CountingEraser eraser = new CountingEraser();
    private ActivityScenario<SettingsActivity> scenario;
    private DeviceStateGuard deviceState;

    /** Counts calls, and fails when told to. Erases nothing. */
    private static final class CountingEraser implements LocalDataEraser {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean fail;

        @Override
        public Completable eraseEverything() {
            return Completable.fromAction(() -> {
                this.calls.incrementAndGet();
                if (this.fail) {
                    throw new IllegalStateException("the database is locked, say");
                }
            });
        }
    }

    @Before
    public void setUp() {
        this.deviceState = DeviceStateGuard.capture(this.context);
        // Or Settings loads Apple's real ADI library, which this is not about. See issue #135.
        AppDependencies.replaceAnisette(settings -> FakeAnisetteSource.ready());
        AppDependencies.replaceDataEraser(this.eraser);
    }

    @After
    public void tearDown() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        AppDependencies.reset();
        this.deviceState.restore();
    }

    private UserAuthRepository auth() {
        return new UserAuthRepository(
                UserAuthDataStore.getInstance(this.context.getApplicationContext()),
                new AppCryptographyUtil());
    }

    /** Invented; the name and address belong to nobody. */
    private void givenSomebodyIsSignedIn() {
        this.auth().storeUserAuth(("{\"account\":{\"info\":{"
                        + "\"account_name\":\"jamie.lang@example.com\","
                        + "\"first_name\":\"Jamie\","
                        + "\"last_name\":\"Lang\"}}}").getBytes(StandardCharsets.UTF_8))
                .blockingAwait();
    }

    private boolean stillSignedIn() {
        return this.auth().getUserAuth().blockingFirst().isPresent();
    }

    /** For a result, so that whether Settings asked the map to sign out can be read back. */
    private void open() {
        this.scenario = ActivityScenario.launchActivityForResult(SettingsActivity.class);
    }

    private void tapEraseAllData() {
        Eventually.check(() -> onView(withId(R.id.settings_erase_all_data))
                .perform(scrollTo()).check(matches(isDisplayed())));
        onView(withId(R.id.settings_erase_all_data)).perform(click());
        Eventually.check(() -> onView(withText(R.string.erase_all_data_confirm_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));
    }

    private void tapLogout() {
        Eventually.check(() -> onView(withId(R.id.logout_button))
                .perform(scrollTo()).check(matches(isDisplayed())));
        onView(withId(R.id.logout_button)).perform(click());
        Eventually.check(() -> onView(withId(R.id.dialog_also_erase))
                .inRoot(isDialog()).check(matches(isDisplayed())));
    }

    /**
     * The dialog's Logout button, by id: its title says "Logout" too. Confirming finishes the
     * screen, so the click may report the activity gone - signed out is what says it worked.
     */
    private void confirmLogout() {
        Eventually.perform("Logout", () -> !this.stillSignedIn(),
                () -> onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click()));
    }

    private void assertSentToSignIn() {
        final ActivityResult result = this.scenario.getResult();
        assertEquals(Activity.RESULT_OK, result.getResultCode());
        assertTrue("Settings signed out but did not send the map back to the sign-in screen",
                result.getResultData().getBooleanExtra("requestSendToLogin", false));
    }

    /** The row is there, and tapping it only asks. */
    @Test
    public void eraseAllDataAsksBeforeErasingAnything() {
        this.givenSomebodyIsSignedIn();
        this.open();

        this.tapEraseAllData();
        onView(withText(R.string.erase_all_data_confirm_message))
                .inRoot(isDialog()).check(matches(isDisplayed()));
        onView(withText(R.string.cancel)).inRoot(isDialog()).perform(click());

        assertEquals("cancelling erased anyway", 0, this.eraser.calls.get());
        assertTrue("cancelling signed out anyway", this.stillSignedIn());
    }

    /**
     * The confirmation says what survives and what does not, including that the app stays on the
     * Apple account. That sentence is the sort somebody trims when shortening a dialog.
     */
    @Test
    public void theConfirmationSaysTheAppStaysOnTheAppleAccount() {
        final String message = this.context.getString(R.string.erase_all_data_confirm_message);
        assertTrue(message, message.contains("Apple device"));
        assertTrue(message, message.contains("cannot be undone"));
    }

    /** Confirming erases, then signs out and sends the map to the sign-in screen. */
    @Test
    public void confirmingErasesAndSignsOut() {
        this.givenSomebodyIsSignedIn();
        this.open();

        this.tapEraseAllData();
        // Confirming finishes the screen, so the click may report the activity gone.
        Eventually.perform("Erase", () -> this.eraser.calls.get() > 0,
                () -> onView(withText(R.string.erase_all_data_confirm_button))
                        .inRoot(isDialog()).perform(click()));

        Eventually.check(() -> assertEquals(1, this.eraser.calls.get()));
        Eventually.check(() -> assertFalse("erased but still signed in", this.stillSignedIn()));
        this.assertSentToSignIn();
    }

    /**
     * <b>A failed erase leaves the user signed in, on this screen, and says so.</b> Signing out
     * anyway would land them on the sign-in screen believing their tags were gone.
     */
    @Test
    public void aFailedEraseDoesNotSignOut() {
        this.eraser.fail = true;
        this.givenSomebodyIsSignedIn();
        this.open();

        this.tapEraseAllData();
        onView(withText(R.string.erase_all_data_confirm_button))
                .inRoot(isDialog()).perform(click());

        Eventually.check(() -> assertEquals(1, this.eraser.calls.get()));
        assertTrue("the erase failed, so signing out would mislead", this.stillSignedIn());
        assertEquals(Lifecycle.State.RESUMED, this.scenario.getState());
    }

    /**
     * Logout still only logs out unless asked: tags imported from a file are the only copy
     * anybody has, so the box starts unticked.
     */
    @Test
    public void logoutLeavesTheDataAloneByDefault() {
        this.givenSomebodyIsSignedIn();
        this.open();

        this.tapLogout();
        onView(withId(R.id.dialog_also_erase)).inRoot(isDialog()).check(matches(allOf(
                isNotChecked(), withText(R.string.logout_also_erase_data))));
        this.confirmLogout();

        Eventually.check(() -> assertFalse(this.stillSignedIn()));
        assertEquals("logging out erased data nobody asked to erase", 0, this.eraser.calls.get());
        this.assertSentToSignIn();
    }

    /** Ticking the box erases first. */
    @Test
    public void logoutWithTheBoxTickedErasesFirst() {
        this.givenSomebodyIsSignedIn();
        this.open();

        this.tapLogout();
        onView(withId(R.id.dialog_also_erase)).inRoot(isDialog()).perform(click());
        onView(withId(R.id.dialog_also_erase)).inRoot(isDialog()).check(matches(isChecked()));
        this.confirmLogout();

        Eventually.check(() -> assertEquals(1, this.eraser.calls.get()));
        Eventually.check(() -> assertFalse(this.stillSignedIn()));
        this.assertSentToSignIn();
    }
}
