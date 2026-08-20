package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.DeviceInfoActivity;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;

/**
 * Removing a tag the app does not own.
 *
 * <p><b>The failure this prevents is silent and self-undoing.</b> A tag read from the Apple
 * account is a cache of what Apple holds: marking it removed appears to work, the row leaves the
 * list, and then the next refresh writes it back with {@code is_removed = 0} and the tag returns
 * with no explanation at all. Nothing logs, nothing throws, and the user is left believing the
 * app ignored them.
 *
 * <p>So the destructive button is not offered for one. What replaces it is an explanation of
 * where the tag can actually be removed - in Find My, on an Apple device - because "not
 * supported" with no next step is only marginally better than the lie.
 *
 * <p>Seeds the real on-device database rather than an in-memory one, because
 * {@link OpenTagViewerDatabase#getInstance} is a plain singleton with no seam and this has to
 * drive the real activity. Every row it writes is deleted by id, before and after - before as
 * well, because a test that crashes half way leaves its rows behind and the next class to run
 * inherits them.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class RemoveAccountTagTest {

    private static final String FROM_ACCOUNT = "test-account-tag";
    private static final String FROM_A_FILE = "test-file-tag";
    private static final String ALSO_FROM_ACCOUNT = "test-account-tag-2";

    private static final String ACCOUNT_NAME = "Tag On The Account";
    private static final String FILE_NAME = "Tag From A Zip";
    private static final String OTHER_ACCOUNT_NAME = "Other Account Tag";

    /** Distinctive enough that the cleanup cannot match a real import. */
    private static final String A_TEST_USER = "removeaccounttagtest@example.invalid";

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
    private ActivityScenario<?> scenario;
    private long importId;

    @Before
    public void seedTheDatabase() {
        final Context context = getInstrumentation().getTargetContext();
        this.db = OpenTagViewerDatabase.getInstance(context);

        this.removeEverythingThisTestWrites();

        // A file-imported tag has an `Import` row behind it, and the device info screen reads it
        // for three of its fields. Seeding one without it made the tag look like an account tag
        // to any code that keys off the absence of an import - which is a mistake this test
        // caught in the first draft of the change it is testing.
        this.importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2")
                .importedAt(1_700_000_000_000L)
                .exportedAt(1_699_000_000_000L)
                .sourceUser(A_TEST_USER)
                .exportedVia("OpenTagViewer.wizard:test")
                .build());

        this.insert(FROM_ACCOUNT, ACCOUNT_NAME, true);
        this.insert(FROM_A_FILE, FILE_NAME, false);
        this.insert(ALSO_FROM_ACCOUNT, OTHER_ACCOUNT_NAME, true);
    }

    @After
    public void putTheDatabaseBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        this.removeEverythingThisTestWrites();
    }

    private void insert(final String id, final String name, final boolean fromAccount) {
        // Null import id for an account tag, exactly as `refreshAccountBeacons` writes it -
        // nothing was exported and nothing was imported, so there is no bundle to point at.
        final Long belongsTo = fromAccount ? null : this.importId;

        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(id)
                .importId(belongsTo)
                .content(A_PLIST)
                .version(fromAccount ? "account" : "0.0.2")
                .fromAccount(fromAccount)
                .isRemoved(false)
                .build());

        this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                .id(id)
                .importId(belongsTo)
                .version(fromAccount ? "account" : "0.0.2")
                .isRemoved(false)
                .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                        + "<key>identifier</key><string>" + id + "</string>"
                        + "<key>name</key><string>" + name + "</string>"
                        + "</dict></plist>")
                .build());
    }

    private void removeEverythingThisTestWrites() {
        // By id, never `clearAllTables`. This is the real database - on a developer's own device
        // that would take their imported tags and every location ever fetched for them.
        for (final String id : new String[] {FROM_ACCOUNT, FROM_A_FILE, ALSO_FROM_ACCOUNT}) {
            this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(id).build());
            this.db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(id).build());
        }

        // Found by this test's own source user rather than by a remembered id, so a run that
        // crashed before @After does not leave one behind for the next.
        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
    }

    private boolean isStillListed(final String id) {
        return this.db.ownedBeaconDao().getById(id) != null;
    }

    private void openTheList() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);
        Eventually.check(() -> onView(withText(ACCOUNT_NAME)).check(matches(isDisplayed())));
    }

    /** Long press a row, then choose Remove from the selection menu. */
    private void chooseRemoveAfterSelecting(final String... names) {
        onView(withText(names[0])).perform(longClick());
        for (int i = 1; i < names.length; i++) {
            onView(withText(names[i])).perform(click());
        }

        Eventually.check(() -> onView(withId(R.id.selection_menu_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.selection_menu_button)).perform(click());

        onView(withText(R.string.remove_devices)).inRoot(isPlatformPopup()).perform(click());
    }

    /**
     * <b>The one that matters.</b> No confirm button, and the tag is still there afterwards.
     */
    @Test
    public void anaccountTagIsExplainedRatherThanRemoved() {
        this.openTheList();
        this.chooseRemoveAfterSelecting(ACCOUNT_NAME);

        Eventually.check(() -> onView(withText(R.string.cannot_remove_account_tag_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withText(R.string.confirm)).inRoot(isDialog()).check(doesNotExist());

        onView(withText(R.string.ok)).inRoot(isDialog()).perform(click());
        Eventually.check(() -> assertNotNull("the account tag must still be there",
                this.db.ownedBeaconDao().getById(FROM_ACCOUNT)));
    }

    /** Two of them says "these", not "this". */
    @Test
    public void twoaccountTagsGetThePluralExplanation() {
        this.openTheList();
        this.chooseRemoveAfterSelecting(ACCOUNT_NAME, OTHER_ACCOUNT_NAME);

        Eventually.check(() -> onView(withText(R.string.cannot_remove_account_tags_message))
                .inRoot(isDialog()).check(matches(isDisplayed())));
    }

    /**
     * The behaviour that already worked still works.
     *
     * <p>Worth its own test: the cheap way to implement this change is to disable the menu item
     * whenever any account tag exists, which would take removal away from everybody.
     */
    @Test
    public void afileImportedTagIsStillRemovable() {
        this.openTheList();
        this.chooseRemoveAfterSelecting(FILE_NAME);

        Eventually.check(() -> onView(withText(R.string.confirm))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click());

        Eventually.check(() -> assertNull("the file-imported tag should have been removed",
                this.db.ownedBeaconDao().getById(FROM_A_FILE)));
    }

    /**
     * A mixed selection removes what it can and says what it did not.
     *
     * <p>The alternative - refusing the whole selection - punishes somebody for picking one tag
     * too many, and the alternative to <i>that</i> is removing the file ones silently, which is
     * how a user ends up staring at a tag they thought they deleted.
     */
    @Test
    public void amixedSelectionRemovesOnlyWhatTheAppOwns() {
        this.openTheList();
        this.chooseRemoveAfterSelecting(FILE_NAME, ACCOUNT_NAME);

        Eventually.check(() -> onView(withText(
                getInstrumentation().getTargetContext()
                        .getString(R.string.remove_devices_but_keep_account_ones, 1)))
                .inRoot(isDialog()).check(matches(isDisplayed())));

        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click());

        Eventually.check(() -> assertNull("the file-imported tag should have gone",
                this.db.ownedBeaconDao().getById(FROM_A_FILE)));
        assertNotNull("the account tag should have stayed",
                this.db.ownedBeaconDao().getById(FROM_ACCOUNT));
    }

    /** The same answer from the other screen that offers removal. */
    @Test
    public void thedeviceInfoScreenSaysTheSameThing() {
        final Intent intent = new Intent(
                getInstrumentation().getTargetContext(), DeviceInfoActivity.class);
        intent.putExtra("beaconId", FROM_ACCOUNT);
        this.scenario = ActivityScenario.launch(intent);

        Eventually.check(() -> onView(withId(R.id.page_menu_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.page_menu_button)).perform(click());
        onView(withText(R.string.remove_device)).inRoot(isPlatformPopup()).perform(click());

        Eventually.check(() -> onView(withText(R.string.cannot_remove_account_tag_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withText(R.string.confirm)).inRoot(isDialog()).check(doesNotExist());

        onView(withText(R.string.ok)).inRoot(isDialog()).perform(click());
        Eventually.check(() -> assertNotNull(this.db.ownedBeaconDao().getById(FROM_ACCOUNT)));
    }

    /**
     * The screen says where the tag came from instead of describing a bundle that never existed.
     *
     * <p>"Exported by", "Exported at" and "Imported at" all read from an {@code Import} row, and
     * an account tag has none - which is what used to crash this screen outright. Leaving the
     * three rows in place with blank subtitles would have replaced a crash with three fields that
     * look like data the app failed to load, so exactly one of the two sets is shown.
     */
    @Test
    public void anaccountTagSaysWhereItCameFromInsteadOfHowItWasExported() {
        final Intent intent = new Intent(
                getInstrumentation().getTargetContext(), DeviceInfoActivity.class);
        intent.putExtra("beaconId", FROM_ACCOUNT);
        this.scenario = ActivityScenario.launch(intent);

        Eventually.check(() -> onView(withText(R.string.source_your_apple_account))
                .check(matches(isDisplayed())));

        onView(withId(R.id.device_settings_exported_by)).check(matches(not(isDisplayed())));
        onView(withId(R.id.device_settings_exported_at)).check(matches(not(isDisplayed())));
        onView(withId(R.id.device_settings_imported_at)).check(matches(not(isDisplayed())));
    }

    /** And a tag that did come from a bundle still describes the bundle. */
    @Test
    public void afileImportedTagStillShowsItsExportDetails() {
        final Intent intent = new Intent(
                getInstrumentation().getTargetContext(), DeviceInfoActivity.class);
        intent.putExtra("beaconId", FROM_A_FILE);
        this.scenario = ActivityScenario.launch(intent);

        Eventually.check(() -> onView(withId(R.id.device_settings_exported_by))
                .check(matches(isDisplayed())));

        onView(withId(R.id.device_settings_imported_at)).check(matches(isDisplayed()));
        onView(withId(R.id.device_settings_source)).check(matches(not(isDisplayed())));
    }

    /** And it still offers it for a tag that is the app's own. */
    @Test
    public void thedeviceInfoScreenStillOffersRemovalForAFileImportedTag() {
        final Intent intent = new Intent(
                getInstrumentation().getTargetContext(), DeviceInfoActivity.class);
        intent.putExtra("beaconId", FROM_A_FILE);
        this.scenario = ActivityScenario.launch(intent);

        Eventually.check(() -> onView(withId(R.id.page_menu_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.page_menu_button)).perform(click());
        onView(withText(R.string.remove_device)).inRoot(isPlatformPopup()).perform(click());

        Eventually.check(() -> onView(withText(R.string.confirm))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        assertTrue("nothing should have been removed by opening the dialog",
                this.isStillListed(FROM_A_FILE));
    }
}
