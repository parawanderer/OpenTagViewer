package dev.wander.android.opentagviewer.python.icloud;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import com.chaquo.python.Kwarg;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.python.PythonAppleAccount;

/**
 * Opening the real iCloud session, across the real Chaquopy bridge.
 *
 * <p><b>This exists because a bug shipped straight through a green suite.</b> The check for "did
 * the bridge give us a session" read {@code made == null || made.toJava(Object.class) == null},
 * which looks like careful null handling and is a guaranteed failure on the path where a session
 * <i>was</i> created: Chaquopy cannot convert an arbitrary Python object to {@code
 * java.lang.Object} and throws {@link ClassCastException}. The real iCloud flow was therefore
 * dead on every device, and the screen reported it as "no signed-in account" - a cause it had
 * invented - so it looked like a button that did nothing.
 *
 * <p><b>Nothing could have caught it, because every other test replaces this class.</b>
 * {@code FakeICloudService} is what all the screen tests drive, which is right for testing
 * screens and means {@code PythonICloudService} itself had never run outside somebody's hands.
 * The lesson is @parawanderer's: everything external here is behind Python, so a fake on the Java
 * side of the bridge skips the bridge.
 *
 * <p>What is pinned here is small and exact - the two facts the bug hinged on - and no Apple
 * account is needed for either.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class OpeningARealICloudSessionTest {

    @BeforeClass
    public static void startPython() {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(getInstrumentation().getTargetContext()));
        }
    }

    /**
     * <b>Python's {@code None} arrives as Java null.</b>
     *
     * <p>Which is why {@code made == null} is the entire check. {@code openSession} refuses an
     * account whose internals it does not recognise by returning {@code None}, and here that is
     * everything Java needs to see.
     */
    @Test
    public void refusingToOpenAsessionComesBackAsNull() {
        final PyObject refused = Python.getInstance()
                .getModule("icloud_bridge")
                .callAttr("openSession", (Object) null);

        assertNull("a Python None must reach Java as null, not as a PyObject wrapping None",
                refused);
    }

    /**
     * <b>And converting a Python object to {@code java.lang.Object} throws.</b>
     *
     * <p>The other half. A session that opened is a Python instance with no Java equivalent, so
     * asking Chaquopy to convert it is not a defensive extra check - it is the failure. Pinned
     * against the bridge module's own class, which is exactly the kind of object
     * {@code openSession} returns on success.
     */
    @Test
    public void aPythonObjectCannotBeConvertedToJavaObject() {
        final PyObject aPythonThing = Python.getInstance()
                .getModule("icloud_bridge")
                .get("ICloudSession");

        assertNotNull("the bridge module should expose its session class", aPythonThing);
        assertThrows("converting a Python object to java.lang.Object has to throw, or this test"
                        + " is not pinning the trap it was written for",
                ClassCastException.class, () -> aPythonThing.toJava(Object.class));
    }

    /**
     * And the service handles a useless account by returning null rather than throwing.
     *
     * <p>The caller treats null as "sign in again", so a throw here would be a crash on a screen
     * whose whole job is reporting that something cannot be done.
     */
    @Test
    public void anaccountTheBridgeCannotUseYieldsNullRatherThanACrash() {
        assertNull(PythonICloudService.openFor(new PythonAppleAccount(null)));
    }

    /**
     * <b>The one that actually catches the bug: a session that opens comes back.</b>
     *
     * <p>The two tests above pin the facts the failure hinged on and would both have passed while
     * it shipped - and so would the one above this, because a null account is refused before the
     * conversion is ever reached. Only the success path throws, so only the success path catches
     * it. Reinstating {@code made.toJava(Object.class)} turns this red and nothing else.
     *
     * <p>The account is assembled here rather than mocked at the Java seam, which is the whole
     * point: {@code openSession} looks for the two private attributes FindMy.py's account carries,
     * and a {@code SimpleNamespace} with those is enough to get a real {@code ICloudSession} back
     * across the real bridge. No Apple account, no network, and no fake standing where the bug was.
     */
    @Test
    public void asessionThatOpensIsHandedBackRatherThanLostInConversion() {
        final Python python = Python.getInstance();

        // What `openSession` guards on: an async account and a loop to drive it. Nothing is
        // called on either here - opening a session only stores them.
        final PyObject account = python.getModule("types").callAttr(
                "SimpleNamespace",
                new Kwarg("_asyncacc", python.getModule("types").callAttr("SimpleNamespace")),
                new Kwarg("_evt_loop", python.getModule("asyncio").callAttr("new_event_loop")));

        assertNotNull("a session that opened must reach Java, not be lost converting it",
                PythonICloudService.openFor(new PythonAppleAccount(account)));
    }
}
