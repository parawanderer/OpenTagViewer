package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deciding whether to load Apple's library in the app's own process, from what happened when a
 * throwaway process loaded it first.
 *
 * <p><b>Issue #232.</b> On some phones the library kills whatever process loads it. The throwaway
 * process is there to be the one that dies. What is tested here is everything the app does with
 * that answer; that a death in the other process is really seen, and really survived, is
 * {@code ApplesLibraryInASeparateProcessTest} on a device.
 */
public class TryingApplesLibraryElsewhereFirstTest {

    private static final String THIS_LOAD = NativeLoadGuard.attemptFor(5, "arm64-v8a", "4.9.6.1447");

    private static final class Remembered implements NativeLoadGuard.Store {
        String value;

        @Override
        public String get() {
            return this.value;
        }

        @Override
        public void set(final String value) {
            this.value = value;
        }

        @Override
        public void clear() {
            this.value = null;
        }
    }

    /** A probe that answers once and counts how often it was asked. */
    private static final class Probe implements TryItElsewhereFirst.Probe {
        final AtomicInteger runs = new AtomicInteger();
        final TryItElsewhereFirst.Outcome outcome;

        Probe(final TryItElsewhereFirst.Outcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public TryItElsewhereFirst.Outcome run() {
            this.runs.incrementAndGet();
            return this.outcome;
        }
    }

    private static TryItElsewhereFirst.Verdict decide(final Remembered disk, final Probe probe) {
        return new TryItElsewhereFirst(disk, THIS_LOAD).decide(probe);
    }

    @Test
    public void asurvivingElsewhereMeansItIsLoadedHere() {
        assertEquals(TryItElsewhereFirst.Verdict.LOAD,
                decide(new Remembered(), new Probe(TryItElsewhereFirst.Outcome.SURVIVED)));
    }

    /** <b>The case this exists for.</b> The other process died, and this one does not follow it. */
    @Test
    public void bdyingElsewhereMeansItIsNotLoadedHere() {
        assertEquals("the app would now crash exactly as the throwaway process just did",
                TryItElsewhereFirst.Verdict.CRASHES_HERE,
                decide(new Remembered(), new Probe(TryItElsewhereFirst.Outcome.DIED)));
    }

    /** Once is enough: a later launch neither starts a process nor loads the library. */
    @Test
    public void cadeathIsRememberedSoTheNextLaunchDoesNotAskAgain() {
        final Remembered disk = new Remembered();
        decide(disk, new Probe(TryItElsewhereFirst.Outcome.DIED));

        final Probe later = new Probe(TryItElsewhereFirst.Outcome.SURVIVED);
        assertEquals(TryItElsewhereFirst.Verdict.CRASHES_HERE, decide(disk, later));
        assertEquals("a known answer should not start another process", 0, later.runs.get());
    }

    /** And so is a pass: the probe costs a process start, once, not on every launch. */
    @Test
    public void dasurvivalIsRememberedToo() {
        final Remembered disk = new Remembered();
        decide(disk, new Probe(TryItElsewhereFirst.Outcome.SURVIVED));

        final Probe later = new Probe(TryItElsewhereFirst.Outcome.DIED);
        assertEquals(TryItElsewhereFirst.Verdict.LOAD, decide(disk, later));
        assertEquals(0, later.runs.get());
    }

    /**
     * <b>An update asks again.</b> A fix - ours, or a new build of Apple's - has to be able to reach
     * a phone that was written off, and on a phone that still crashes the cost is one more death
     * in a process nobody sees.
     */
    @Test
    public void eananswerForAnOlderAppVersionIsNotTrusted() {
        final Remembered disk = new Remembered();
        disk.value = NativeLoadGuard.attemptFor(4, "arm64-v8a", "4.9.6.1447")
                + TryItElsewhereFirst.DIED;

        final Probe probe = new Probe(TryItElsewhereFirst.Outcome.SURVIVED);
        assertEquals(TryItElsewhereFirst.Verdict.LOAD, decide(disk, probe));
        assertEquals(1, probe.runs.get());
    }

    /**
     * <b>No answer is not an answer.</b> The process may be dying slowly - a crash report takes
     * seconds to write - so the library is not loaded here. But nothing is recorded, because nothing
     * was learned, and the next launch asks again.
     */
    @Test
    public void fnoAnswerLoadsNothingAndRecordsNothing() {
        final Remembered disk = new Remembered();

        assertEquals(TryItElsewhereFirst.Verdict.NOT_THIS_TIME,
                decide(disk, new Probe(TryItElsewhereFirst.Outcome.NO_ANSWER)));
        assertNull(disk.value);
    }

    /**
     * If the other process cannot be started at all, the app behaves as it did before this existed:
     * it loads the library itself, under {@link NativeLoadGuard}. Refusing instead would switch
     * local Anisette off on any phone where a service will not start, for no evidence at all.
     */
    @Test
    public void gnotBeingAbleToAskFallsBackToTheGuardAndRecordsNothing() {
        final Remembered disk = new Remembered();

        assertEquals(TryItElsewhereFirst.Verdict.LOAD,
                decide(disk, new Probe(TryItElsewhereFirst.Outcome.COULD_NOT_START)));
        assertNull("the next launch should try the other process again", disk.value);
    }
}
