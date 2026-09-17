package dev.wander.android.opentagviewer.anisette;

/**
 * Decides whether this app's own process may load Apple's library, by asking a throwaway process
 * to load it first.
 *
 * <p><b>Why this exists.</b> On some phones Apple's {@code libstoreservicescore.so} kills the
 * process that loads it, inside {@code dlopen} (issue #232). {@link NativeLoadGuard} notices that
 * afterwards, but only afterwards: somebody still meets one crash before it knows. A process that
 * exists only to load the library can die in their place. Android reports its death to whoever
 * bound it, and the app carries on with a remote Anisette server instead - no crash on screen, not
 * even once, including for people upgrading from 1.1.0 who have no record of the crash yet.
 *
 * <p><b>The answer is kept, so the throwaway process runs once and not on every launch.</b> It is
 * keyed like {@link NativeLoadGuard}'s record - app version, ABI, library build - so an update to
 * either asks again. A fix, ours or Apple's, then reaches a phone that was written off, and a phone
 * that still crashes costs one more silent death in a process nobody sees.
 *
 * <p><b>Not getting an answer is not an answer.</b> If the other process cannot be started at all,
 * the load goes ahead here under {@link NativeLoadGuard}, which is what the app did before this
 * existed. If it started and did not reply in time, the library is <i>not</i> loaded here - it may
 * be dying slowly, and writing a crash report takes seconds - but nothing is recorded either, so
 * the next launch asks again.
 *
 * <p>Plain Java on purpose, so every branch is tested on the JVM (AGENTS.md rule 13). The real
 * process is {@link AppleLibraryProbeService}, reached through {@link AppleLibraryProbe}.
 */
final class TryItElsewhereFirst {

    /** What happened to the throwaway process. */
    enum Outcome {
        /** It loaded the library and replied. Whether the load threw does not matter here. */
        SURVIVED,
        /** It died before replying. */
        DIED,
        /** It started and did not reply in time. */
        NO_ANSWER,
        /** It could not be started at all, so there is no evidence either way. */
        COULD_NOT_START
    }

    /** What this process should do. */
    enum Verdict {
        /** Load it here, still inside {@link NativeLoadGuard}. */
        LOAD,
        /** Do not load it on this device, for this app and library build. */
        CRASHES_HERE,
        /** Do not load it now, and ask again next launch. */
        NOT_THIS_TIME
    }

    /** Runs the throwaway process. Blocks until it has an outcome. */
    interface Probe {
        Outcome run();
    }

    static final String SURVIVED = "=survived";
    static final String DIED = "=died";

    private final NativeLoadGuard.Store store;
    private final String attempt;

    /**
     * @param store   where the answer is kept, written with {@code commit()} for the same reason
     *                as {@link NativeLoadGuard.Store}
     * @param attempt from {@link NativeLoadGuard#attemptFor}
     */
    TryItElsewhereFirst(final NativeLoadGuard.Store store, final String attempt) {
        this.store = store;
        this.attempt = attempt;
    }

    Verdict decide(final Probe probe) {
        final String recorded = this.store.get();
        if ((this.attempt + SURVIVED).equals(recorded)) {
            return Verdict.LOAD;
        }
        if ((this.attempt + DIED).equals(recorded)) {
            return Verdict.CRASHES_HERE;
        }

        switch (probe.run()) {
            case SURVIVED:
                this.store.set(this.attempt + SURVIVED);
                return Verdict.LOAD;
            case DIED:
                this.store.set(this.attempt + DIED);
                return Verdict.CRASHES_HERE;
            case COULD_NOT_START:
                // No evidence, so behave as the app did before this existed: load under the guard.
                // Unrecorded, so the next launch tries the other process again.
                return Verdict.LOAD;
            case NO_ANSWER:
            default:
                return Verdict.NOT_THIS_TIME;
        }
    }
}
