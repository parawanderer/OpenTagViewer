package dev.wander.android.opentagviewer.python;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one lock that every call into Python must be made under.
 *
 * <p>FindMy.py's synchronous {@code AppleAccount} wraps an async one and drives it with a single
 * asyncio event loop. RxJava schedules our work on a thread pool, and the periodic refresh in
 * {@code MapsActivity} fires every 60 seconds regardless of whether the previous fetch has
 * finished. Two threads calling {@code run_until_complete} on the same loop fails with
 * <i>"RuntimeError: This event loop is already running"</i> and then keeps failing.
 *
 * <p>A fetch that takes longer than the refresh interval is entirely normal for an accessory with
 * no alignment yet, so this is not a rare race.
 *
 * <p><b>It lives here, rather than inside one service, because it is not one service's lock.</b>
 * The iCloud flow drives the <i>same</i> event loop - deliberately, since a second account would
 * be a second device to Apple - so a location fetch and a keychain unlock collide exactly as two
 * location fetches would. A second private lock in a second class would be no lock at all.
 *
 * <p>Serialising alone is not enough: the periodic refresh would still queue up behind a slow
 * fetch, one entry per minute, and then fire the whole stale backlog at once when it finally
 * drained. Callers on the periodic path should check {@link #isBusy()} and skip their turn
 * instead - a refresh that is minutes late has no value.
 */
public final class PythonLock {
    private static final ReentrantLock LOCK = new ReentrantLock();

    private PythonLock() {
    }

    /**
     * Run one call into Python, with nothing else in there at the same time.
     *
     * <p><b>Scope it to the call and nothing more.</b> The reason this is a method taking the
     * work, rather than a lock to take and release, is the iCloud flow: it is several calls with
     * a person answering a dialog between them, and holding this across one of those waits would
     * stop every location refresh in the app until they got round to typing. Each step takes it,
     * finishes, and gives it back; the waiting happens in Java with nothing held.
     */
    public static <T> T holding(final Callable<T> work) throws Exception {
        LOCK.lock();
        try {
            return work.call();
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Whether a call into Python is currently in progress.
     *
     * <p>Advisory only. A caller that acts on this can still be beaten to the lock, which is
     * harmless: it just waits, exactly as it did before.
     */
    public static boolean isBusy() {
        return LOCK.isLocked();
    }
}
