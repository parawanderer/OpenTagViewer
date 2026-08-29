package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingRootException;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.DeviceStateGuard;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.FetchFromICloudActivity;
import dev.wander.android.opentagviewer.ui.error.ErrorReportActivity;
import dev.wander.android.opentagviewer.MapsActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.python.icloud.KeychainMembership;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import dev.wander.android.opentagviewer.util.rx.RefreshPolicy;

/**
 * The one-time offer to read tags out of the Apple account.
 *
 * <p><b>Two people are meant to see it and nobody else.</b> Somebody setting the app up from
 * scratch, and somebody updating from an older version - both have no account connected and no
 * record of being asked, which is what the condition actually tests. There is no migration code
 * and no version check: an upgrader simply has no flag, because the flag did not exist when they
 * last ran the app.
 *
 * <p><b>And it must never come back.</b> Somebody happy importing zips should be asked once, ever.
 * That is the half worth the most tests, because getting it wrong turns the app into something
 * that nags - and a returning prompt is one people learn to dismiss without reading, which costs
 * the offer any value it had.
 *
 * <p>Arranges its own settings rather than using {@code AMapWithTagsOnIt}, whose whole job is to
 * get to a map with no dialogs in front of it - it suppresses this offer deliberately.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class TheICloudOfferAppearsOnceTest {

    private final Context context = getInstrumentation().getTargetContext();

    private DeviceStateGuard guard;
    private PyObject appleDouble;
    private UserSettingsRepository settingsRepo;
    private KeychainMembershipRepository memberships;
    private ActivityScenario<MapsActivity> scenario;

    @Before
    public void startFromNothing() {
        this.guard = DeviceStateGuard.capture(this.context);
        this.settingsRepo = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.context));
        this.memberships = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(this.context), new AppCryptographyUtil());

        this.memberships.forget().blockingAwait();

        // Signed in, because the offer is only made to somebody who got past the door.
        new UserAuthRepository(
                UserAuthDataStore.getInstance(this.context), new AppCryptographyUtil())
                .storeUserAuth("{\"not\":\"a restorable account\"}".getBytes(StandardCharsets.UTF_8))
                .blockingAwait();

        // **The session has to restore, or the map tears itself down mid-test.** A hand-written
        // blob cannot be restored for real, and MapsActivity's response to that is correct and
        // fatal here: it clears the login and finishes for the login screen. The offer is raised
        // before the restore resolves, so the dialog appears and is then pulled off screen a
        // second later - which reads as "the offer did not appear" and, when it happens during
        // teardown, takes the instrumentation process with it.
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this.context));
        }
        this.appleDouble = Python.getInstance().getModule("apple_test_double");
        this.appleDouble.callAttr("installWithNothingToReport");

        // **Or the map loads Apple's real ADI library and can take the whole run down.**
        // MapsActivity asks for Anisette on the way to restoring, and the real source dlopens
        // libstoreservicescore.so and initialises it - which segfaults often enough to abort a
        // suite, on an Rx thread, killing every test after it. Nothing here is testing Anisette.
        AppDependencies.replaceAnisette(whateverTheSettingsSay -> FakeAnisetteSource.ready());

        // Process-wide and outlives any activity, so a previous test's fetch would suppress the
        // startup one here.
        RefreshPolicy.resetShared();

        Intents.init();
    }

    @After
    public void putItBack() {
        Intents.release();
        if (this.scenario != null) {
            this.scenario.close();
        }
        if (this.appleDouble != null) {
            this.appleDouble.callAttr("uninstall");
        }
        AppDependencies.reset();
        this.memberships.forget().blockingAwait();
        if (this.guard != null) {
            this.guard.restore();
        }
    }

    // --- the two people it is for ---------------------------------------------------------------

    /**
     * <b>Somebody setting the app up from scratch is asked.</b>
     *
     * <p>A new install resolves to local Anisette without being asked, so that offer is not due
     * and this one has the screen to itself.
     */
    @Test
    public void anewUserIsOffered() {
        this.settingsWhere(settings -> settings.setAnisetteMode(UserSettings.ANISETTE_LOCAL));

        this.openTheMap();

        Eventually.check(() -> onView(withText(R.string.icloud_offer_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));
    }

    /**
     * <b>And so is somebody updating from an older version.</b>
     *
     * <p>They have a session and a server URL and no {@code icloudOfferMade} key, because it did
     * not exist when they last ran the app. Their Anisette question is already answered here, so
     * it is not standing in front of this one - the test below covers when it is.
     */
    @Test
    public void anupgraderIsOfferedToo() {
        this.settingsWhere(settings -> {
            settings.setAnisetteServerUrl("https://someone-elses-server.example");
            settings.setAnisetteUpgradeOffered(true);
        });

        this.openTheMap();

        Eventually.check(() -> onView(withText(R.string.icloud_offer_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));
    }

    // --- and nobody else ------------------------------------------------------------------------

    /**
     * <b>Dismissing it once is final.</b>
     *
     * <p>The assertion that matters most. Somebody who is happy importing zips should never be
     * bothered again, and the flag is written when the dialog is <i>shown</i> rather than when a
     * button is pressed - so this covers declining, swiping away, and the screen being torn down
     * mid-dialog alike.
     *
     * <p>Reopening the map is the real test of that, not reading the setting back: a stored flag
     * nothing consults would look identical in the database and still nag.
     */
    @Test
    public void itneverComesBackOnceItHasBeenSeen() {
        this.settingsWhere(settings -> settings.setAnisetteMode(UserSettings.ANISETTE_LOCAL));

        this.openTheMap();
        Eventually.check(() -> onView(withText(R.string.icloud_offer_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));

        onView(withText(R.string.icloud_offer_not_now)).inRoot(isDialog()).perform(click());

        Eventually.check(() -> assertTrue("declining still has to record that it was offered",
                this.settingsRepo.getUserSettings().getIcloudOfferMade() == Boolean.TRUE));

        this.scenario.close();
        this.openTheMap();

        this.letTheMapSettle();
        assertFalse("somebody who said no once must never be asked again",
                this.theOfferIsShowing());
    }

    /**
     * <b>Showing it is what records it, before anybody has answered.</b>
     *
     * <p>The class promises that a dialog dismissed by the activity being torn down still counts
     * as the one time. That only holds if the write happens when the dialog goes up - so this
     * asserts exactly that, with nothing pressed.
     */
    @Test
    public void theOfferIsRecordedTheMomentItIsShown() {
        this.settingsWhere(settings -> settings.setAnisetteMode(UserSettings.ANISETTE_LOCAL));

        this.openTheMap();
        Eventually.check(() -> onView(withText(R.string.icloud_offer_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));

        Eventually.check(() -> assertTrue(
                "the offer has to be recorded when it is shown, not when it is answered",
                this.theOfferHasBeenMade()));
    }

    /**
     * <b>A resume while the offer is up must not lose the record of it.</b>
     *
     * <p>This is the bug a user hit: the prompt came back days after they had answered it, with
     * an account already connected. {@code MapsActivity.onResume} re-reads the settings into the
     * field the dialog had marked, so the marked object was replaced by a fresh one still saying
     * the offer had never been made - and the answer then saved that. Nothing failed, nothing
     * logged, and the prompt returned on every launch.
     *
     * <p>A resume between showing and answering is not a contrived sequence: it is what happens
     * when somebody glances at another app and comes back, and it is also the ordinary
     * onCreate/onResume ordering when the membership lookup answers quickly.
     *
     * <p>Confirmed to fail before the fix - the stored flag came back false, and the offer
     * appeared again on reopening.
     */
    @Test
    public void theOfferSurvivesAResumeWhileItIsOnScreen() {
        this.settingsWhere(settings -> settings.setAnisetteMode(UserSettings.ANISETTE_LOCAL));

        this.openTheMap();
        Eventually.check(() -> onView(withText(R.string.icloud_offer_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));

        // The step that used to swap the settings object out from under the dialog.
        this.scenario.moveToState(Lifecycle.State.STARTED);
        this.scenario.moveToState(Lifecycle.State.RESUMED);

        Eventually.check(() -> assertTrue(
                "a resume while the dialog was up threw away the record that it was offered",
                this.theOfferHasBeenMade()));

        this.scenario.close();
        this.openTheMap();

        this.letTheMapSettle();
        assertFalse("the offer came back after a resume, which is the reported bug",
                this.theOfferIsShowing());
    }

    /**
     * <b>A connection that exists but cannot be read is a fault, not a fresh install.</b>
     *
     * <p>The membership read used to answer "empty" both for somebody who had never joined and
     * for somebody whose stored keys could no longer be decrypted - so a device whose secure
     * storage had moved on was shown the first-time setup offer, once, and if that was declined
     * the app behaved from then on as though iCloud had never been wanted. Nothing said anything
     * was wrong; account reads just stopped working.
     *
     * <p>Two claims here, and the second is the one with teeth: they are told what happened, and
     * their one-and-only first-time offer is <b>not</b> spent on it. Spending it would leave
     * somebody who dismissed a message they did not understand with no way back to the feature
     * except a Settings item they have no reason to open.
     */
    @Test
    public void aConnectionWhoseKeyHasGoneAsksThemToReconnect() {
        this.settingsWhere(settings -> settings.setAnisetteMode(UserSettings.ANISETTE_LOCAL));
        this.givenAConnection();
        this.andThenItsKeystoreKeyDisappears();

        this.openTheMap();

        Eventually.check(() -> onView(withText(R.string.icloud_membership_unreadable_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));

        assertFalse("the one-time offer must not be spent on a broken connection",
                this.theOfferHasBeenMade());
    }

    /**
     * <b>And the same situation with the key still present is a bug, so it offers a report.</b>
     *
     * <p>The distinction @parawanderer asked for. A key that has gone is somebody's device - an
     * OS upgrade, a wiped keystore, a transfer tool that copied app data and could not copy
     * keystore keys - and the useful thing to say is "connect it again". A key that is right
     * there and still does not open the data is not explainable by any of that, so an
     * explanation would be an apology for something the user cannot act on, and the report is
     * what is actually worth offering.
     */
    @Test
    public void aConnectionThatWillNotOpenWithItsOwnKeyOffersABugReport() {
        this.settingsWhere(settings -> settings.setAnisetteMode(UserSettings.ANISETTE_LOCAL));
        this.givenAConnection();
        this.butItsStoredBytesAreDamaged();

        this.openTheMap();

        Eventually.check(() -> intended(hasComponent(ErrorReportActivity.class.getName())));

        assertFalse("a fault must not spend the one-time offer either",
                this.theOfferHasBeenMade());
    }

    /** A real membership, written through the real writer, so the ciphertext is genuine. */
    private void givenAConnection() {
        this.memberships.store(new KeychainMembership(
                "{\"peer\":\"invented\"}", "entropy", "PASS-CODE-HERE", "This phone", 1))
                .blockingAwait();
    }

    /**
     * Take the keystore key away and leave the data behind.
     *
     * <p>Deleted rather than corrupted, because that is the actual shape of the situation: the
     * keystore and this app's files have different lifetimes, and it is always the key that
     * goes. Done explicitly rather than by writing junk and hoping the alias happens not to
     * exist - an earlier test in the run may well have created it, which would make this assert
     * the opposite case by accident.
     */
    private void andThenItsKeystoreKeyDisappears() {
        try {
            final java.security.KeyStore keyStore =
                    java.security.KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            keyStore.deleteEntry(
                    dev.wander.android.opentagviewer.AppKeyStoreConstants.KEYSTORE_ALIAS_KEYCHAIN);
        } catch (final Exception e) {
            throw new IllegalStateException("could not take the keystore key away", e);
        }
    }

    /** Keep the key, ruin the ciphertext: the combination that should not be possible. */
    private void butItsStoredBytesAreDamaged() {
        UserAuthDataStore.getInstance(this.context).updateDataAsync(preferences -> {
            final androidx.datastore.preferences.core.MutablePreferences mutable =
                    preferences.toMutablePreferences();
            mutable.set(UserAuthDataStore.KEYCHAIN_MEMBERSHIP,
                    new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
            return io.reactivex.rxjava3.core.Single.just(mutable);
        }).blockingGet();
    }

    /**
     * <b>Somebody already reading their account is not asked.</b>
     *
     * <p>Asking would read as the app having lost track of what it is already doing - and would
     * spend their one prompt on a question that does not apply to them.
     */
    @Test
    public void somebodyAlreadyConnectedIsNotAsked() {
        this.settingsWhere(settings -> settings.setAnisetteMode(UserSettings.ANISETTE_LOCAL));
        this.memberships.store(new KeychainMembership(
                "{\"peer_id\":\"peer-ours\"}", "ZW50cm9weQ==", "a-passcode", "a-label", 2))
                .blockingAwait();

        this.openTheMap();

        this.letTheMapSettle();
        assertFalse("an account is already connected; there is nothing to offer",
                this.theOfferHasBeenMade());
    }

    /**
     * <b>The Anisette offer goes first, and this one is deferred rather than spent.</b>
     *
     * <p>Somebody updating qualifies for both at the same moment. Two dialogs stacked on the map
     * is how people learn to dismiss dialogs unread, so Anisette wins - it is about the session
     * continuing to work at all.
     *
     * <p>The half that could go wrong quietly is the deferral: nothing may mark this offer as
     * made while it is being skipped, or the user simply never sees it.
     */
    @Test
    public void thedeferredOfferIsNotSilentlySpent() {
        this.settingsWhere(settings ->
                settings.setAnisetteServerUrl("https://someone-elses-server.example"));

        this.openTheMap();

        Eventually.check(() -> onView(withText(R.string.anisette_upgrade_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));

        assertFalse("the iCloud offer must not be marked made while it was never shown",
                this.settingsRepo.getUserSettings().getIcloudOfferMade() == Boolean.TRUE);
    }

    // --- accepting --------------------------------------------------------------------------------

    /** <b>Set up now takes them to the screen that does it.</b> */
    @Test
    public void acceptingOpensTheAccountFetch() {
        this.settingsWhere(settings -> settings.setAnisetteMode(UserSettings.ANISETTE_LOCAL));

        this.openTheMap();
        Eventually.check(() -> onView(withText(R.string.icloud_offer_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));

        onView(withText(R.string.icloud_offer_set_up_now)).inRoot(isDialog()).perform(click());

        Eventually.check(() -> intended(hasComponent(FetchFromICloudActivity.class.getName())));
    }

    // --- helpers ------------------------------------------------------------------------------

    /**
     * Put the settings in a known state, then let the test say what is different about it.
     *
     * <p><b>Every field the decision reads is reset, not just the obvious one.</b> These settings
     * are the real device's and survive between tests in the class, and the condition under test
     * depends on four of them. Resetting only {@code icloudOfferMade} meant
     * {@code anupgraderIsOfferedToo} left {@code anisetteUpgradeOffered} true behind it, and the
     * next test - whose whole point is that the Anisette offer comes first - silently ran with
     * the Anisette offer already answered. It failed for a reason that had nothing to do with
     * the behaviour, and would have passed or failed depending on the order tests ran in.
     */
    private void settingsWhere(final java.util.function.Consumer<UserSettings> arrange) {
        final UserSettings settings = this.settingsRepo.getUserSettings();

        settings.setIcloudOfferMade(false);
        settings.setAnisetteUpgradeOffered(false);
        settings.setAnisetteMode(null);
        settings.setAnisetteServerUrl(null);

        arrange.accept(settings);
        this.settingsRepo.storeUserSettings(settings).blockingAwait();
    }

    private void openTheMap() {
        this.scenario = ActivityScenario.launch(
                new Intent(this.context, MapsActivity.class));
    }

    /**
     * Whether the offer has been made, read from storage rather than from the screen.
     *
     * <p><b>Never ask {@code inRoot(isDialog())} whether a dialog is absent.</b> Espresso's root
     * picker retries internally for seconds before admitting there is no dialog, so the "no"
     * answer is the slow one - and inside an {@code Eventually} loop that is seconds per retry,
     * for as long as the loop runs. The same mistake cost {@code UnlinkTheAccountSettingTest}
     * 6m 33s for seven tests before it was rewritten to ask a repository. This class had three
     * of them and took minutes.
     *
     * <p>Reading the flag is also the better assertion. The flag is written the moment the
     * dialog is shown, so "was it offered" is exactly what it records - and unlike a screenshot
     * of the screen it stays true after the dialog goes away.
     */
    private boolean theOfferHasBeenMade() {
        return this.settingsRepo.getUserSettings().getIcloudOfferMade() == Boolean.TRUE;
    }

    /**
     * Whether the dialog is on screen right now.
     *
     * <p><b>Call this once, never inside a retry loop.</b> Answering "no" means Espresso's root
     * picker exhausting its internal retries first, which takes seconds - fine once, ruinous
     * repeatedly. Use {@link #letTheMapSettle()} to make the moment meaningful instead of
     * retrying the question.
     *
     * <p>Needed despite {@link #theOfferHasBeenMade()} for the one case the flag cannot answer:
     * on a second visit the flag is already true whether or not the dialog came back, so only
     * looking at the screen distinguishes "asked once" from "asked again".
     */
    private boolean theOfferIsShowing() {
        try {
            onView(withText(R.string.icloud_offer_title))
                    .inRoot(isDialog()).check(matches(isDisplayed()));
            return true;
        } catch (final NoMatchingViewException | NoMatchingRootException | AssertionError e) {
            // **NoMatchingRootException is the one that actually fires here.** With no dialog up
            // there is no dialog *root* to search, so Espresso gives up a step earlier than the
            // obvious exception - and catching only NoMatchingViewException lets the real case
            // through as a failure.
            return false;
        }
    }

    /**
     * Give the map long enough to have raised the offer if it were going to.
     *
     * <p>Needed because the assertions above are about an <i>absence</i>, and an absence is true
     * immediately whether or not anything was ever going to happen. The offer is raised off a
     * membership lookup that hops threads, so this waits for the thing that always happens on
     * this screen - the tag carousel being laid out - before concluding that the thing that
     * should not happen did not.
     */
    private void letTheMapSettle() {
        Eventually.check(() -> onView(withId(R.id.map_container))
                .check(matches(isDisplayed())));
    }
}
