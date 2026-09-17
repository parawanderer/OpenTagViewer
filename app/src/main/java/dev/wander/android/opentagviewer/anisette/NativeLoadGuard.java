package dev.wander.android.opentagviewer.anisette;

import java.util.concurrent.Callable;

/**
 * Remembers that Apple's library was being loaded, so a load that kills the process is not
 * repeated on every launch.
 *
 * <p><b>Why this exists.</b> On some devices Apple's own {@code libstoreservicescore.so} crashes
 * inside {@code dlopen}, while running its static initialisers - a {@code SIGBUS} with
 * {@code BUS_ADRALN}, reported from a Pixel 5 and a Redmi Note 12 Pro in issue #232. It is the
 * correct arm64 binary, byte for byte the pinned one that works elsewhere. {@link LocalAnisette}
 * catches every exception and falls back to a remote Anisette server, which is the right design,
 * but a signal is not an exception: the process dies inside the call, the fallback never runs,
 * and the login screen - which checks Anisette as soon as it opens - does it again on the next
 * launch. The app became impossible to open on those phones, with no way to reach Settings and
 * choose a server by hand.
 *
 * <p><b>So the record is written before the call and removed after it.</b> A record still present
 * at the next launch means the call never returned, which on a load that takes milliseconds means
 * it killed the process. That launch uses a remote server instead. The user meets the crash once
 * rather than on every launch, which is not good, but it is recoverable, and nothing short of
 * running Apple's code in another process does better.
 *
 * <p><b>The record names the attempt, not just the fact of one.</b> It holds the app version and
 * the pinned library build, and only a record for the <i>same</i> attempt counts. An update to
 * either retries once, so a fix - ours or Apple's - is picked up without anyone clearing data,
 * and a device is not written off forever on the strength of one old crash.
 *
 * <p><b>An exception is not a crash.</b> If the body throws, the process is alive and
 * {@link LocalAnisette} already handles it, so the record is removed before rethrowing. Only a
 * body that never returns at all leaves it behind.
 *
 * <p>Plain Java on purpose, so the decision is tested on the JVM (AGENTS.md rule 13); the
 * storage it needs is the {@link Store} {@link LocalAnisette} backs with preferences.
 */
final class NativeLoadGuard {

    /**
     * Where the record lives.
     *
     * <p><b>A write has to be on disk when {@link #set} returns.</b> The next thing to happen may
     * be the process dying, and anything still buffered dies with it - so a store built on
     * {@code SharedPreferences.Editor.apply()}, which writes asynchronously, would look correct,
     * pass every test here, and never once record a crash. Use {@code commit()}.
     */
    interface Store {
        /** The recorded attempt, or null if there is none. */
        String get();

        /** Record an attempt, durably, before returning. */
        void set(String attempt);

        /** Remove the record, durably. */
        void clear();
    }

    private final Store store;
    private final String attempt;

    /**
     * @param store   where the record is kept
     * @param attempt what is about to be loaded, in a form that changes when the app or the
     *                library does - see {@link #attemptFor}
     */
    NativeLoadGuard(final Store store, final String attempt) {
        this.store = store;
        this.attempt = attempt;
    }

    /** The identity of one load: this build of the app, this ABI, this build of Apple's library. */
    static String attemptFor(final int appVersionCode, final String abi, final String libraryBuild) {
        return appVersionCode + "/" + abi + "/" + libraryBuild;
    }

    /**
     * Whether the last time this exact load was attempted, it never returned.
     *
     * <p>A record from a different attempt is stale - the app or the library has changed since -
     * and is cleared, so the load is tried again.
     */
    boolean previousAttemptCrashed() {
        final String recorded = this.store.get();
        if (recorded == null) {
            return false;
        }
        if (recorded.equals(this.attempt)) {
            return true;
        }

        this.store.clear();
        return false;
    }

    /**
     * Run a load that might take the whole process down with it.
     *
     * <p>The record is written before {@code body} starts and removed only if it returns or
     * throws, so the one outcome that leaves it behind is the process not surviving the call.
     */
    <T> T around(final Callable<T> body) throws Exception {
        this.store.set(this.attempt);

        final T result;
        try {
            result = body.call();
        } catch (final Exception e) {
            // The process survived, so this was not the failure being guarded against.
            this.store.clear();
            throw e;
        }

        this.store.clear();
        return result;
    }
}
