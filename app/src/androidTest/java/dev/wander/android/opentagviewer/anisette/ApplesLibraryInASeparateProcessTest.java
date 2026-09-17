package dev.wander.android.opentagviewer.anisette;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Loading Apple's library in a process of its own, and surviving that process dying.
 *
 * <p><b>Issue #232.</b> On some phones Apple's library kills whatever process loads it, and the app
 * used to be that process on every launch. Now a throwaway process loads it first. Whether that
 * phone is affected cannot be staged on an emulator, and does not need to be: what has to hold is
 * that a native death over there is reported here, and that "here" is still running afterwards to
 * read the report. That is what this runs, for real, across a real process boundary.
 *
 * <p><b>The death is a real {@code SIGBUS}</b>, the signal #232's phones die by, delivered to the
 * other process by itself - so the system's crash handling runs exactly as it would there,
 * including the seconds it spends writing a crash report before the process is gone.
 *
 * <p><b>Exactly one death in this whole suite, on purpose.</b> A second crash of the same process
 * within a minute is what Android answers with a "keeps stopping" dialog, and a dialog on the
 * managed device takes focus from every Espresso test after it.
 */
@RunWith(AndroidJUnit4.class)
public class ApplesLibraryInASeparateProcessTest {

    private final Context context = getInstrumentation().getTargetContext();

    /** Nothing is ever downloaded here, so loading from it throws - in the other process. */
    private File nowhere() {
        return new File(this.context.getCacheDir(), "no-apple-libraries-here");
    }

    /**
     * <b>The case this exists for</b>, and then the next launch's case: after one death, the
     * process can be started again and answers normally. If a death left the service unstartable,
     * every later probe would come back "could not start", which loads the library in the app's
     * own process - the crash this is meant to prevent.
     */
    @Test
    public void aprocessThatDiesIsReportedDeadAndThisOneCarriesOn() {
        assertEquals("a native death in the other process has to reach the app as a death",
                TryItElsewhereFirst.Outcome.DIED,
                new AppleLibraryProbe(this.context, this.nowhere(), true).run());

        // Still here - which is the point - and the service works again afterwards.
        assertEquals(TryItElsewhereFirst.Outcome.SURVIVED,
                new AppleLibraryProbe(this.context, this.nowhere()).run());
    }

    /**
     * An exception is not a crash. The other process survived, so the app goes on to load the
     * library itself, fails the same way, and falls back to a server as it always did - rather
     * than this being recorded as a phone Apple's library crashes on.
     */
    @Test
    public void aloadThatThrowsInTheOtherProcessIsNotACrash() {
        assertEquals(TryItElsewhereFirst.Outcome.SURVIVED,
                new AppleLibraryProbe(this.context, this.nowhere()).run());
    }
}
