package dev.wander.android.opentagviewer.python;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Java asking the shared heuristic what an accessory is.
 *
 * <p>The heuristic itself is tested in {@code python/opentagviewer_export/tests/} and reachable
 * from the APK per {@code PythonPackagingTest}. What is tested here is the <b>Java side of the
 * bridge</b>: that the wrapper hands over what the function expects, returns the string rather
 * than a {@code PyObject}'s {@code toString} of something else, and - the part that matters most
 * - never throws, because it is called from a screen that must render either way.
 */
@RunWith(AndroidJUnit4.class)
public class ChaquopyHardwareDescriberTest {

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

    private final HardwareDescriber describer = new ChaquopyHardwareDescriber();

    @BeforeClass
    public static void startPython() {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(
                    getInstrumentation().getTargetContext().getApplicationContext()));
        }
    }

    /** The whole point: a real answer, crossing the bridge, from the shared module. */
    @Test
    public void anairTagIsNamedRatherThanNumbered() {
        assertEquals("AirTag", this.describer.describe(AIRTAG_PLIST));
    }

    /**
     * A tag with no plist is a null answer rather than a crash.
     *
     * <p>A self-generated tag has none, and this is the screen's most common non-Apple case.
     *
     * <p><b>Deliberately not named for the short-circuit.</b> The implementation returns before
     * crossing the bridge, which saves starting an interpreter - but nothing here can observe
     * that, and Python would answer None for a null plist anyway. Asserting the contract this
     * test can actually see beats a name implying one it cannot.
     */
    @Test
    public void atagWithoutAPlistIsANullAnswer() {
        assertNull(this.describer.describe(null));
        assertNull(this.describer.describe(""));
        assertNull(this.describer.whereToLookUp(null));
    }

    /**
     * Nonsense is a null answer, not an exception.
     *
     * <p>The screen calls this after it has already drawn a label. Throwing would replace a
     * correct-if-vague answer with a crash, which is a strictly worse trade - so the failure
     * mode has to be "no improvement", and that is worth pinning rather than trusting.
     */
    @Test
    public void garbageIsRefusedQuietly() {
        assertNull(this.describer.describe("not a plist at all"));
        assertNull(this.describer.whereToLookUp("not a plist at all"));
    }

    /**
     * And the second question answers too, so the wrapper is not accidentally one function.
     *
     * <p>Only asserts that the call completes and is consistent with itself: what it says for an
     * AirTag - a name it recognises - is the shared module's business, and pinning the sentence
     * here would be the copy this design exists to avoid.
     */
    @Test
    public void thelookupHintIsReachableToo() {
        // A recognised accessory needs no lookup hint; the contract is that asking is safe.
        this.describer.whereToLookUp(AIRTAG_PLIST);

        assertNotNull("a recognised accessory should still describe",
                this.describer.describe(AIRTAG_PLIST));
    }
}
