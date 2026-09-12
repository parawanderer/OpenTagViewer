package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.not;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;

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
import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.icloud.KeychainMembership;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Getting tags in, from a list that already has tags in it.
 *
 * <p><b>Both ways in used to disappear the moment the first one worked.</b> The buttons live in
 * the empty state, and the empty state is hidden as soon as anything is imported - so a user with
 * one tag had no way to add a second, from a file or from their account, without going back to
 * the map's menu for one of them and nowhere at all for the other.
 *
 * <p>It matters most for the account route, and that is why this exists rather than being folded
 * into a broader UI test. The app joins the Apple keychain so that a later read costs one tap and
 * no device passcode - a whole feature, several days of work, tested to the point of asserting
 * that the passcode is never asked for twice - and until now there was no way to ask for that
 * second read. The empty-state test cannot catch it, because in the empty state everything works.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class GettingTagsInWhenTheListIsNotEmptyTest {

    private static final String A_TAG = "test-already-imported-tag";
    private static final String A_NAME = "A Tag That Is Already Here";
    private static final String A_TEST_USER = "gettingtagsintest@example.invalid";

    private static final String A_PLIST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<plist version=\"1.0\"><dict>"
            + "<key>batteryLevel</key><integer>1</integer>"
            + "<key>model</key><string></string>"
            + "<key>pairingDate</key><date>2025-02-27T20:03:32Z</date>"
            + "<key>privateKey</key><dict><key>key</key><dict>"
            + "<key>data</key><data>bm90LWEtcmVhbC1rZXk=</data></dict></dict>"
            + "<key>productId</key><integer>21760</integer>"
            + "<key>stableIdentifier</key><array><string>2001~#0~#A0</string></array>"
            + "<key>systemVersion</key><string>2.0.73</string>"
            + "<key>vendorId</key><integer>76</integer>"
            + "</dict></plist>";

    private OpenTagViewerDatabase db;
    private ActivityScenario<MyDevicesListActivity> scenario;

    @Before
    public void seedOneTagSoTheListIsNotEmpty() {
        final Context context = getInstrumentation().getTargetContext();
        this.db = OpenTagViewerDatabase.getInstance(context);
        this.forgetIt();

        final long importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2")
                .importedAt(1_700_000_000_000L)
                .exportedAt(1_699_000_000_000L)
                .sourceUser(A_TEST_USER)
                .exportedVia("OpenTagViewer.wizard:test")
                .build());

        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(A_TAG).importId(importId).content(A_PLIST).version("0.0.2")
                .fromAccount(false).isRemoved(false).build());

        this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                .id(A_TAG).importId(importId).version("0.0.2").isRemoved(false)
                .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                        + "<key>identifier</key><string>" + A_TAG + "</string>"
                        + "<key>name</key><string>" + A_NAME + "</string>"
                        + "</dict></plist>")
                .build());

        Intents.init();
        // The fetch screen wants an Apple session and a Python interpreter. Nothing here is
        // about what it does - only that it is what the menu item reaches - so it is answered
        // at the door rather than launched.
        Intents.intending(hasComponent(FetchFromICloudActivity.class.getName()))
                .respondWith(new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null));
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null));
    }

    @After
    public void putEverythingBack() {
        Intents.release();
        if (this.scenario != null) {
            this.scenario.close();
        }
        this.forgetIt();
        new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil()).forget().blockingAwait();
    }

    /** As if the app had already joined the account's keychain. */
    private void givenTheAccountIsLinked() {
        new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil())
                .store(new KeychainMembership(
                        "{\"peer_id\":\"peer-ours\"}", "ZW50cm9weQ==", "a-passcode",
                        "a-label", 2))
                .blockingAwait();
    }

    private void forgetIt() {
        this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(A_TAG).build());
        this.db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(A_TAG).build());
        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
    }

    private void openTheListWithATagInIt() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);
        Eventually.check(() -> onView(withText(A_NAME)).check(matches(isDisplayed())));
    }

    /** The premise: with a tag in the list, the empty state and its buttons are gone. */
    @Test
    public void theemptyStateButtonsAreNotOnScreenOnceSomethingIsImported() {
        this.openTheListWithATagInIt();

        onView(withId(R.id.my_devices_empty_state)).check(matches(not(isDisplayed())));
        onView(withId(R.id.my_devices_empty_fetch_button)).check(matches(not(isDisplayed())));
    }

    @Test
    public void theoverflowMenuOffersBothWaysIn() {
        this.openTheListWithATagInIt();

        onView(withId(R.id.page_menu_button)).perform(click());

        Eventually.check(() -> onView(withText(R.string.icloud_link_account_action))
                .inRoot(isPlatformPopup()).check(matches(isDisplayed())));
        onView(withText(R.string.icloud_import_from_file))
                .inRoot(isPlatformPopup()).check(matches(isDisplayed()));
        onView(withText(R.string.import_history))
                .inRoot(isPlatformPopup()).check(matches(isDisplayed()));
    }

    @Test
    public void importingHistoryAsksTheSystemForAZipAndCancellationIsSilent() {
        this.openTheListWithATagInIt();

        onView(withId(R.id.page_menu_button)).perform(click());
        Eventually.check(() -> onView(withText(R.string.import_history))
                .inRoot(isPlatformPopup()).check(matches(isDisplayed())));
        onView(withText(R.string.import_history)).inRoot(isPlatformPopup()).perform(click());

        Eventually.check(() -> intended(allOf(
                hasAction(Intent.ACTION_OPEN_DOCUMENT),
                hasExtra(Intent.EXTRA_MIME_TYPES, arrayContaining("application/zip")))));
        onView(withText(R.string.history_import_complete_title)).check(doesNotExist());
        onView(withText(R.string.history_import_failed_title)).check(doesNotExist());
    }

    /**
     * <b>And linking disappears once the account is linked.</b>
     *
     * <p>The item links an account; after that the app is a member of the keychain and re-reads
     * without asking for anything, so offering to link again describes work already done.
     * Importing a file stays, because somebody with a linked account can still be handed a
     * bundle for a tag that is not theirs.
     */
    @Test
    public void linkingIsNotOfferedOnceTheAccountIsLinked() {
        this.givenTheAccountIsLinked();

        this.openTheListWithATagInIt();
        Eventually.check(() -> onView(withId(R.id.page_menu_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.page_menu_button)).perform(click());

        Eventually.check(() -> onView(withText(R.string.icloud_import_from_file))
                .inRoot(isPlatformPopup()).check(matches(isDisplayed())));
        onView(withText(R.string.icloud_link_account_action)).check(doesNotExist());
    }

    /** <b>The one that matters.</b> A second read of the account is one tap away. */
    @Test
    public void thefetchItemReachesTheAccountScreen() {
        this.openTheListWithATagInIt();

        onView(withId(R.id.page_menu_button)).perform(click());
        Eventually.check(() -> onView(withText(R.string.icloud_link_account_action))
                .inRoot(isPlatformPopup()).check(matches(isDisplayed())));
        onView(withText(R.string.icloud_link_account_action))
                .inRoot(isPlatformPopup()).perform(click());

        Eventually.check(() -> intended(hasComponent(FetchFromICloudActivity.class.getName())));
    }

    /** And the empty state's own button still goes to the same place. */
    @Test
    public void theemptyStateButtonStillWorks() {
        this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(A_TAG).build());

        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);
        Eventually.check(() -> onView(withId(R.id.my_devices_empty_fetch_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.my_devices_empty_fetch_button)).perform(click());

        Eventually.check(() -> intended(hasComponent(FetchFromICloudActivity.class.getName())));
    }
}
