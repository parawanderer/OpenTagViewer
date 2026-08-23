package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.AppleLoginActivity;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.AccountBeaconsForTests;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.icloud.FakeICloudService;
import dev.wander.android.opentagviewer.python.icloud.KeychainMembership;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Reading the account again from the list, because somebody asked.
 *
 * <p><b>The account was re-read every six hours, and an iPad does it in seconds.</b> So a tag
 * renamed on the owner's own iPad kept its old name here for the rest of the afternoon. This is
 * the on-demand half of the fix - the automatic reads got shorter intervals, and this is the
 * button for when even a minute is too long to wonder.
 *
 * <p>It is also the one refresh path where a refused sign-in sends the user to sign in again.
 * They pressed something and are watching it; a tap that does nothing visible is
 * indistinguishable from a broken button. The periodic and on-resume reads only log, because
 * nobody asked for those. See AGENTS.md rule 14.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class RefreshingFromTheAccountTest {

    private ActivityScenario<MyDevicesListActivity> scenario;
    private KeychainMembershipRepository memberships;

    @Before
    public void linkTheAccount() {
        final Context context = getInstrumentation().getTargetContext();
        this.memberships = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(context), new AppCryptographyUtil());

        AccountBeaconsForTests.forgetThemAll();

        // The item only appears once linked - it is the mirror of "link an account", and only
        // one of the two is ever what somebody wants.
        this.memberships.store(new KeychainMembership(
                "{\"peer_id\":\"peer-ours\"}", "ZW50cm9weQ==", "a-passcode", "a-label", 2))
                .blockingAwait();

        Intents.init();
        intending(hasComponent(AppleLoginActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_CANCELED, null));
    }

    @After
    public void putItBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();
        this.memberships.forget().blockingAwait();
        AccountBeaconsForTests.forgetThemAll();
    }

    private void openTheMenu(final FakeICloudService fake) {
        AppDependencies.replaceICloud(() -> fake);
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);

        Eventually.check(() -> onView(withId(R.id.page_menu_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.page_menu_button)).perform(click());
    }

    /**
     * <b>The item is there once an account is linked, and it is the one that fits.</b>
     *
     * <p>"Link an account" describes work already done by then, so the two swap rather than both
     * being offered - a menu with both reads as three ways in rather than two.
     */
    @Test
    public void atherefreshItemReplacesTheLinkItemOnceLinked() {
        this.openTheMenu(FakeICloudService.withTags());

        Eventually.check(() -> onView(withText(R.string.refresh_from_account))
                .check(matches(isDisplayed())));

        assertTrue("linking should not still be offered once linked",
                nothingOnScreenSays(getInstrumentation().getTargetContext()
                        .getString(R.string.icloud_link_account_action)));
    }

    /** And pressing it actually reads the account rather than only saying it did. */
    @Test
    public void bpressingItReadsTheAccount() {
        final FakeICloudService fake = FakeICloudService.withTags();
        this.openTheMenu(fake);

        onView(withText(R.string.refresh_from_account)).perform(click());

        Eventually.check(() -> assertTrue("the account was never read",
                fake.timesCalled("fetch") > 0));
    }

    /**
     * <b>And a refused sign-in sends them to sign in again, unlike the automatic reads.</b>
     *
     * <p>The difference is who asked. This one has somebody watching it, so a toast saying
     * something vague - or worse, nothing at all - leaves them pressing a button that cannot ever
     * work. The six-hourly read hitting the same wall only writes a log line, because a screen
     * appearing out of a background job is its own bug.
     */
    @Test
    public void carefusedSignInSendsThemToSignInAgain() {
        this.openTheMenu(FakeICloudService.whereAppleRefusesTheCredentials());

        onView(withText(R.string.refresh_from_account)).perform(click());

        Eventually.check(() -> intended(allOf(
                hasComponent(AppleLoginActivity.class.getName()),
                hasExtra(AppleLoginActivity.EXTRA_SESSION_EXPIRED, true))));
    }

    /**
     * An ordinary failure is not worth a sign-in, and says so instead.
     *
     * <p>The mirror of the test above, and the reason the predicate exists rather than a check
     * for "did anything go wrong": sending somebody to the login screen because iCloud had a bad
     * afternoon takes away a working session to fix nothing.
     */
    @Test
    public void danoutageJustSaysSo() {
        final FakeICloudService fake = FakeICloudService.whereTheServiceIsUnsure();
        this.openTheMenu(fake);

        onView(withText(R.string.refresh_from_account)).perform(click());

        // **Waited on by asking the fake, not by matching the toast.** A Toast lives in its own
        // window, so `inRoot(withDecorView(not(...)))` sends Espresso's root picker hunting - and
        // when the toast is not up yet it retries internally for seconds per attempt, inside a
        // retry loop. Written that way this class ran for over ten minutes and had to be killed.
        // A call count answers instantly whether the work has finished.
        Eventually.check(() -> assertTrue("the read never happened",
                fake.timesCalled("open") > 0));

        try {
            intended(hasComponent(AppleLoginActivity.class.getName()));
            fail("an outage must not cost the user a sign-in");
        } catch (final AssertionError expected) {
            // Nothing was launched, which is the point.
        }
    }

    private static boolean nothingOnScreenSays(final String text) {
        try {
            onView(withText(text)).check(matches(isDisplayed()));
            return false;
        } catch (final NoMatchingViewException expected) {
            return true;
        }
    }
}
