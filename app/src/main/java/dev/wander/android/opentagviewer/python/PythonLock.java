package dev.wander.android.opentagviewer.python;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One location fetch, or one change to which account is signed in, at a time.
 *
 * <p>RxJava schedules our work on a thread pool, and the periodic refresh in {@code MapsActivity}
 * fires every 60 seconds regardless of whether the previous fetch has finished. A fetch that
 * takes longer than the refresh interval is entirely normal for an accessory with no alignment
 * yet, so two fetches overlapping is not a rare race. Running them side by side would double the
 * request rate against Apple for the same keys, which is the account-flagging risk rule 6 is
 * about, and have both write back alignment for the same tags.
 *
 * <p><b>It is no longer what keeps the asyncio loop from being driven twice at once.</b> It used
 * to be: FindMy.py's synchronous account entered its event loop per call, and two threads in
 * {@code run_until_complete} failed with <i>"This event loop is already running"</i>. The loop now
 * runs on its own thread and the account is shared out in turns on the Python side - see
 * {@code account_loop.py}. That is why the iCloud flow does not take this lock any more: it used
 * to wait for a whole fetch, which on a first import was minutes (issue #126), and now waits for
 * one request at most.
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
     * Run one call into Python, with no other fetch or account change in there at the same time.
     *
     * <p><b>Scope it to the call and nothing more.</b> The reason this is a method taking the
     * work, rather than a lock to take and release, is that anything held across a wait for a
     * person - a sign-in code, a passcode dialog - would stop every location refresh in the app
     * until they got round to typing.
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
     * Whether a fetch or an account change is currently in progress.
     *
     * <p>Advisory only. A caller that acts on this can still be beaten to the lock, which is
     * harmless: it just waits, exactly as it did before.
     */
    public static boolean isBusy() {
        return LOCK.isLocked();
    }
}
