package dev.wander.android.opentagviewer.anisette;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The emulated Mac across the real process boundary: {@code libmacadi.so} in {@code :macadi}, the
 * app talking to it over binder (AGENTS.md rules 4 and 21).
 *
 * <p>What has to hold, and cannot be checked on the JVM: Apple's real macOS CoreADI loads under
 * Unicorn over there and answers "not provisioned"; the app process never maps
 * {@code libmacadi.so} at all (which is also what keeps the GPL code out of it); a library that is
 * not the pinned build is refused over there; and a native death over there reaches the app as
 * {@link MacAdi.EmulatorDiedException}, with the app still running to read it.
 *
 * <p>Nothing is sent to Apple but the one range request {@link MacCoreAdiFetcher} makes for the
 * library, as {@code ApplesRealLibraryOnThisDeviceTest} does for the Android one. Provisioning,
 * which would register a machine, is never run.
 *
 * <p><b>One death in this class</b>, for the reason {@code ApplesLibraryInASeparateProcessTest}
 * gives: a process that crashes twice within a minute earns a "keeps stopping" dialog.
 */
@RunWith(AndroidJUnit4.class)
public class TheEmulatedMacInItsOwnProcessTest {

    private static File library;

    private final Context context = getInstrumentation().getTargetContext();

    @BeforeClass
    public static void fetchApplesMacLibrary() throws Exception {
        final Context context = getInstrumentation().getTargetContext();
        library = new File(context.getCacheDir(), "emulated-mac-test/CoreADI-4.19-x86_64");
        MacCoreAdiFetcher.ensure(library);
    }

    private static boolean thisProcessHasMapped(final String name) throws Exception {
        return new String(Files.readAllBytes(new File("/proc/self/maps").toPath()),
                StandardCharsets.UTF_8).contains(name);
    }

    @Test
    public void applesLibraryLoadsOverThereAndThisProcessNeverMapsTheEmulator() throws Exception {
        final RemoteMacAdi adi = RemoteMacAdi.open(this.context, library);
        try {
            adi.setHostUuid(MacIdentity.generate().hostUuidBytes());
            assertThrows(MacAdi.NotProvisionedException.class,
                    () -> adi.headers(MacAdi.ANONYMOUS_DS_ID));
            assertFalse("adi.pb is the library's; storeFiles still answers",
                    adi.storeFiles() == null);
        } finally {
            adi.close();
        }

        assertFalse("libmacadi.so belongs to :macadi, and must never be mapped into the app",
                thisProcessHasMapped("libmacadi.so"));
    }

    @Test
    public void aLibraryThatIsNotThePinnedBuildIsRefusedOverThere() throws Exception {
        final File impostor = new File(this.context.getCacheDir(), "emulated-mac-test/impostor");
        impostor.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(impostor)) {
            out.write("not Apple's library".getBytes(StandardCharsets.UTF_8));
        }

        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> RemoteMacAdi.open(this.context, impostor));

        assertTrue(refused.getMessage(), refused.getMessage().contains("not the pinned"));
        assertFalse(refused instanceof MacAdi.EmulatorDiedException);
    }

    @Test
    public void aNativeDeathOverThereIsReportedHereAndThisProcessCarriesOn() throws Exception {
        final RemoteMacAdi doomed = RemoteMacAdi.open(this.context, library);

        assertThrows(MacAdi.EmulatorDiedException.class, doomed::dieForATest);
        assertTrue(doomed.isGone());

        // Still here, which is the point, and the emulator can be started again afterwards.
        final RemoteMacAdi again = RemoteMacAdi.open(this.context, library);
        try {
            assertThrows(MacAdi.NotProvisionedException.class,
                    () -> again.headers(MacAdi.ANONYMOUS_DS_ID));
        } finally {
            again.close();
        }
    }
}
