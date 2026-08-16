package dev.wander.android.opentagviewer.python;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.wander.android.opentagviewer.anisette.AdiDeviceIdentity.Hardware;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

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
