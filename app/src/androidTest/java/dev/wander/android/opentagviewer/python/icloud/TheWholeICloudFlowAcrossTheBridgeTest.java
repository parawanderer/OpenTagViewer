package dev.wander.android.opentagviewer.python.icloud;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import dev.wander.android.opentagviewer.python.PythonAppleAccount;

/**
 * The iCloud flow end to end, with only the network replaced.
 *
 * <p><b>Every other test of this flow replaces the Java side of the bridge, so the bridge never
 * runs.</b> {@code FakeICloudService} is what the screen tests drive - correct for testing
 * screens, and it means {@code PythonICloudService} itself, the JSON it builds, the objects it
 * converts and the reason strings it maps had never executed outside somebody's hands. Two bugs
 * shipped straight through that gap and were found by @parawanderer using the app:
 *
 * <ul>
 *   <li>{@code openFor} checked its result with {@code made.toJava(Object.class)}, which throws
 *       for any Python object. The whole flow was dead on every device while the suite stayed
 *       green, and the screen blamed a missing account - a cause it had invented.</li>
 *   <li>{@code getLastReports} never emitted {@code wideSearch} or {@code exhaustedWideSearch}.
 *       Java read both, a missing key reads as false, and the silent-tag backoff quietly did
 *       nothing at all.</li>
 * </ul>
 *
 * <p>Both lived in the seam between the two languages, which is the one place a fake on either
 * side cannot see. So this fakes neither: {@code icloud_test_double} replaces the two functions
 * in {@code exporter.icloud} that talk to Apple, and everything above them - the session, the
 * JSON, the plist rendering, the failure mapping - is the shipping code.
 *
 * <p>The double lives in the debug source set, so it is in the APK the instrumented tests run
 * against and in no release build.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class TheWholeICloudFlowAcrossTheBridgeTest {

    private static final String DOUBLE = "icloud_test_double";

    /** The serial the double escrows against, and the passcode it accepts for it. */
    private static final String A_SERIAL = "F2LX9Q";
    private static final String THE_RIGHT_PASSCODE = "123456";

    private PyObject theDouble;
    private PythonICloudService service;

    @BeforeClass
    public static void startPython() {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(getInstrumentation().getTargetContext()));
        }
    }

    @Before
    public void replaceOnlyTheNetwork() {
        this.theDouble = Python.getInstance().getModule(DOUBLE);
        this.theDouble.callAttr("install");

        // The account object comes from the double because opening a session guards on the two
        // private attributes FindMy.py's account carries. Everything after this is production.
        this.service = PythonICloudService.openFor(
                new PythonAppleAccount(this.theDouble.callAttr("anAccount")));
    }

    @After
    public void putTheRealOnesBack() {
        if (this.service != null) {
            this.service.close();
        }
        if (this.theDouble != null) {
            this.theDouble.callAttr("uninstall");
        }
    }

    /**
     * Open, then list - because unlocking needs the listing to have happened.
     *
     * <p><b>Not incidental setup.</b> {@code unlock} takes a serial and resolves it against the
     * escrow records the session cached while listing, so calling it without listing first fails
     * with {@code NO_SUCH_RECORD}. That is the real order too - a user sees their devices, picks
     * one, and types its passcode - and it is worth a named method rather than a stray line,
     * because the failure when it is missing reads like the fake being wrong rather than the
     * caller.
     */
    private void openAndListTheRecoverableDevices() {
        this.service.open().blockingAwait();
        this.service.recoveryOptions().blockingFirst();
    }

    /** What the fake was asked to do, as one flattened string per key. */
    private String reached(final String key) {
        return String.valueOf(this.theDouble.callAttr("whatReachedTheAccount")
                .asMap()
                .get(PyObject.fromJava(key)));
    }

    /**
     * <b>A session opens at all.</b>
     *
     * <p>The regression for the {@code toJava(Object.class)} bug, driven the way the app drives
     * it rather than by asserting on Chaquopy's conversion rules. {@code openFor} returning
     * something usable here is what was false on every device.
     */
    @Test
    public void asessionOpensAndTheServiceIsUsable() {
        assertNotNull("openFor refused a session the bridge did create", this.service);

        this.service.open().blockingAwait();
    }

    /** The recoverable devices come back as Java objects with their fields intact. */
    @Test
    public void therecoverableDevicesCrossTheBridge() {
        this.service.open().blockingAwait();

        final List<RecoverableDevice> devices = this.service.recoveryOptions().blockingFirst();

        assertEquals(2, devices.size());
        assertEquals(A_SERIAL, devices.get(0).getSerial());
        assertTrue("the description did not survive the bridge: " + devices.get(0).getDescription(),
                devices.get(0).getDescription().contains("iPhone"));
    }

    /**
     * Unlocking passes the passcode through, and joining comes back with a usable membership.
     *
     * <p>The membership is what the app stores and cannot regenerate, so "it came back populated"
     * is what matters rather than any particular value in it.
     */
    @Test
    public void unlockingAndJoiningProduceAmembership() {
        this.openAndListTheRecoverableDevices();
        this.service.unlock(A_SERIAL, THE_RIGHT_PASSCODE).blockingAwait();

        assertTrue("the passcode did not reach the keychain: " + this.reached("unlockedWith"),
                this.reached("unlockedWith").contains(A_SERIAL + ":" + THE_RIGHT_PASSCODE));

        final KeychainMembership held = this.service.join("an-escrow-passcode").blockingFirst();

        assertNotNull(held);
        assertTrue("the peer JSON is empty, so nothing could be resumed later",
                held.getPeerJson().contains("peer-ours"));
        assertEquals("an-escrow-passcode", held.getEscrowPasscode());
        assertTrue("the entropy did not survive", held.getEntropy().length() > 0);
    }

    /**
     * <b>The join is attributed to the app's one device identity.</b>
     *
     * <p>Rule 11: the serial is what distinguishes peers in the trust circle, and the only field
     * of this the user actually sees - in a list next to a <i>Remove from Account</i> button. A
     * path that composed its own would register a second device.
     */
    @Test
    public void thejoinCarriesTheAppsOwnSerial() {
        this.openAndListTheRecoverableDevices();
        this.service.unlock(A_SERIAL, THE_RIGHT_PASSCODE).blockingAwait();
        this.service.join("an-escrow-passcode").blockingFirst();

        assertEquals("the peer was registered under something other than the app's serial",
                "0PENTAGVIEWR", this.reached("joinedSerial"));
    }

    /**
     * <b>A wrong passcode arrives as a rejected passcode, not as a crash.</b>
     *
     * <p>Mapping FindMy.py's {@code RecoveryError} to something a screen can phrase is pure
     * bridge code, on the path a user reaches by mistyping - which is to say the common one.
     */
    @Test
    public void arejectedPasscodeIsReportedAsRejected() {
        this.service.open().blockingAwait();

        this.service.recoveryOptions().blockingFirst();

        Throwable failure = null;
        try {
            this.service.unlock(A_SERIAL, "000000").blockingAwait();
        } catch (final Throwable thrown) {
            failure = thrown;
        }

        assertNotNull("a wrong passcode was accepted", failure);

        final Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        final String described = String.valueOf(cause.getMessage());

        assertFalse("the failure reached Java as a raw traceback rather than a reason: "
                + described, described.contains("Traceback"));
    }

    /**
     * <b>Fetching lists what the account holds, and says what it skipped.</b>
     *
     * <p>The picker is built from this, so a dropped {@code skipped} entry means somebody's iPad
     * silently vanishes with no explanation of why.
     */
    @Test
    public void fetchingListsTheAccessoriesAndKeepsWhatWasSkipped() {
        this.service.open().blockingAwait();

        final ICloudFetch fetched = this.service.fetch().blockingFirst();

        assertEquals(2, fetched.getAccessories().size());

        final ICloudAccessory bike = fetched.getAccessories().get(0);
        assertEquals("a-bike-tag", bike.getBeaconId());
        assertEquals("Bike", bike.getName());
        assertEquals("🚲", bike.getEmoji());
        assertTrue("a tag with an alignment record was reported as lacking one",
                bike.isHasAlignment());

        // A tag CloudKit holds no naming record for is a real case, not an error.
        final ICloudAccessory nameless = fetched.getAccessories().get(1);
        assertEquals("a-nameless-tag", nameless.getBeaconId());
        assertFalse("a tag nobody named was reported as named", nameless.isHasName());

        assertEquals("the skipped device and its reason were dropped",
                1, fetched.getSkipped().size());
    }

    /**
     * <b>And the records it hands over are real plists.</b>
     *
     * <p>This is the whole point of faking below {@code exporter.icloud} rather than above it.
     * The candidates the double returns are the shipping dataclasses, so the XML Java parses here
     * is produced by the same renderer a real account's would go through - which is the document
     * the two sides actually have to agree about.
     */
    @Test
    public void therecordsAreRenderedByTheShippingRenderer() {
        this.service.open().blockingAwait();
        this.service.fetch().blockingFirst();

        final List<AccessoryRecords> records =
                this.service.records(List.of("a-bike-tag")).blockingFirst();

        assertEquals(1, records.size());
        assertTrue("the owned beacon plist is not a plist: "
                        + records.get(0).getOwnedBeaconPlist(),
                records.get(0).getOwnedBeaconPlist().contains("<plist"));
        assertNotNull("the key alignment record was dropped, which costs the first fetch a "
                + "full-history key search", records.get(0).getKeyAlignmentPlist());
    }

    /**
     * Renaming reaches the account with both fields.
     *
     * <p>The one operation here that writes to somebody's real Apple account, so what actually
     * arrives matters more than what comes back.
     */
    @Test
    public void renamingSendsTheNameAndEmojiToTheAccount() {
        this.service.open().blockingAwait();
        this.service.fetch().blockingFirst();

        final AccessoryRecords bike =
                this.service.records(List.of("a-bike-tag")).blockingFirst().get(0);

        this.service.rename("a-bike-tag", bike.getOwnedBeaconPlist(),
                "Cargo bike", "🛻").blockingAwait();

        final String sent = this.reached("renamedWith");
        assertTrue("the new name never reached the account: " + sent, sent.contains("Cargo bike"));
        assertTrue("the new emoji never reached the account: " + sent, sent.contains("🛻"));
    }

    /** Closing the service closes the client underneath it rather than leaking the session. */
    @Test
    public void closingTheServiceClosesTheClient() {
        this.service.open().blockingAwait();
        this.service.fetch().blockingFirst();

        this.service.close();
        this.service = null;

        assertEquals("the Find My client was left open", "True", this.reached("closed"));
    }
}
