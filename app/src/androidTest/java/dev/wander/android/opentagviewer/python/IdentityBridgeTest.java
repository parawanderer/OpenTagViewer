package dev.wander.android.opentagviewer.python;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.Base64;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.wander.android.opentagviewer.anisette.AdiDeviceIdentity;
import dev.wander.android.opentagviewer.anisette.AdiDeviceIdentity.Hardware;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

/**
 * Java and Python agreeing about which machine this is.
 *
 * <p>Each side is tested on its own elsewhere - {@code AdiDeviceIdentityTest} pins the strings,
 * {@code test_identity.py} pins the parsing - and <b>neither of those can catch the failure
 * that matters</b>, because it lives in the gap between them. The field names crossing the
 * bridge are FindMy.py's, and {@code DeviceIdentity.from_json} back-fills a name it does not
 * recognise from the library's own identity rather than failing. So a rename on either side
 * produces no exception and no log line: it produces a login that claims a MacBookPro13,2 on
 * macOS 13.1 with FindMy.py's CFNetwork, a release that has never existed, sent to Apple as
 * though it had.
 *
 * <p>Only a test with both halves present can see that, which means on a device, through
 * Chaquopy, with the library the app actually ships. That is what this is.
 */
@RunWith(AndroidJUnit4.class)
public class IdentityBridgeTest {

    @BeforeClass
    public static void startPython() {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(
                    getInstrumentation().getTargetContext().getApplicationContext()));
        }
    }

    private static PyObject identity() {
        return Python.getInstance().getModule("identity");
    }

    /** What Python makes of a source claiming the given profile. */
    private static PyObject profileFor(Hardware hardware) {
        return identity().callAttr(
                "hardwareProfile", FakeAnisetteSource.ready().claiming(hardware));
    }

    /**
     * <b>The serial Java shows the user is the serial Python sends Apple.</b>
     *
     * <p>Python owns {@code APP_SERIAL}; {@code AdiDeviceIdentity.APP_SERIAL} is a copy, so that
     * the screen naming the device-list entry does not have to start CPython to draw a label. Two
     * copies of one value is what rule 11 is about, and this is the pin that stops them drifting.
     *
     * <p>The failure it prevents is quiet and nasty: the app registers under one serial and the
     * screen tells the user to look for another, so the row they find looks like somebody else's
     * device - which is the exact belief that gets it removed.
     */
    @Test
    public void theserialOnScreenIsTheSerialOnTheWire() {
        assertEquals("Java shows a serial Python never sends",
                identity().get("APP_SERIAL").toString(),
                AdiDeviceIdentity.APP_SERIAL);
    }

    /**
     * The assertion the whole bridge exists for.
     *
     * <p>Java's six values arrive in Python as the same six values, on a {@code DeviceIdentity}
     * built by the real library. Every field is checked individually rather than in bulk,
     * because back-filling means a wrong one looks exactly like a right one from a distance.
     */
    @Test
    public void everyFieldSurvivesTheCrossing() {
        for (final Hardware hardware : Hardware.values()) {
            final PyObject identity = profileFor(hardware);

            assertNotNull(hardware + " did not survive the crossing at all", identity);
            assertEquals(hardware.model(), identity.get("model").toString());
            assertEquals(hardware.osName(), identity.get("os_name").toString());
            assertEquals(hardware.osVersion(), identity.get("os_version").toString());
            assertEquals(hardware.osBuild(), identity.get("os_build").toString());
            assertEquals(hardware.cfnetwork(), identity.get("cfnetwork").toString());
            assertEquals(hardware.darwin(), identity.get("darwin").toString());
        }
    }

    /**
     * And the client info FindMy.py composes is the one Java provisions ADI under.
     *
     * <p>This is rule 11 stated as an equality. The two strings are built by different code in
     * different languages from what is now one source, and if they ever differ the app is two
     * devices as far as Apple is concerned - the exact thing that made {@code DeviceIdentity}
     * necessary upstream.
     */
    @Test
    public void bothSidesComposeTheSameClientInfo() {
        for (final Hardware hardware : Hardware.values()) {
            final String fromPython = profileFor(hardware).get("platform").toString();

            assertTrue(hardware + ": Java sends " + hardware.clientInfo()
                            + ", Python composes " + fromPython,
                    hardware.clientInfo().startsWith(fromPython));
        }
    }

    /** And the user agent, whose CFNetwork and Darwin have to describe that same release. */
    @Test
    public void bothSidesComposeTheSameUserAgent() {
        for (final Hardware hardware : Hardware.values()) {
            assertEquals(hardware.userAgent(),
                    profileFor(hardware).callAttr("user_agent", "akd/1.0").toString());
        }
    }

    /**
     * A new sign-in gets the app's serial and Java's machine.
     *
     * <p>The end-to-end shape, as {@code loginSync} builds it - so a keyword renamed upstream
     * fails here rather than at somebody's sign-in.
     */
    @Test
    public void anewSignInIsGivenBothHalves() {
        final PyObject kwargs = identity().callAttr(
                "identityForNewSession",
                FakeAnisetteSource.ready().claiming(Hardware.IPHONE));

        assertEquals("0PENTAGVIEWR", kwargs.callAttr("get", "serial").toString());
        assertEquals("iPhone15,2",
                kwargs.callAttr("get", "identity").get("model").toString());
    }

    /**
     * Including when local Anisette is unusable, which is the case that is easy to get wrong.
     *
     * <p>The fallback to a remote server is automatic and invisible. If the machine went with
     * the transport, a user whose libraries failed to download would silently become a second
     * device without having done anything.
     */
    @Test
    public void asignInThatFallsBackToAServerClaimsTheSameMachine() {
        final PyObject local = identity().callAttr(
                "hardwareProfile", FakeAnisetteSource.ready().claiming(Hardware.IPHONE));
        final PyObject fellBack = identity().callAttr(
                "hardwareProfile",
                FakeAnisetteSource.unavailable("no network").claiming(Hardware.IPHONE));

        assertNotNull(fellBack);
        assertEquals(local.toString(), fellBack.toString());
    }

    /**
     * An install that predates profiles presents the Mac, even signing in today.
     *
     * <p>Their ADI is provisioned as a MacBookPro13,2 and is not provisioned again, so this is
     * not a preference - it is the only answer that matches what Apple was already told.
     */
    @Test
    public void alegacyInstallSigningInAgainStillClaimsTheMac() {
        final PyObject kwargs = identity().callAttr(
                "identityForNewSession",
                FakeAnisetteSource.ready().claiming(Hardware.LEGACY_MAC));

        assertEquals("MacBookPro13,2",
                kwargs.callAttr("get", "identity").get("model").toString());
        assertEquals("the serial is a label, and a new sign-in is a new entry either way",
                "0PENTAGVIEWR", kwargs.callAttr("get", "serial").toString());
    }

    /**
     * The two ids cross intact and keep their names.
     *
     * <p>That FindMy.py then accepts them under exactly these keywords is asserted in
     * {@code test_identity.py}, against a real account and the same pinned library. What only a
     * device can show is the crossing itself - a Java string, through Chaquopy, out of the JSON
     * an {@code AnisetteSource} actually produces.
     */
    @Test
    public void theIdsThisInstallAlreadyUsedCrossIntact() {
        final PyObject ids = identity().callAttr(
                "deviceIdsForNewSession", FakeAnisetteSource.ready());

        assertEquals(FakeAnisetteSource.UID, ids.callAttr("get", "uid").toString());
        assertEquals(FakeAnisetteSource.DEVID, ids.callAttr("get", "devid").toString());
    }

    /**
     * The uid crosses as stored, not as the header renders it.
     *
     * <p>FindMy.py base64-encodes it on the way out, and a fresh install's ADI provisioning
     * already sent base64 of the same string. Handing over the encoded form would encode it
     * twice - a value Apple has never seen, from a client claiming to be an installation it
     * has. The alignment is one step away from that mistake in either direction, so both steps
     * are pinned here together.
     */
    @Test
    public void theUidIsNotEncodedTwice() {
        final String crossed = identity()
                .callAttr("deviceIdsForNewSession", FakeAnisetteSource.ready())
                .callAttr("get", "uid").toString();

        assertEquals(FakeAnisetteSource.UID, crossed);
        assertNotEquals("this is the header form; FindMy.py would encode it a second time",
                Hardware.IPHONE.localUserHeader(crossed), crossed);
        assertEquals("and the header form is exactly what FindMy.py will produce from it",
                Base64.encodeToString(crossed.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP),
                Hardware.IPHONE.localUserHeader(crossed));
    }

    /**
     * An install from before profiles existed sends its local user id raw, and cannot not.
     *
     * <p>Its ADI was provisioned under the raw convention and is never provisioned again, and
     * there is no {@code uid} whose base64 is a 64-character hex string. So for these two the
     * device id aligns and this one does not - stated as a test rather than left as a surprise,
     * because it is the one part of the alignment that does not hold for everybody.
     */
    @Test
    public void alegacyInstallCannotAlignItsLocalUserId() {
        final String stored = "3F2A1B0C9D8E7F6A5B4C3D2E1F0A9B8C7D6E5F4A3B2C1D0E9F8A7B6C5D4E3F2A";

        assertEquals(stored, Hardware.LEGACY_MAC.localUserHeader(stored));
        assertNotEquals("if these ever matched, the two conventions would have converged",
                Hardware.LEGACY_MAC.localUserHeader(stored),
                Base64.encodeToString(stored.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    }

    /**
     * Nothing to ask means nothing imposed, rather than a guess.
     *
     * <p>FindMy.py's own identity is wrong for this app, but it is wrong in a way that costs a
     * misleading device-list entry. Guessing a profile could contradict what ADI already sent,
     * which is worse, and refusing to sign in would be worse still.
     */
    @Test
    public void withNoBridgeThereIsNoMachineToClaim() {
        assertNull(identity().callAttr("hardwareProfile", (Object) null));

        final PyObject kwargs = identity().callAttr("identityForNewSession", (Object) null);

        assertEquals("0PENTAGVIEWR", kwargs.callAttr("get", "serial").toString());
        assertFalse("with no machine to claim, none should be asserted",
                kwargs.callAttr("__contains__", "identity").toBoolean());
    }
}
