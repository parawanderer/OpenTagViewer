package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.TestPace;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.HardwareDescriber;
import dev.wander.android.opentagviewer.python.icloud.FakeICloudService;
import dev.wander.android.opentagviewer.python.icloud.ICloudFailure;
import dev.wander.android.opentagviewer.python.icloud.KeychainMembership;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Renaming a tag, which does two completely different things depending on the tag.
 *
 * <p><b>The rule, and it is genuinely odd:</b>
 *
 * <ul>
 *   <li><b>An accessory read from iCloud</b> - an AirTag, or a Find My-certified tag - keeps its
 *       name and emoji in the naming record and nowhere else. So renaming it writes to the
 *       account, and the owner sees the new name in Find My on their own devices.</li>
 *   <li><b>One of the owner's own devices</b> - an iPhone, iPad or Mac - takes its name from
 *       several places at once. Writing this record would leave Find My disagreeing with the
 *       device itself, so the app keeps a local nickname and goes on showing the real name.</li>
 *   <li><b>Anything imported from a file</b> was never on this account. Nickname, as before.</li>
 * </ul>
 *
 * <p><b>Which is why these tests exist rather than one happy-path check.</b> Both behaviours look
 * identical on screen - a tag with a new name on it - and the difference is whether a write left
 * the device. The failure mode of getting it wrong is not a crash: it is either a rename that
 * quietly does not reach the account, or a write to somebody's Apple account they did not ask
 * for. Neither shows up by looking.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class RenamingWritesToTheAccountTest {

    private static final String AN_ACCESSORY = "test-rename-accessory";
    private static final String A_DEVICE = "test-rename-device";
    private static final String FROM_A_FILE = "test-rename-file-tag";

    private static final String THE_OLD_NAME = "Old Tag Name";
    private static final String THE_NEW_NAME = "Renamed In iCloud";
    private static final String A_TEST_USER = "renametest@example.invalid";

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

    /** Answers the one question that decides which behaviour the screen offers. */
    private static final class Describer implements HardwareDescriber {
        private final Boolean ownDevice;

        Describer(final Boolean ownDevice) {
            this.ownDevice = ownDevice;
        }

        @Override
        public String describe(final String plistXml) {
            return "AirTag";
        }

        @Override
        public String whereToLookUp(final String plistXml) {
            return null;
        }

        @Override
        public Boolean isOwnDevice(final String plistXml) {
            return this.ownDevice;
        }
    }

    private OpenTagViewerDatabase db;
    private KeychainMembershipRepository memberships;
    private FakeICloudService icloud;
    private ActivityScenario<DeviceInfoActivity> scenario;
    private long importId;

    @Before
    public void seedThreeKindsOfTag() {
        final Context context = getInstrumentation().getTargetContext();
        this.db = OpenTagViewerDatabase.getInstance(context);
        this.memberships = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(context), new AppCryptographyUtil());

        this.forgetEverything();

        this.importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2")
                .importedAt(1_700_000_000_000L)
                .exportedAt(1_699_000_000_000L)
                .sourceUser(A_TEST_USER)
                .exportedVia("OpenTagViewer.wizard:test")
                .build());

        this.insert(AN_ACCESSORY, true);
        this.insert(A_DEVICE, true);
        this.insert(FROM_A_FILE, false);

        // Renaming through the account resumes as the member this app already is. Without one
        // stored there is nothing to resume with, and every write would fail for that reason
        // rather than the one under test.
        this.memberships.store(new KeychainMembership(
                "{\"peer_id\":\"peer-ours\"}", "ZW50cm9weQ==", "a-generated-passcode",
                "a-label", 2)).blockingAwait();
    }

    @After
    public void putEverythingBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        AppDependencies.reset();
        this.forgetEverything();
    }

    private void insert(final String id, final boolean fromAccount) {
        final Long belongsTo = fromAccount ? null : this.importId;

        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(id).importId(belongsTo).content(A_PLIST)
                .version(fromAccount ? "account" : "0.0.2")
                .fromAccount(fromAccount).isRemoved(false).build());

        this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                .id(id).importId(belongsTo)
                .version(fromAccount ? "account" : "0.0.2").isRemoved(false)
                .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                        + "<key>identifier</key><string>" + id + "</string>"
                        + "<key>name</key><string>" + THE_OLD_NAME + "</string>"
                        + "</dict></plist>")
                .build());
    }

    private void forgetEverything() {
        for (final String id : new String[] {AN_ACCESSORY, A_DEVICE, FROM_A_FILE}) {
            this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(id).build());
            this.db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(id).build());
            this.db.userBeaconOptionsDao().deleteById(id);
        }
        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
        this.memberships.forget().blockingAwait();
    }

    private void open(final String beaconId, final Boolean ownDevice) {
        this.icloud = FakeICloudService.withTags();
        AppDependencies.replaceICloud(() -> this.icloud);
        AppDependencies.replaceHardwareDescriber(new Describer(ownDevice));

        final Intent intent = new Intent(
                getInstrumentation().getTargetContext(), DeviceInfoActivity.class);
        intent.putExtra("beaconId", beaconId);
        this.scenario = ActivityScenario.launch(intent);

        // By id, not by text: the name is on screen in three places at once - the toolbar
        // title, the row, and the debug section - and matching the text is ambiguous.
        Eventually.check(() -> onView(withId(R.id.device_settings_name))
                .check(matches(isDisplayed())));
        TestPace.afterAStep();
    }

    /** Tap the name row, type a new one, confirm. */
    private void renameTo(final String newName) {
        onView(withId(R.id.device_settings_name)).perform(click());
        TestPace.afterAStep();

        Eventually.check(() -> onView(withId(R.id.device_name_input))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withId(R.id.device_name_input)).inRoot(isDialog()).perform(replaceText(newName));
        TestPace.afterAStep();

        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click());
        TestPace.afterAStep();
    }

    private String storedNameFor(final String beaconId) {
        return this.db.beaconNamingRecordDao().getByBeaconId(beaconId).content;
    }

    /**
     * <b>An accessory's rename goes to Apple.</b>
     */
    @Test
    public void anaccessoryFromICloudIsRenamedInTheAccount() {
        this.open(AN_ACCESSORY, false);

        this.renameTo(THE_NEW_NAME);

        Eventually.check(() -> assertEquals(1, this.icloud.timesCalled("rename")));
        assertArrayEquals("the wrong thing was sent to be renamed",
                new String[] {AN_ACCESSORY, THE_NEW_NAME, ""}, this.icloud.renamedWith());
    }

    /**
     * And the record it was judged from is this tag's, not an empty string.
     *
     * <p>Python decides accessory-or-device from that plist. Sending the wrong one - or none -
     * would have it answering about a different tag, and the rename would still look fine.
     */
    @Test
    public void therecordSentIsTheOneTheTagWasImportedWith() {
        this.open(AN_ACCESSORY, false);

        this.renameTo(THE_NEW_NAME);

        Eventually.check(() -> assertNotNull(this.icloud.renamedPlist()));
        assertTrue("the accessory's own record must be what the decision is made from",
                this.icloud.renamedPlist().contains("stableIdentifier"));
    }

    /**
     * <b>The new name becomes the tag's real name, not a nickname over the top of it.</b>
     *
     * <p>A nickname would have been much less code and quietly wrong: it wins at display time
     * forever, so the next rename made on the owner's iPhone would arrive and be hidden behind
     * it, and the app would look like it had stopped syncing.
     */
    @Test
    public void thestoredRecordIsRewrittenRatherThanNicknamed() {
        this.open(AN_ACCESSORY, false);

        this.renameTo(THE_NEW_NAME);

        Eventually.check(() -> assertTrue("the naming record still says the old name",
                this.storedNameFor(AN_ACCESSORY).contains(THE_NEW_NAME)));
        assertNull("a nickname must not be left over the real name",
                this.db.userBeaconOptionsDao().getById(AN_ACCESSORY));
    }

    /**
     * <b>One of the owner's own devices is nicknamed, and nothing is written.</b>
     */
    @Test
    public void anownDeviceIsNicknamedAndTheAccountIsNotTouched() {
        this.open(A_DEVICE, true);

        this.renameTo("My Own iPad");

        Eventually.check(() -> assertNotNull("the nickname was not saved",
                this.db.userBeaconOptionsDao().getById(A_DEVICE)));
        assertEquals("a device rename must never reach the account",
                0, this.icloud.timesCalled("rename"));
    }

    /** And its real name is still there, under the nickname. */
    @Test
    public void anownDevicesRealNameSurvivesTheNickname() {
        this.open(A_DEVICE, true);

        this.renameTo("My Own iPad");

        Eventually.check(() -> assertNotNull(this.db.userBeaconOptionsDao().getById(A_DEVICE)));
        assertTrue("the real name must not be overwritten by a nickname",
                this.storedNameFor(A_DEVICE).contains(THE_OLD_NAME));
    }

    /** A tag that came from a zip was never on this account, whatever kind of thing it is. */
    @Test
    public void afileImportedTagIsNicknamedEvenThoughItIsAnAccessory() {
        this.open(FROM_A_FILE, false);

        this.renameTo("From My Friend");

        Eventually.check(() -> assertNotNull(this.db.userBeaconOptionsDao().getById(FROM_A_FILE)));
        assertEquals("a file-imported tag is not on this account to rename",
                0, this.icloud.timesCalled("rename"));
    }

    /**
     * <b>Before the heuristic has answered, renaming stays local.</b>
     *
     * <p>Null is not false. The cautious mistake is a nickname, which changes nothing anybody
     * else can see; the other one writes to somebody's Apple account on a guess.
     */
    @Test
    public void anunansweredHeuristicDoesNotWriteToTheAccount() {
        this.open(AN_ACCESSORY, null);

        this.renameTo(THE_NEW_NAME);

        Eventually.check(() -> assertNotNull(this.db.userBeaconOptionsDao().getById(AN_ACCESSORY)));
        assertEquals(0, this.icloud.timesCalled("rename"));
    }

    /**
     * <b>A rename that could not reach Apple says so and changes nothing.</b>
     *
     * <p>Not a silent demotion to a nickname. That would be the app telling the user something
     * about their account that is not true, which is the same class of quiet lie as a remove
     * button that undoes itself.
     */
    @Test
    public void afailedWriteChangesNothingAndSaysSo() {
        this.icloud = FakeICloudService.withTags()
                .whereRenamingFails(ICloudFailure.MEMBERSHIP_UNUSABLE);
        AppDependencies.replaceICloud(() -> this.icloud);
        AppDependencies.replaceHardwareDescriber(new Describer(false));

        final Intent intent = new Intent(
                getInstrumentation().getTargetContext(), DeviceInfoActivity.class);
        intent.putExtra("beaconId", AN_ACCESSORY);
        this.scenario = ActivityScenario.launch(intent);
        Eventually.check(() -> onView(withId(R.id.device_settings_name))
                .check(matches(isDisplayed())));

        this.renameTo(THE_NEW_NAME);

        Eventually.check(() -> onView(withText(R.string.rename_failed_title))
                .inRoot(isDialog()).check(matches(isDisplayed())));
        onView(withText(R.string.ok)).inRoot(isDialog()).perform(click());

        assertTrue("a failed rename must not change the stored name",
                this.storedNameFor(AN_ACCESSORY).contains(THE_OLD_NAME));
        assertNull("a failed rename must not leave a nickname behind either",
                this.db.userBeaconOptionsDao().getById(AN_ACCESSORY));
    }
}
