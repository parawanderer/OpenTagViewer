package dev.wander.android.opentagviewer.util;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * Something expensive and unrepeatable, built at most once however many threads ask.
 *
 * <p><b>Written because getting this wrong crashed the app, natively.</b> Apple's ADI library is
 * loaded into the process, unpacked to a fixed path, and initialised with state it keeps inside
 * itself - so building it twice is not merely wasteful, and building it twice *concurrently*
 * meant two threads writing the same {@code .so} and then {@code dlopen}-ing it. The guard that
 * was supposed to prevent it was {@code synchronized} on an object the caller created fresh every
 * time, which excludes nothing at all. See issue #135.
 *
 * <p><b>The rule lives here rather than inline so it can be tested.</b> Exercising it in place
 * means downloading Apple's libraries and running their native code, which the instrumented suite
 * deliberately does not do - so inline, the one property that matters would be verified by
 * reading it. Here it is a handful of lines with a JVM test that runs threads at it.
 *
 * <p>Two properties, and the second is the one that is easy to lose:
 *
 * <ul>
 *   <li><b>The supplier runs at most once</b>, whatever the contention.</li>
 *   <li><b>A failure is not remembered.</b> If the supplier throws, nothing is cached and the
 *       next caller tries again. Caching the failure would turn one flat network moment into an
 *       app that never loads Anisette again until it is restarted.</li>
 * </ul>
 *
 * <p>Deliberately not double-checked locking, and deliberately not {@code volatile}: the whole
 * point is that every read happens under the lock, and a field that looks safe to read outside
 * one invites exactly the unsynchronised access this exists to stop. Contention here is a handful
 * of calls over the life of a process.
 */
public final class LoadedOnce<T> {

    /** What a caller does to build the value. Allowed to fail, unlike {@code Supplier}. */
    public interface Build<T> {
        T make() throws Exception;
    }

    @Nullable
    private T value;

    /**
     * The value, building it if this is the first ask.
     *
     * @param build run only when there is nothing cached yet, and only by one thread at a time.
     * @throws Exception whatever {@code build} threw, with nothing cached.
     */
    public synchronized T get(final Build<T> build) throws Exception {
        if (this.value == null) {
            // Assigned only after `make` returns. A half-built value published here would be
            // handed to every later caller, and the failure would surface far from its cause.
            this.value = build.make();
        }
        return this.value;
    }

    /** Whether anything has been built yet. */
    public synchronized boolean isLoaded() {
        return this.value != null;
    }

    /**
     * Throw away what was built, so the next ask builds again.
     *
     * <p>Nothing in the app calls this yet. It is here because the thing this holds - an
     * initialised ADI - has a reset in Apple's API ({@code ADIProvisioningErase}), and wiring
     * that up without clearing this would appear to work and change nothing.
     */
    @VisibleForTesting
    public synchronized void forget() {
        this.value = null;
    }
}
