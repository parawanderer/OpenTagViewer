package dev.wander.android.opentagviewer.python.icloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * That the two sides of the bridge mean the same things by the same strings.
 *
 * <p><b>The failure this exists for does not throw.</b> A reason added in Python and not added
 * here does not break anything visibly - {@link ICloudFailure#fromWire} deliberately falls back
 * to {@link ICloudFailure#UNKNOWN} rather than exploding, because a new failure mode must not
 * become a crash on the screen that reports failures. The cost is paid quietly instead: the user
 * gets a generic "something went wrong" where a specific, actionable screen was written for them.
 *
 * <p>The one that would hurt most is {@code service_unsure} degrading to {@code UNKNOWN}, because
 * the specific screen for it is the one that says "try again later" rather than "this account
 * owns no tags" - the difference between a user coming back tomorrow and a user giving up.
 *
 * <p>So this walks the module rather than a list written by hand. A list would have to be
 * updated by the same person who forgot to update the enum.
 */
@RunWith(AndroidJUnit4.class)
public class ICloudFailureWireTest {

    private static final String MODULE = "icloud_bridge";

    private static List<String> reasonConstants() {
        final Python py = Python.getInstance();
        final PyObject module = py.getModule(MODULE);

        final List<String> found = new ArrayList<>();
        for (final PyObject name : py.getBuiltins().callAttr("dir", module).asList()) {
            if (name.toString().startsWith("REASON_")) {
                found.add(name.toString());
            }
        }

        return found;
    }

    @Test
    public void everyReasonPythonCanReportHasAScreenBehindIt() {
        final Python py = Python.getInstance();
        final PyObject module = py.getModule(MODULE);
        final List<String> constants = reasonConstants();

        // Without this the whole test passes by finding nothing, which is the failure mode of
        // every test written against reflection.
        assertTrue("found no REASON_ constants at all - this test is checking nothing",
                constants.size() >= 5);

        for (final String constant : constants) {
            final String wire = module.get(constant).toString();
            final ICloudFailure mapped = ICloudFailure.fromWire(wire);

            if ("REASON_UNKNOWN".equals(constant)) {
                assertEquals("REASON_UNKNOWN is the one that is meant to land there",
                        ICloudFailure.UNKNOWN, mapped);
                continue;
            }

            assertNotEquals(
                    constant + " (\"" + wire + "\") has no case in ICloudFailure.fromWire, so the"
                            + " screen written for it will never be shown",
                    ICloudFailure.UNKNOWN, mapped);
        }
    }

    @Test
    public void thetwoEmptyAnswersStayDistinct() {
        // Asserted on the wire values themselves rather than the enum, because collapsing them
        // is a one-character edit on either side and only this compares the two.
        final PyObject module = Python.getInstance().getModule(MODULE);

        final ICloudFailure nothing =
                ICloudFailure.fromWire(module.get("REASON_NOTHING_TO_RECOVER_FROM").toString());
        final ICloudFailure unsure =
                ICloudFailure.fromWire(module.get("REASON_SERVICE_UNSURE").toString());

        assertEquals(ICloudFailure.NOTHING_TO_RECOVER_FROM, nothing);
        assertEquals(ICloudFailure.SERVICE_UNSURE, unsure);
        assertNotEquals("an account with no Apple device and a service outage are not the same"
                + " thing, and only one of them is worth coming back for", nothing, unsure);
    }

    @Test
    public void anunrecognisedReasonDegradesRatherThanThrowing() {
        assertEquals(ICloudFailure.UNKNOWN, ICloudFailure.fromWire("something_added_later"));
        assertEquals(ICloudFailure.UNKNOWN, ICloudFailure.fromWire(null));
    }
}
