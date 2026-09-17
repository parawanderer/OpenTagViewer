package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/**
 * Not crashing on every launch because Apple's library crashed on one.
 *
 * <p><b>Issue #232.</b> On a Pixel 5 and a Redmi Note 12 Pro, loading {@code libstoreservicescore.so}
 * kills the process inside {@code dlopen} with a {@code SIGBUS}. The login screen checks Anisette
 * as soon as it opens, so the app died on launch, every launch, and nothing in Java could catch
 * it - a signal is not an exception.
 *
 * <p>A real crash cannot be staged in a JVM test, and does not need to be: what matters is the
 * record left behind, and a body that never returns leaves exactly what a process death does. So
 * "the process died" is modelled as reading the store while the body is still running, which is
 * the state the next launch finds.
 */
public class SurvivingAppleLibraryCrashingOnLoadTest {

    private static final String THIS_LOAD = NativeLoadGuard.attemptFor(5, "arm64-v8a", "4.9.6.1447");

    /** The preferences file, as far as the guard can tell. */
    private static final class Remembered implements NativeLoadGuard.Store {
        String value;

        @Override
        public String get() {
            return this.value;
        }

        @Override
        public void set(final String attempt) {
            this.value = attempt;
        }

        @Override
        public void clear() {
            this.value = null;
        }
    }

    @Test
    public void afirstLaunchHasNothingToRemember() {
        assertFalse(new NativeLoadGuard(new Remembered(), THIS_LOAD).previousAttemptCrashed());
    }

    /**
     * <b>The case the whole class exists for.</b> The record is on disk before Apple's code
     * runs, so a process that dies inside the call leaves it behind for the next launch.
     */
    @Test
    public void btheRecordIsWrittenBeforeTheLoadSoADeathDuringItLeavesItBehind()
            throws Exception {
        final Remembered disk = new Remembered();

        new NativeLoadGuard(disk, THIS_LOAD).around(() -> {
            // This is the moment the process dies on an affected phone. Whatever is on disk now is
            // all the next launch will have.
            assertEquals("the record must exist before Apple's code runs, or a crash leaves nothing",
                    THIS_LOAD, disk.value);
            return null;
        });
    }

    @Test
    public void ctheNextLaunchAfterACrashDoesNotLoadAgain() {
        final Remembered disk = new Remembered();
        disk.value = THIS_LOAD;   // what a launch that died mid-load leaves behind

        assertTrue("the app would crash again on this launch",
                new NativeLoadGuard(disk, THIS_LOAD).previousAttemptCrashed());
    }

    @Test
    public void ditStaysOffOnEveryLaterLaunchNotJustTheNextOne() {
        final Remembered disk = new Remembered();
        disk.value = THIS_LOAD;

        final NativeLoadGuard guard = new NativeLoadGuard(disk, THIS_LOAD);
        assertTrue(guard.previousAttemptCrashed());
        assertTrue("checking must not consume the record, or the third launch crashes",
                guard.previousAttemptCrashed());
    }

    @Test
    public void eaLoadThatReturnsLeavesNoRecord() throws Exception {
        final Remembered disk = new Remembered();
        final Object library = new Object();

        final Object loaded = new NativeLoadGuard(disk, THIS_LOAD).around(() -> library);

        assertSame(library, loaded);
        assertNull("a successful load left a record, so the next launch would skip local Anisette",
                disk.value);
    }

    /**
     * An exception is not a crash. The process is alive and the caller falls back on its own, so
     * leaving a record would switch local Anisette off for a failure that was already handled.
     */
    @Test
    public void faLoadThatThrowsLeavesNoRecordAndTheExceptionStillArrives() {
        final Remembered disk = new Remembered();

        final IOException thrown = assertThrows(IOException.class,
                () -> new NativeLoadGuard(disk, THIS_LOAD).around(() -> {
                    throw new IOException("the library would not open");
                }));

        assertEquals("the library would not open", thrown.getMessage());
        assertNull(disk.value);
    }

    /**
     * <b>An update gets a fresh try.</b> A crash recorded by an older app build, or against an
     * older library, says nothing about this one - and writing a device off forever would mean
     * a real fix never reaches the people it was for.
     */
    @Test
    public void gaCrashUnderAnOlderAppVersionIsForgottenAfterAnUpdate() {
        final Remembered disk = new Remembered();
        disk.value = NativeLoadGuard.attemptFor(4, "arm64-v8a", "4.9.6.1447");

        assertFalse(new NativeLoadGuard(disk, THIS_LOAD).previousAttemptCrashed());
        assertNull("the stale record should be gone, not left to be misread later", disk.value);
    }

    @Test
    public void haCrashAgainstAnOlderLibraryBuildIsForgottenToo() {
        final Remembered disk = new Remembered();
        disk.value = NativeLoadGuard.attemptFor(5, "arm64-v8a", "4.9.5.1000");

        assertFalse(new NativeLoadGuard(disk, THIS_LOAD).previousAttemptCrashed());
    }

    @Test
    public void ieachPartOfTheAttemptActuallyDistinguishesIt() {
        assertFalse(THIS_LOAD.equals(NativeLoadGuard.attemptFor(6, "arm64-v8a", "4.9.6.1447")));
        assertFalse(THIS_LOAD.equals(NativeLoadGuard.attemptFor(5, "x86_64", "4.9.6.1447")));
        assertFalse(THIS_LOAD.equals(NativeLoadGuard.attemptFor(5, "arm64-v8a", "4.9.6.1448")));
    }
}
