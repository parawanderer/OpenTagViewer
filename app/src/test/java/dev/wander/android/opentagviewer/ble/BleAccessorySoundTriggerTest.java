package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.wander.android.opentagviewer.python.AccessoryMacResolver;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.observers.TestObserver;

/**
 * Exercises {@link BleAccessorySoundTrigger}'s own orchestration - the permission gate, the
 * retry count, the continuous-repeat wiring - against fakes for the three seams that would
 * otherwise need real Bluetooth hardware ({@link BleAccessorySoundTrigger.PermissionCheck},
 * {@link BleAccessorySoundTrigger.Scanner}, {@link BleAccessorySoundTrigger.GattTrigger}).
 *
 * <p>Uses {@code String} as the fake "found device" type - see the class doc on why {@code <D>}
 * exists at all. A JVM test on purpose: nothing here needs Android or a device.
 */
public class BleAccessorySoundTriggerTest {

    private static final String A_MAC = "AA:BB:CC:DD:EE:FF";
    private static final String A_DEVICE = A_MAC;

    /** The index the first candidate sits at, so a fed-back sighting is checkable. */
    private static final int A_KEY_INDEX = 4321;
    private static final long AWAIT_SECONDS = 5;

    /**
     * A resolver over a fixed candidate set, counting calls and remembering sightings.
     *
     * <p>A class rather than a lambda because {@link AccessoryMacResolver} gained a second
     * method: a match is fed back through {@code recordSeen} so the next scan can collapse to a
     * single key index, and a one-method interface cannot express both halves.
     */
    private static final class FakeResolver implements AccessoryMacResolver {
        private final Map<String, Integer> candidates;
        private final AtomicInteger resolveCalls = new AtomicInteger();
        private final List<Integer> sightings = new ArrayList<>();

        private FakeResolver(final Map<String, Integer> candidates) {
            this.candidates = candidates;
        }

        @Override
        public Map<String, Integer> currentMacAddresses(final String accessoryJson) {
            this.resolveCalls.incrementAndGet();
            return this.candidates;
        }

        @Override
        public String recordSeen(
                final String accessoryJson, final int keyIndex, final long seenAtUnixMs) {
            this.sightings.add(keyIndex);
            return "{\"aligned\":true}";
        }
    }

    /** Candidates at made-up indices, so "which one matched" is visible in an assertion. */
    private static FakeResolver resolverReturning(final List<String> macs) {
        final Map<String, Integer> candidates = new LinkedHashMap<>();
        for (int i = 0; i < macs.size(); i++) {
            candidates.put(macs.get(i), A_KEY_INDEX + i);
        }
        return new FakeResolver(candidates);
    }

    private static BleSoundTriggerUpdate doneUpdate(final BleSoundTriggerStatus status) {
        return BleSoundTriggerUpdate.done(new BleSoundTriggerResult(status, null, "test"));
    }

    // --- permission gate --------------------------------------------------------------------

    @Test
    public void missingPermissionShortCircuitsBeforeResolvingAnyMac() throws InterruptedException {
        final FakeResolver resolver = resolverReturning(List.of(A_MAC));
        final AtomicInteger resolverCalls = resolver.resolveCalls;

        final BleAccessorySoundTrigger<String> trigger = new BleAccessorySoundTrigger<>(
                resolver,
                context -> false,
                unreachableScanner(),
                unreachableGattTrigger(),
                s -> s,
                3, 0L, 0L);

        final List<BleSoundTriggerUpdate> items = playSoundBlocking(trigger);

        assertEquals(1, items.size());
        assertEquals(BleSoundTriggerStatus.MISSING_PERMISSION, items.get(0).getResult().getStatus());
        assertEquals("a denied permission must not even ask for a MAC address",
                0, resolverCalls.get());
    }

    // --- MAC resolution -----------------------------------------------------------------------

    @Test
    public void noCandidateMacsShortCircuitsBeforeScanning() throws InterruptedException {
        final BleAccessorySoundTrigger<String> trigger = new BleAccessorySoundTrigger<>(
                resolverReturning(List.of()),
                context -> true,
                unreachableScanner(),
                unreachableGattTrigger(),
                s -> s,
                3, 0L, 0L);

        final List<BleSoundTriggerUpdate> items = playSoundBlocking(trigger);

        assertEquals(1, items.size());
        assertEquals(BleSoundTriggerStatus.NO_CANDIDATE_MACS, items.get(0).getResult().getStatus());
    }

    // --- the happy path ---------------------------------------------------------------------

    @Test
    public void aSuccessfulRunEmitsScanningThenWhateverTheGattTriggerEmits() throws InterruptedException {
        final BleAccessorySoundTrigger<String> trigger = new BleAccessorySoundTrigger<>(
                resolverReturning(List.of(A_MAC)),
                context -> true,
                (context, macs, timeout) -> Single.just(A_DEVICE),
                (context, device) -> Observable.just(
                        BleSoundTriggerUpdate.progress(BleSoundTriggerPhase.CONNECTING),
                        BleSoundTriggerUpdate.progress(BleSoundTriggerPhase.TRIGGERING),
                        doneUpdate(BleSoundTriggerStatus.SUCCESS)),
                s -> s,
                3, 0L, 0L);

        final List<BleSoundTriggerUpdate> items = playSoundBlocking(trigger);

        assertEquals(4, items.size());
        assertEquals(BleSoundTriggerPhase.SCANNING, items.get(0).getPhase());
        assertEquals(BleSoundTriggerPhase.CONNECTING, items.get(1).getPhase());
        assertEquals(BleSoundTriggerPhase.TRIGGERING, items.get(2).getPhase());
        assertEquals(BleSoundTriggerStatus.SUCCESS, items.get(3).getResult().getStatus());
    }

    /**
     * <b>A match reports the key index it matched at.</b>
     *
     * <p>Which is the whole reason the resolver returns a map. The candidate set is derived with
     * a twelve-hour margin either side of the believed alignment, and without feeding a hit back
     * that range is re-derived on every scan - about a hundred keys where three would do, on
     * every cycle of a continuous ping. The caller persists it; this only has to report it.
     */
    @Test
    public void asuccessfulMatchReportsWhichKeyIndexAnswered() throws InterruptedException {
        final String anotherMac = "11:22:33:44:55:66";

        final BleAccessorySoundTrigger<String> trigger = new BleAccessorySoundTrigger<>(
                resolverReturning(List.of(A_MAC, anotherMac)),
                context -> true,
                // The *second* candidate answers, so a hardcoded first index cannot pass.
                (context, macs, timeout) -> Single.just(anotherMac),
                (context, device) -> Observable.just(doneUpdate(BleSoundTriggerStatus.SUCCESS)),
                s -> s,
                3, 0L, 0L);

        final List<BleSoundTriggerUpdate> items = playSoundBlocking(trigger);

        final BleSoundTriggerUpdate done = items.get(items.size() - 1);
        assertEquals(BleSoundTriggerStatus.SUCCESS, done.getResult().getStatus());
        assertEquals("the index of the candidate that actually answered",
                Integer.valueOf(A_KEY_INDEX + 1), done.getResult().getMatchedKeyIndex());
    }

    /**
     * <b>And it reports it even when the sound then failed.</b>
     *
     * <p>The tag was there - that is what the scan proved, and it stays true whether or not the
     * GATT exchange worked. Dropping the index on failure would mean the case most likely to be
     * retried is also the one that keeps paying for the wide search.
     */
    @Test
    public void afailedTriggerStillReportsThatTheTagWasSeen() throws InterruptedException {
        final BleAccessorySoundTrigger<String> trigger = new BleAccessorySoundTrigger<>(
                resolverReturning(List.of(A_MAC)),
                context -> true,
                (context, macs, timeout) -> Single.just(A_DEVICE),
                (context, device) -> Observable.just(
                        doneUpdate(BleSoundTriggerStatus.NO_SOUND_SERVICE)),
                s -> s,
                3, 0L, 0L);

        final List<BleSoundTriggerUpdate> items = playSoundBlocking(trigger);

        final BleSoundTriggerUpdate done = items.get(items.size() - 1);
        assertEquals(BleSoundTriggerStatus.NO_SOUND_SERVICE, done.getResult().getStatus());
        assertEquals(Integer.valueOf(A_KEY_INDEX), done.getResult().getMatchedKeyIndex());
    }

    /** Nothing found means nothing to report - there is no sighting to record. */
    @Test
    public void anunfoundTagReportsNoKeyIndex() throws InterruptedException {
        final BleAccessorySoundTrigger<String> trigger = new BleAccessorySoundTrigger<>(
                resolverReturning(List.of(A_MAC)),
                context -> true,
                (context, macs, timeout) ->
                        Single.error(new NearbyAccessoryScanner.NotNearbyException()),
                unreachableGattTrigger(),
                s -> s,
                3, 0L, 0L);

        final List<BleSoundTriggerUpdate> items = playSoundBlocking(trigger);

        final BleSoundTriggerUpdate done = items.get(items.size() - 1);
        assertEquals(BleSoundTriggerStatus.NOT_NEARBY, done.getResult().getStatus());
        assertNull(done.getResult().getMatchedKeyIndex());
    }

    @Test
    public void theCandidateMacsPassedToTheScannerComeFromTheResolver() throws InterruptedException {
        final AtomicInteger seenCandidateCount = new AtomicInteger(-1);

        final BleAccessorySoundTrigger<String> trigger = new BleAccessorySoundTrigger<>(
                resolverReturning(List.of(A_MAC, "11:22:33:44:55:66")),
                context -> true,
                (context, macs, timeout) -> {
                    seenCandidateCount.set(macs.size());
                    return Single.just(A_DEVICE);
                },
                (context, device) -> Observable.just(doneUpdate(BleSoundTriggerStatus.SUCCESS)),
                s -> s,
                3, 0L, 0L);

        playSoundBlocking(trigger);

        assertEquals(2, seenCandidateCount.get());
    }

    // --- retry ------------------------------------------------------------------------------

    @Test
    public void aFailedAttemptIsRetriedUpToTheAttemptLimit() throws InterruptedException {
        final AtomicInteger gattCalls = new AtomicInteger();

        final BleAccessorySoundTrigger<String> trigger = new BleAccessorySoundTrigger<>(
                resolverReturning(List.of(A_MAC)),
                context -> true,
                (context, macs, timeout) -> Single.just(A_DEVICE),
                (context, device) -> {
                    gattCalls.incrementAndGet();
                    return Observable.just(doneUpdate(BleSoundTriggerStatus.FAILED));
                },
                s -> s,
                3, 0L, 0L);

        final List<BleSoundTriggerUpdate> items = playSoundBlocking(trigger);

        assertEquals("FAILED should be retried until the attempt limit", 3, gattCalls.get());
        assertEquals(BleSoundTriggerStatus.FAILED,
                items.get(items.size() - 1).getResult().getStatus());
    }

    @Test
    public void aSuccessfulRetryStopsFurtherAttempts() throws InterruptedException {
        final AtomicInteger gattCalls = new AtomicInteger();

        final BleAccessorySoundTrigger<String> trigger = new BleAccessorySoundTrigger<>(
                resolverReturning(List.of(A_MAC)),
                context -> true,
                (context, macs, timeout) -> Single.just(A_DEVICE),
                (context, device) -> Observable.just(gattCalls.incrementAndGet() == 1
                        ? doneUpdate(BleSoundTriggerStatus.FAILED)
                        : doneUpdate(BleSoundTriggerStatus.SUCCESS)),
                s -> s,
                3, 0L, 0L);

        final List<BleSoundTriggerUpdate> items = playSoundBlocking(trigger);

        assertEquals("should have stopped after the second, successful attempt", 2, gattCalls.get());
        assertEquals(BleSoundTriggerStatus.SUCCESS,
                items.get(items.size() - 1).getResult().getStatus());
    }

    @Test
    public void noSoundServiceIsNeverRetried() throws InterruptedException {
        final AtomicInteger gattCalls = new AtomicInteger();

        final BleAccessorySoundTrigger<String> trigger = new BleAccessorySoundTrigger<>(
                resolverReturning(List.of(A_MAC)),
                context -> true,
                (context, macs, timeout) -> Single.just(A_DEVICE),
                (context, device) -> {
                    gattCalls.incrementAndGet();
                    return Observable.just(doneUpdate(BleSoundTriggerStatus.NO_SOUND_SERVICE));
                },
                s -> s,
                3, 0L, 0L);

        final List<BleSoundTriggerUpdate> items = playSoundBlocking(trigger);

        assertEquals("connecting worked and found nothing recognisable - a retry cannot fix that",
                1, gattCalls.get());
        assertEquals(BleSoundTriggerStatus.NO_SOUND_SERVICE,
                items.get(items.size() - 1).getResult().getStatus());
    }

    // --- scanner failure modes ----------------------------------------------------------------

    @Test
    public void aScannerTimeoutMapsToNotNearby() throws InterruptedException {
        final BleAccessorySoundTrigger<String> trigger = new BleAccessorySoundTrigger<>(
                resolverReturning(List.of(A_MAC)),
                context -> true,
                (context, macs, timeout) -> Single.error(new NearbyAccessoryScanner.NotNearbyException()),
                unreachableGattTrigger(),
                s -> s,
                3, 0L, 0L);

        final List<BleSoundTriggerUpdate> items = playSoundBlocking(trigger);

        assertEquals(BleSoundTriggerStatus.NOT_NEARBY,
                items.get(items.size() - 1).getResult().getStatus());
    }

    @Test
    public void anUnexpectedScannerErrorMapsToFailedRatherThanCrashing() throws InterruptedException {
        final BleAccessorySoundTrigger<String> trigger = new BleAccessorySoundTrigger<>(
                resolverReturning(List.of(A_MAC)),
                context -> true,
                (context, macs, timeout) -> Single.error(new IllegalStateException("radio is off")),
                unreachableGattTrigger(),
                s -> s,
                3, 0L, 0L);

        final TestObserver<BleSoundTriggerUpdate> observer = trigger.playSound(null, "{}").test();
        assertTrue(observer.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        observer.assertComplete(); // the never-errors contract - see AccessorySoundTrigger's doc
        observer.assertNoErrors();

        final List<BleSoundTriggerUpdate> items = observer.values();
        assertEquals(BleSoundTriggerStatus.FAILED,
                items.get(items.size() - 1).getResult().getStatus());
    }

    // --- continuous ping ----------------------------------------------------------------------

    @Test
    public void continuousPingRepeatsAfterEachCycleUntilDisposed() throws InterruptedException {
        final AtomicInteger scannerCalls = new AtomicInteger();
        final CountDownLatch sawThreeCycles = new CountDownLatch(1);

        final BleAccessorySoundTrigger<String> trigger = new BleAccessorySoundTrigger<>(
                resolverReturning(List.of(A_MAC)),
                context -> true,
                (context, macs, timeout) -> {
                    if (scannerCalls.incrementAndGet() >= 3) {
                        sawThreeCycles.countDown();
                    }
                    return Single.error(new NearbyAccessoryScanner.NotNearbyException());
                },
                unreachableGattTrigger(),
                s -> s,
                3, 0L, 1L); // 1ms pause - fast, but still an async repeatWhen delay

        final Disposable subscription = trigger.playSoundContinuously(null, "{}")
                .subscribe(update -> { }, error -> fail("playSoundContinuously must never error"));
        try {
            assertTrue("expected at least 3 scan cycles within " + AWAIT_SECONDS + "s, got "
                            + scannerCalls.get(),
                    sawThreeCycles.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
        }
    }

    @Test
    public void disposingContinuousPingStopsFurtherCycles() throws InterruptedException {
        final AtomicInteger scannerCalls = new AtomicInteger();

        final BleAccessorySoundTrigger<String> trigger = new BleAccessorySoundTrigger<>(
                resolverReturning(List.of(A_MAC)),
                context -> true,
                (context, macs, timeout) -> {
                    scannerCalls.incrementAndGet();
                    return Single.error(new NearbyAccessoryScanner.NotNearbyException());
                },
                unreachableGattTrigger(),
                s -> s,
                3, 0L, 1L);

        final Disposable subscription = trigger.playSoundContinuously(null, "{}")
                .subscribe(update -> { }, error -> fail("playSoundContinuously must never error"));

        // Give it a moment to run a few cycles, then stop it.
        Thread.sleep(200);
        subscription.dispose();
        final int callsAtDispose = scannerCalls.get();
        Thread.sleep(200);

        assertEquals("a cycle ran after dispose - the loop was not actually stopped",
                callsAtDispose, scannerCalls.get());
    }

    // --- helpers ------------------------------------------------------------------------------

    private static List<BleSoundTriggerUpdate> playSoundBlocking(
            final BleAccessorySoundTrigger<String> trigger) throws InterruptedException {
        final TestObserver<BleSoundTriggerUpdate> observer = trigger.playSound(null, "{}").test();
        assertTrue("playSound did not complete within " + AWAIT_SECONDS + "s",
                observer.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        observer.assertComplete();
        observer.assertNoErrors();
        return observer.values();
    }

    private static BleAccessorySoundTrigger.Scanner<String> unreachableScanner() {
        return (context, macs, timeout) -> {
            throw new AssertionError("scanner should not have been called");
        };
    }

    private static BleAccessorySoundTrigger.GattTrigger<String> unreachableGattTrigger() {
        return (context, device) -> {
            throw new AssertionError("gatt trigger should not have been called");
        };
    }
}
