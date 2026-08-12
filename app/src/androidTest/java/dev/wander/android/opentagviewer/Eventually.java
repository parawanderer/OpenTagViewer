package dev.wander.android.opentagviewer;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.os.SystemClock;

import java.util.function.BooleanSupplier;

/**
 * Waiting for a screen that Espresso cannot wait for by itself.
 *
 * <p>Espresso waits for the main thread to go idle and for nothing else. Every interesting step
 * in this app runs on an RxJava scheduler and hops back, so an assertion made the instant a
 * click returns is asking about work that has not started. That is a property of the app, not
 * a defect in the test - the alternative, an IdlingResource, would mean threading test-only
 * bookkeeping through production Rx chains.
 *
 * <p><b>Why this is one class rather than a helper in each test.</b> It used to be copied into
 * three of them. When a flake was found and fixed in one copy, the identical code in the others
 * kept failing in CI, which is how this went red three times running.
 */
public final class Eventually {

    private Eventually() {}

    private static final int ATTEMPTS = 50;
    private static final long PAUSE_MS = 100;

    /**
     * Retry an assertion until it holds.
     *
     * <p>Catches {@code RuntimeException} as well as {@code AssertionError}, because Espresso
     * reports "the view is not there yet" as {@code PerformException} and
     * {@code NoMatchingViewException}, neither of which is an assertion failure.
     */
    public static void check(final Runnable assertion) {
        Throwable last = null;

        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                assertion.run();
                return;
            } catch (final AssertionError | RuntimeException problem) {
                last = problem;
                settle();
            }
        }

        rethrow(last);
    }

    /**
     * Do something until it has actually taken effect, then stop.
     *
     * <p><b>Use this rather than {@link #check} for anything that changes the screen.</b>
     * Retrying until nothing throws cannot tell two opposite situations apart:
     *
     * <ul>
     *   <li>the tap missed - the keyboard was still over the button, say - and must be retried
     *   <li>the tap worked, and what it started has already finished and torn the screen down,
     *       so Espresso throws {@code NoActivityResumedException} from the very same call
     * </ul>
     *
     * <p>Retrying the second turns one success into fifty failures. Only the caller knows what
     * "it worked" means, so the caller says.
     *
     * @param what       named in the failure, so a timeout says which step never landed
     * @param tookEffect asked before and after each attempt. Anything observable will do - a
     *                   fake having been called, a value having been stored
     */
    public static void perform(final String what, final BooleanSupplier tookEffect,
                               final Runnable action) {
        Throwable last = null;

        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (tookEffect.getAsBoolean()) {
                return;
            }

            try {
                action.run();
            } catch (final AssertionError | RuntimeException problem) {
                last = problem;
            }

            // Checked again straight away: the action may have both worked and thrown, which
            // is the whole reason this method exists.
            if (tookEffect.getAsBoolean()) {
                return;
            }
            settle();
        }

        if (last != null) {
            rethrow(last);
        }
        throw new AssertionError(what + " never took effect");
    }

    private static void settle() {
        getInstrumentation().waitForIdleSync();
        SystemClock.sleep(PAUSE_MS);
    }

    private static void rethrow(final Throwable last) {
        if (last instanceof RuntimeException) {
            throw (RuntimeException) last;
        }
        if (last instanceof AssertionError) {
            throw (AssertionError) last;
        }
        throw new AssertionError("gave up waiting", last);
    }
}
