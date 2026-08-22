package dev.wander.android.opentagviewer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Building something at most once, however many threads want it.
 *
 * <p><b>The rule that stops the app crashing natively.</b> Apple's ADI library is loaded into the
 * process and initialised with state it keeps inside itself, so two threads building it at once
 * meant two writes of the same {@code .so} and two {@code dlopen}s - a segfault that took whole
 * test runs down and named arbitrary tests on the way. Issue #135.
 *
 * <p>Tested here rather than in place because in place means downloading Apple's libraries and
 * running their native code, which the suite deliberately avoids. So the property is pulled out
 * to where threads can actually be run at it.
 */
public class LoadedOnceTest {

    @Test
    public void thefirstAskBuildsIt() throws Exception {
        final LoadedOnce<String> once = new LoadedOnce<>();

        assertFalse("nothing should be built before anybody asks", once.isLoaded());
        assertEquals("built", once.get(() -> "built"));
        assertTrue(once.isLoaded());
    }

    @Test
    public void latercallersGetTheSameThingWithoutBuildingIt() throws Exception {
        final LoadedOnce<Object> once = new LoadedOnce<>();
        final AtomicInteger builds = new AtomicInteger();

        final Object first = once.get(() -> {
            builds.incrementAndGet();
            return new Object();
        });
        final Object second = once.get(() -> {
            builds.incrementAndGet();
            return new Object();
        });

        assertSame("the second ask must not build a second one", first, second);
        assertEquals(1, builds.get());
    }

    /**
     * <b>The one that matters: twenty threads, one build.</b>
     *
     * <p>All of them released at the same instant, because a loop that calls twenty times in
     * sequence would pass against a completely unsynchronised implementation - which is exactly
     * the implementation this replaces.
     */
    @Test
    public void twentyThreadsAtOnceStillBuildItOnce() throws Exception {
        final LoadedOnce<Object> once = new LoadedOnce<>();
        final AtomicInteger builds = new AtomicInteger();
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(20);
        final List<Object> got = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            new Thread(() -> {
                try {
                    go.await();
                    final Object value = once.get(() -> {
                        builds.incrementAndGet();
                        // Long enough that an unsynchronised version is certain to overlap
                        // rather than merely likely to.
                        Thread.sleep(20);
                        return new Object();
                    });
                    synchronized (got) {
                        got.add(value);
                    }
                } catch (final Exception e) {
                    fail("a caller failed: " + e);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        go.countDown();
        assertTrue("threads did not finish", done.await(30, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals("it was built more than once under contention", 1, builds.get());
        assertEquals(20, got.size());
        for (final Object value : got) {
            assertSame("callers got different values", got.get(0), value);
        }
    }

    /**
     * <b>A failure is not remembered.</b>
     *
     * <p>Caching the failure would turn one flat network moment into an app that never loads
     * Anisette again until it is restarted - and the caller's fallback is a remote server, so
     * nothing would look broken enough to investigate.
     */
    @Test
    public void afailedBuildIsRetriedRatherThanRemembered() throws Exception {
        final LoadedOnce<String> once = new LoadedOnce<>();

        try {
            once.get(() -> {
                throw new IllegalStateException("no network");
            });
            fail("the failure should have reached the caller");
        } catch (final IllegalStateException expected) {
            // what the caller has to see
        }

        assertFalse("a failure must not count as being loaded", once.isLoaded());
        assertEquals("the next ask has to try again", "second time lucky",
                once.get(() -> "second time lucky"));
    }

    /** And once it has succeeded, an earlier failure is ancient history. */
    @Test
    public void asuccessAfterAFailureIsKept() throws Exception {
        final LoadedOnce<String> once = new LoadedOnce<>();

        try {
            once.get(() -> {
                throw new IllegalStateException("no network");
            });
        } catch (final IllegalStateException ignored) {
            // expected
        }

        once.get(() -> "loaded");

        assertEquals("loaded", once.get(() -> {
            throw new AssertionError("must not build again");
        }));
    }

    /** Forgetting is what a future ADIProvisioningErase would need. */
    @Test
    public void forgettingMakesTheNextAskBuildAgain() throws Exception {
        final LoadedOnce<String> once = new LoadedOnce<>();

        once.get(() -> "first");
        once.forget();

        assertFalse(once.isLoaded());
        assertEquals("second", once.get(() -> "second"));
    }
}
