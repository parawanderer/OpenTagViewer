package dev.wander.android.opentagviewer.util.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import dev.wander.android.opentagviewer.db.repo.model.ImportData;
import dev.wander.android.opentagviewer.util.parse.ZipImporterException.Reason;

/**
 * Opening a bundle the exporter locked.
 *
 * <p><b>The fixture is written by the real exporter, and that is the whole point.</b> It comes
 * from {@code scripts/make_locked_bundle_fixture.py}, which calls
 * {@code opentagviewer_export.zipsink} - so these bytes are AES-256 in the WinZip scheme as
 * pyzipper writes it, and the app reads them with zip4j. A test that both wrote and read with
 * zip4j would prove zip4j agrees with itself, which is not the thing that can break.
 *
 * <p>The passcode is typed here in the grouped form the exporter displays,
 * {@code H4K2-9WMR-7TQX}, and goes through {@link BundlePasscode#normalise} exactly as a user's
 * typing would. A zip password is compared as bytes, so if the two normalisations ever drift
 * apart this fixture stops opening - which is precisely the alarm that is wanted, since the
 * symptom in the field is a user being told their correct code is wrong.
 */
@RunWith(AndroidJUnit4.class)
public class LockedBundleTest {

    /** Must match {@code PASSCODE_AS_DISPLAYED} in the generator. */
    private static final String CODE_AS_WRITTEN_DOWN = "H4K2-9WMR-7TQX";

    private static final String FIXTURE = "locked_bundle_fixture.zip";

    private static final String BEACON = "0FB0AEAC-C083-405E-A979-4AA6A73F5C56";

    private Context appContext;
    private File bundle;

    @Before
    public void setUp() throws IOException {
        this.appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // The fixture ships with the test APK, so it is read from the *instrumentation*
        // context's assets rather than the app's.
        this.bundle = File.createTempFile("otv-locked", ".zip", this.appContext.getCacheDir());
        try (InputStream in = InstrumentationRegistry.getInstrumentation()
                        .getContext().getAssets().open(FIXTURE);
             OutputStream out = new FileOutputStream(this.bundle)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    @After
    public void tearDown() {
        if (this.bundle != null && this.bundle.exists() && !this.bundle.delete()) {
            this.bundle.deleteOnExit();
        }
    }

    /**
     * The headline: a locked bundle from the exporter opens with the code it printed.
     *
     * <p>Every published release fails this - {@code java.util.zip} cannot decrypt anything at
     * all - which is why the exporter's own docs tell people to pass {@code --no-password}.
     */
    @Test
    public void aLockedBundleOpensWithTheCodeTheExporterPrinted() throws Exception {
        final ImportData imported = importWith(BundlePasscode.normalise(CODE_AS_WRITTEN_DOWN));

        assertEquals(1, imported.getOwnedBeacons().size());
        assertEquals(BEACON, imported.getOwnedBeacons().get(0).id);
        assertNotNull(imported.getOwnedBeacons().get(0).content);
        assertTrue("the decrypted plist should be the real thing",
                imported.getOwnedBeacons().get(0).content.contains(BEACON));
    }

    /**
     * Typed the way it is actually typed.
     *
     * <p>Separate from the case above because they fail separately: this one covers the
     * normalisation, and would break if the Java side stopped folding or stopped stripping
     * hyphens while the exporter carried on doing both.
     */
    @Test
    public void theGroupedLowerCaseFormOpensItToo() throws Exception {
        assertEquals(1,
                importWith(BundlePasscode.normalise("h4k2-9wmr-7tqx")).getOwnedBeacons().size());
    }

    /**
     * Asked for rather than failed.
     *
     * <p>Decided from the entry header before any content is read, so the app can put up a
     * prompt. Without this the user gets an error about the zip and no way forward - and the
     * exporter locks by default, so this is the ordinary case, not an edge one.
     */
    @Test
    public void withoutACodeItAsksRatherThanFailing() {
        assertEquals(Reason.LOCKED, failureOf(null));
    }

    /**
     * A wrong code is distinguishable from a broken bundle.
     *
     * <p>AES carries a two-byte password verifier, so this is known at the first entry rather
     * than after decrypting something into nonsense - which is what makes it possible to say
     * "that code did not work" instead of "this export is damaged".
     */
    @Test
    public void aWrongCodeSaysSoRatherThanBlamingTheBundle() throws Exception {
        assertEquals(Reason.WRONG_PASSCODE,
                failureOf(BundlePasscode.normalise("00000000-0000")));
    }

    /** A code of the right shape but one character out. The most likely real mistake. */
    @Test
    public void oneCharacterWrongIsStillJustAWrongCode() throws Exception {
        assertEquals(Reason.WRONG_PASSCODE,
                failureOf(BundlePasscode.normalise("H4K2-9WMR-7TQY")));
    }

    // -----------------------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------------------

    private ImportData importWith(final String passcode) {
        return new AppleZipImporterUtil(this.appContext)
                .extractZip(Uri.fromFile(this.bundle), passcode);
    }

    private Reason failureOf(final String passcode) {
        final ZipImporterException thrown = assertThrows(
                "the bundle opened with a code that should not have worked",
                ZipImporterException.class,
                () -> importWith(passcode));
        return thrown.getReason();
    }
}
