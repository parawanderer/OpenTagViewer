package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation.ActivityResult;
import android.content.Intent;

import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.BundleBuilder;
import dev.wander.android.opentagviewer.ui.error.ErrorReportActivity;
import dev.wander.android.opentagviewer.util.export.TagExporter;

/**
 * What an export does when it cannot be done.
 *
 * <p><b>The failure path is the one worth driving, and it is the one that cannot happen on
 * demand.</b> A successful export is exercised by hand constantly; a Python interpreter that will
 * not start is not, and neither is a record the shared format refuses. Both leave somebody holding
 * no file and no explanation, having just decided to share the keys to their tags with another
 * person - so what they are told at that moment is the whole of what this feature does for them.
 *
 * <p>Three failures, three answers, and the difference is the point. A tag that cannot go in a
 * bundle is something the user picked and can change. A file that will not write is the disk.
 * Anything else is the app failing at something it should manage, and that one earns the report
 * page - which is worth nothing at all if it also turns up for the first two.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ExportingTagsThatGoesWrongTest {

    private static final String A_PLIST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                    + "<key>identifier</key><string>a-tag</string></dict></plist>";

    @Before
    public void catchWhatLeavesTheApp() {
        Intents.init();
        intending(anyOf(
                hasComponent(ErrorReportActivity.class.getName()),
                hasComponent(dev.wander.android.opentagviewer.MapsActivity.class.getName())))
                .respondWith(new ActivityResult(Activity.RESULT_CANCELED, null));
    }

    @After
    public void putTheRealOnesBack() {
        Intents.release();
        AppDependencies.reset();
    }

    private static List<TagExporter.Pairing> onePairing(final BeaconNamingRecord naming) {
        final OwnedBeacon beacon = OwnedBeacon.builder()
                .id("a-tag")
                .content(A_PLIST)
                .version("0.0.2")
                .build();

        final List<TagExporter.Pairing> selection = new ArrayList<>();
        selection.add(new TagExporter.Pairing(beacon, naming, "The cat"));
        return selection;
    }

    private static BeaconNamingRecord aNamingRecord() {
        return BeaconNamingRecord.builder().id("a-tag").content(A_PLIST).version("0.0.2").build();
    }

    /**
     * <b>A tag with no naming record is refused by name, before anything is written.</b>
     *
     * <p>The importer inner-joins the two records and drops anything it cannot pair - so a bundle
     * exported without one imports successfully and contains nothing. That is the worst available
     * outcome: the sender is told it worked, and the recipient finds out it did not.
     */
    @Test
    public void atagWithNoNamingRecordIsNamedRatherThanSilentlySkipped() {
        AppDependencies.replaceBundleBuilder(everBuilding());

        final TagExporter.NothingToExportException thrown = assertThrowsNothingToExport(
                () -> TagExporter.writeTo(new ByteArrayOutputStream(), onePairing(null),
                        "OpenTagViewer.android:test", "someone", 1L));

        assertEquals("The cat", thrown.getMessage());
    }

    /** And an empty selection never reaches the interpreter at all. */
    @Test
    public void anemptySelectionIsRefusedWithoutStartingPython() {
        final boolean[] called = {false};
        AppDependencies.replaceBundleBuilder((accessories, via, user, at) -> {
            called[0] = true;
            return new BundleBuilder.Built(new LinkedHashMap<>(), null);
        });

        assertThrowsNothingToExport(() -> TagExporter.writeTo(
                new ByteArrayOutputStream(), new ArrayList<>(),
                "OpenTagViewer.android:test", "someone", 1L));

        assertTrue("an empty selection should not have started anything", !called[0]);
    }

    /**
     * <b>And a builder that cannot run reaches the report page, cause and all.</b>
     *
     * <p>This is what the seam exists for. "Python did not start" is not reachable on a working
     * device, and it is exactly the state where a user has nothing to say in a bug report unless
     * the app says it for them.
     */
    @Test
    public void abuilderThatCannotRunIsReportable() throws Exception {
        AppDependencies.replaceBundleBuilder((accessories, via, user, at) -> {
            throw new BundleBuilder.BundleBuildException("The export could not be built.");
        });

        try {
            TagExporter.writeTo(new ByteArrayOutputStream(), onePairing(aNamingRecord()),
                    "OpenTagViewer.android:test", "someone", 1L);
            fail("a builder that throws should not produce a bundle");
        } catch (final BundleBuilder.BundleBuildException expected) {
            // What the screen would put on the page, verbatim.
            assertEquals("BundleBuildException: The export could not be built.",
                    ErrorReportActivity.describe(expected));
        }

        // And the page it lands on says the export failed rather than something about a zip.
        final Intent intent = ErrorReportActivity.intentFor(
                getInstrumentation().getTargetContext(),
                "BundleBuildException: The export could not be built.",
                dev.wander.android.opentagviewer.R.string.error_report_body_export);

        getInstrumentation().getTargetContext().startActivity(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

        intended(allOf(
                hasComponent(ErrorReportActivity.class.getName()),
                hasExtra(ErrorReportActivity.EXTRA_BODY,
                        dev.wander.android.opentagviewer.R.string.error_report_body_export)));
    }

    /**
     * <b>A destination that will not take it does not lose the code, because there is none yet.</b>
     *
     * <p>Ordering matters here and is easy to get backwards: generating a code, showing it, and
     * then failing to write leaves somebody holding the code to a file that does not exist. The
     * write has to succeed before anything is shown.
     */
    @Test
    public void adestinationThatRefusesTheWriteProducesNoCode() {
        AppDependencies.replaceBundleBuilder(everBuilding());

        final OutputStream refuses = new OutputStream() {
            @Override
            public void write(final int b) throws IOException {
                throw new IOException("No space left on device");
            }
        };

        try {
            TagExporter.writeTo(refuses, onePairing(aNamingRecord()),
                    "OpenTagViewer.android:test", "someone", 1L);
            fail("a destination that throws should not report success");
        } catch (final Exception expected) {
            assertTrue(expected instanceof IOException);
        }
    }

    /** The happy path still works, and the code it hands back is one the importer would accept. */
    @Test
    public void asuccessfulExportHandsBackAUsableCode() throws Exception {
        AppDependencies.replaceBundleBuilder(everBuilding());

        final TagExporter.Exported written = TagExporter.writeTo(
                new ByteArrayOutputStream(), onePairing(aNamingRecord()),
                "OpenTagViewer.android:test", "someone", 1L);

        assertEquals(1, written.getCount());
        assertEquals(12, written.getPasscode().length());
        assertEquals(written.getPasscode(),
                dev.wander.android.opentagviewer.util.parse.BundlePasscode.normalise(
                        dev.wander.android.opentagviewer.util.parse.BundlePasscode.format(
                                written.getPasscode())));
    }

    /** A builder that always produces one entry, so the zip write has something to do. */
    private static BundleBuilder everBuilding() {
        return (accessories, via, user, at) -> {
            final Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("OPENTAGVIEWER.yml", ("via: " + via + "\n").getBytes());
            return new BundleBuilder.Built(entries, null);
        };
    }

    private interface Throwing {
        void run() throws Exception;
    }

    private static TagExporter.NothingToExportException assertThrowsNothingToExport(
            final Throwing what) {
        try {
            what.run();
        } catch (final TagExporter.NothingToExportException expected) {
            return expected;
        } catch (final Exception other) {
            fail("expected NothingToExportException, got " + other);
        }
        fail("expected NothingToExportException, nothing was thrown");
        return null;
    }
}
