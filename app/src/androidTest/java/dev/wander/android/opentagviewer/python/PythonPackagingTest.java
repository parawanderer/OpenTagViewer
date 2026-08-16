package dev.wander.android.opentagviewer.python;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What Python actually ends up inside the APK.
 *
 * <p>The build packages two things and must package exactly two: {@code main.py}, the bridge the
 * app cannot run without, and {@code opentagviewer_export}, the shared bundle-format package that
 * {@code main.py} calls into. The directory the second comes from - {@code python/} - also holds
 * the desktop wizard and its tests, neither of which belongs on a phone.
 *
 * <p><b>Both halves of that went wrong while this was being written, and neither failed a build.</b>
 * Chaquopy's {@code include} filter applies to the whole source set rather than to the {@code
 * srcDir} it follows, so filtering to the shared package dropped {@code main.py} out of the APK -
 * green build, working unit tests, an app that cannot sign in. And a top-level package named
 * {@code test} on the Python path shadows the standard library's own, which is the hazard that
 * left this undone in the first place.
 *
 * <p>So these are imports on a real device, not an inspection of the zip. What matters is whether
 * the interpreter can find these modules, and only the interpreter can answer that.
 */
@RunWith(AndroidJUnit4.class)
public class PythonPackagingTest {

    @BeforeClass
    public static void startPython() {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(
                    getInstrumentation().getTargetContext().getApplicationContext()));
        }
    }

    /**
     * The bridge is still there.
     *
     * <p>The regression guard for the filter above. Everything the app does against Apple goes
     * through this module; without it the app starts, looks fine, and fails at sign-in.
     */
    @Test
    public void theBridgeModuleIsPackaged() {
        final PyObject main = Python.getInstance().getModule("main");

        assertNotNull(main);
        assertNotNull("main.py must still expose the hardware bridge",
                main.get("identifyHardware"));
    }

    /** The point of the exercise: the shared package resolves inside the APK. */
    @Test
    public void theSharedPackageIsPackaged() {
        assertNotNull(Python.getInstance().getModule("opentagviewer_export"));
        assertNotNull(Python.getInstance().getModule("opentagviewer_export.hardware"));
        assertNotNull(Python.getInstance().getModule("opentagviewer_export.passcode"));
    }

    /** An AirTag as the app stores one. Matches the fixture in {@code test_hardware_bridge.py}. */
    private static final String AIRTAG_PLIST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\""
            + " \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
            + "<plist version=\"1.0\">\n"
            + "<dict>\n"
            + "    <key>identifier</key><string>725A989D-D871-49A7-B2FE-948C24F356AB</string>\n"
            + "    <key>model</key><string></string>\n"
            + "    <key>productId</key><integer>21760</integer>\n"
            + "    <key>vendorId</key><integer>76</integer>\n"
            + "    <key>stableIdentifier</key><array>"
            + "<string>2001~#001234a12345aaac~#A02BCDEFG1AB</string></array>\n"
            + "</dict>\n"
            + "</plist>\n";

    /**
     * And it runs, rather than merely importing.
     *
     * <p>Asked through {@code main.py}'s own bridge, because that is how the app reaches it -
     * importing the package directly would pass even if the bridge were wired to nothing.
     *
     * <p><b>This is the test that would catch the shared package going missing at runtime.</b>
     * {@code identifyHardware} swallows every exception and returns null by design - an
     * accessory is not worth losing over a label - so a failed import there is invisible unless
     * something asks for an answer it knows the right shape of. On desktop CPython the same
     * assertion is {@code test_hardware_bridge.py}; the point of repeating it here is that the
     * APK is a different path with a different chance of not containing the package.
     */
    @Test
    public void theHardwareHeuristicAnswersThroughTheBridge() {
        final PyObject answer = Python.getInstance().getModule("main")
                .callAttr("identifyHardware", AIRTAG_PLIST);

        assertNotNull("null here means the shared package did not import inside the APK", answer);
        assertEquals("AirTag", answer.toString());
    }

    /**
     * The standard library's {@code test} module is not shadowed by the exporter's.
     *
     * <p>{@code python/test/} is the desktop wizard's test suite. On the Python path it would sit
     * where the standard library's {@code test} package goes, which is the specific hazard that
     * kept this change unmerged. Asserted by name: those modules must not be importable.
     */
    @Test
    public void theExportersTestsAreNotOnThePath() {
        assertThrows("python/test/ must not be packaged - it shadows the stdlib's test module",
                Exception.class,
                () -> Python.getInstance().getModule("test.test_airtag_decryptor"));
    }

    /**
     * The desktop wizard is not packaged.
     *
     * <p>It imports tkinter, which does not exist here - so a build that shipped it would fail
     * at import time on a phone rather than at build time on a desktop.
     */
    @Test
    public void theDesktopWizardIsNotPackaged() {
        assertThrows("python/exporter/ is the tkinter wizard and must not reach the APK",
                Exception.class,
                () -> Python.getInstance().getModule("exporter.asyncui"));
    }

    /**
     * The shared package's own tests are not shipped either.
     *
     * <p>They came along at first, because the include pattern that picks up the package picks
     * up everything under it. Harmless in the sense that nothing imports them - they would fail
     * if anything did, since pytest is not in the APK - but they are a test suite inside a
     * phone app, and the reason to keep them out is the same reason the wizard is kept out.
     */
    @Test
    public void thePackagesOwnTestsAreNotShipped() {
        assertThrows("opentagviewer_export/tests/ does not belong in the APK",
                Exception.class,
                () -> Python.getInstance().getModule("opentagviewer_export.tests.test_hardware"));
    }
}
