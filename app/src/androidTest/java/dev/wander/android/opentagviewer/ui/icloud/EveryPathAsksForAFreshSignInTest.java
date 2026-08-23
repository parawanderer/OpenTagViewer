package dev.wander.android.opentagviewer.ui.icloud;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
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

import java.util.List;

import dev.wander.android.opentagviewer.AppleLoginActivity;
import dev.wander.android.opentagviewer.DeviceInfoActivity;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.FetchFromICloudActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.AccountBeaconsForTests;
import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.repo.KeychainMembershipRepository;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.icloud.AccountRefresher;
import dev.wander.android.opentagviewer.python.icloud.FakeICloudService;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Every way this app talks to the account, when Apple refuses the stored password.
 *
 * <p><b>The behaviour has to be the same wherever it surfaces, and it was not.</b> Three paths
 * reach the same wall - opening the iCloud device list, renaming a tag (which writes to the
 * account), and the six-hourly background read - and each interpreted the failure for itself.
 * Opening the list landed on "worth trying again later", which is false for a session that can
 * never work again; the background read logged a warning and carried on.
 *
 * <p>{@code WhichFailuresNeedAFreshSignInTest} proves the decision on the JVM. This proves the
 * callers act on it, which is the half a shared predicate does not guarantee: nothing stops a
 * fourth screen being written that never asks.
 *
 * <p>None of it is reachable on a real account on demand - it needs Apple to refuse a password
 * that was working - so {@code FakeICloudService.whereAppleRefusesTheCredentials} sets the
 * refusal on every entry point at once, deliberately: a fake that failed only one call would let
 * a caller mishandling the others pass.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class EveryPathAsksForAFreshSignInTest {

    private static final String A_TAG = "refused-credentials-tag";
    private static final String A_NAME = "Refused";

    private static final String A_PLIST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<plist version=\"1.0\"><dict>"
            + "<key>batteryLevel</key><integer>1</integer>"
            + "<key>identifier</key><string>" + A_TAG + "</string>"
            + "<key>model</key><string></string>"
            + "<key>pairingDate</key><date>2025-02-27T20:03:32Z</date>"
            + "<key>privateKey</key><dict><key>key</key><dict>"
            + "<key>data</key><data>bm90LWEtcmVhbC1rZXk=</data></dict></dict>"
            + "<key>productId</key><integer>21760</integer>"
            + "<key>stableIdentifier</key><array><string>2001~#0~#A0</string></array>"
            + "<key>systemVersion</key><string>2.0.73</string>"
            + "<key>vendorId</key><integer>76</integer>"
            + "</dict></plist>";

    private static final String A_NAMING_RECORD =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                    + "<key>identifier</key><string>" + A_TAG + "</string>"
                    + "<key>associatedBeacon</key><string>" + A_TAG + "</string>"
                    + "<key>name</key><string>" + A_NAME + "</string>"
                    + "</dict></plist>";

    private ActivityScenario<?> scenario;
    private OpenTagViewerDatabase db;

    @Before
    public void refuseEverything() {
        final Context context = getInstrumentation().getTargetContext();
        this.db = OpenTagViewerDatabase.getInstance(context);

        AccountBeaconsForTests.forgetThemAll();
        this.forgetTheTag();
        this.forgetTheMembership();

        AppDependencies.replaceICloud(FakeICloudService::whereAppleRefusesTheCredentials);

        Intents.init();
        // The login screen is the destination under test, so it is stubbed rather than launched:
        // starting it for real would sit on a sign-in form waiting for an Apple password.
        intending(hasComponent(AppleLoginActivity.class.getName()))
                .respondWith(new ActivityResult(Activity.RESULT_CANCELED, null));
    }

    @After
    public void putItAllBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        Intents.release();
        AppDependencies.reset();
        AccountBeaconsForTests.forgetThemAll();
        this.forgetTheTag();
        this.forgetTheMembership();
    }

    private void forgetTheTag() {
        this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(A_TAG).build());
        this.db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(A_TAG).build());
    }

    /**
     * A stored membership, without which a rename never reaches Apple at all.
     *
     * <p>{@code AccessoryRenamer} refuses with {@code MEMBERSHIP_UNUSABLE} before opening
     * anything when this app is not a keychain member - correctly, since it could not write - so
     * a test without one asserts nothing about credentials being refused. The first version of
     * this had no membership and failed for that reason, which reads exactly like the fix not
     * working.
     */
    private void givenTheAccountIsLinked() {
        new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil())
                .store(new dev.wander.android.opentagviewer.python.icloud.KeychainMembership(
                        "{\"peer_id\":\"peer-ours\"}", "ZW50cm9weQ==", "a-passcode",
                        "a-label", 2))
                .blockingAwait();
    }

    private void forgetTheMembership() {
        new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(getInstrumentation().getTargetContext()),
                new AppCryptographyUtil())
                .forget().blockingAwait();
    }

    private void seedAnAccountTag() {
        // fromAccount, because renaming one of those is what writes to iCloud. A file-imported
        // tag renames locally and never touches the account, so it could not reach this failure.
        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(A_TAG).content(A_PLIST).version("account")
                .fromAccount(true).isRemoved(false).build());
        this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                .id(A_TAG).content(A_NAMING_RECORD).version("account").isRemoved(false).build());
    }

    /**
     * An accessory rather than one of the owner's own devices, said definitely.
     *
     * <p><b>Null is not False here, and that is the whole reason this exists.</b> The account
     * rename is gated on {@code isFromAccount() && Boolean.FALSE.equals(isOwnDevice)} - so a
     * describer that cannot decide leaves the rename local, iCloud is never called, and this
     * test passes for the wrong reason or fails for one. The real describer runs Python against
     * a fixture plist and is under no obligation to be sure.
     */
    private static void treatTheTagAsAnAccessory() {
        AppDependencies.replaceHardwareDescriber(
                new dev.wander.android.opentagviewer.python.HardwareDescriber() {
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
                        return Boolean.FALSE;
                    }
                });
    }

    /** Whatever the path, this is what has to happen. */
    private void assertItAsksForAFreshSignIn() {
        Eventually.check(() -> intended(allOf(
                hasComponent(AppleLoginActivity.class.getName()),
                hasExtra(AppleLoginActivity.EXTRA_SESSION_EXPIRED, true))));
    }

    /**
     * <b>Opening the iCloud device list. The path that reported this.</b>
     *
     * <p>It used to land on the retry screen, which says the service is having a bad day. It is
     * not: the same password is re-sent on every attempt and refused every time.
     */
    @Test
    public void aopeningTheICloudListAsksForAFreshSignIn() {
        this.scenario = ActivityScenario.launch(FetchFromICloudActivity.class);

        this.assertItAsksForAFreshSignIn();
    }

    /**
     * <b>And the session it could not use is cleared, not kept.</b>
     *
     * <p>Keeping one Apple refuses means the next screen to try meets the same wall with no idea
     * why - and the sign-in form would then be prefilled from a session that is about to be
     * replaced anyway.
     */
    @Test
    public void btherefusedSessionIsForgotten() {
        this.scenario = ActivityScenario.launch(FetchFromICloudActivity.class);
        this.assertItAsksForAFreshSignIn();

        final Context context = getInstrumentation().getTargetContext();
        Eventually.check(() -> assertTrue("the refused session was kept",
                new dev.wander.android.opentagviewer.db.repo.UserAuthRepository(
                        UserAuthDataStore.getInstance(context), new AppCryptographyUtil())
                        .getUserAuth().blockingFirst().isEmpty()));
    }

    /**
     * <b>Renaming a tag, which writes to the account.</b>
     *
     * <p>Without this it says the rename failed - so somebody tries a shorter name, or a
     * different one, for a session that cannot write anything at all.
     */
    @Test
    public void crenamingATagAsksForAFreshSignIn() {
        this.seedAnAccountTag();
        this.givenTheAccountIsLinked();
        treatTheTagAsAnAccessory();

        final Intent intent = new Intent(
                getInstrumentation().getTargetContext(), DeviceInfoActivity.class);
        intent.putExtra("beaconId", A_TAG);
        this.scenario = ActivityScenario.launch(intent);

        // Driven the same way RenamingWritesToTheAccountTest drives it, so this exercises the
        // path a person takes rather than a method call that happens to be reachable.
        Eventually.check(() -> onView(withId(R.id.device_settings_name))
                .check(matches(isDisplayed())));
        onView(withId(R.id.device_settings_name)).perform(click());

        Eventually.check(() -> onView(withId(R.id.device_name_input)).inRoot(isDialog())
                .check(matches(isDisplayed())));
        onView(withId(R.id.device_name_input)).inRoot(isDialog())
                .perform(replaceText("Something Else"));
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click());

        this.assertItAsksForAFreshSignIn();
    }

    /**
     * <b>And the background read, which has no screen of its own.</b>
     *
     * <p>It must <i>not</i> act: clearing somebody's session out from under whatever they are
     * doing, from a job nobody asked for, is worse than waiting for the next thing that needs the
     * account. What it must do is leave the stored tags alone rather than treating a refusal as
     * a membership that has gone bad - which would cost a device passcode to rejoin, for a
     * problem rejoining cannot fix.
     */
    @Test
    public void dthebackgroundReadLeavesEverythingAlone() {
        final Context context = getInstrumentation().getTargetContext();
        final KeychainMembershipRepository memberships = new KeychainMembershipRepository(
                UserAuthDataStore.getInstance(context), new AppCryptographyUtil());

        this.seedAnAccountTag();

        final List<String> held = new AccountRefresher(
                memberships,
                new dev.wander.android.opentagviewer.db.repo.BeaconRepository(this.db))
                .refresh()
                .onErrorReturnItem(List.of())
                .blockingFirst();

        assertEquals("a refusal is not a list of tags", List.of(), held);

        // The tag it could not re-read is still there, and still the user's.
        assertTrue("the stored tag was dropped over a failed read",
                this.db.ownedBeaconDao().getAll().stream()
                        .anyMatch(beacon -> A_TAG.equals(beacon.id)));
    }
}
