package dev.wander.android.opentagviewer.util.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * Tests for the stream compositions the import and refresh paths are built from.
 * <br>
 * These exist because of a bug that produced no exception, no log line and no crash: importing
 * three tags fetched one, and the other two simply had no card. The cause was
 * {@code Observable.zip} completing as soon as its shortest source did and disposing the rest,
 * which was harmless while every branch emitted exactly once and silently destructive the
 * moment one of them emitted per accessory.
 * <br>
 * A disposal is not a failure, so nothing downstream could notice. The only way to catch that
 * class of bug is to assert on how many times the work actually ran - which is what these do.
 * <br>
 * {@link TestScheduler} is used wherever timing matters, so the tests are deterministic and
 * never sleep.
 */
public class RxFlowsTest {

    // -------------------------------------------------------------------------------------
    // allThen - the import fork-join
    // -------------------------------------------------------------------------------------

    @Test
    public void allThen_consumesEveryEmissionOfEveryStream() {
        final List<Integer> fromLong = new ArrayList<>();
        final List<String> fromShort = new ArrayList<>();

        RxFlows.allThen(
                        Completable.complete(),
                        Observable.just(1, 2, 3).doOnNext(fromLong::add),
                        Observable.just("only").doOnNext(fromShort::add))
                .test()
                .assertComplete();

        assertEquals(List.of(1, 2, 3), fromLong);
        assertEquals(List.of("only"), fromShort);
    }

    /**
     * The regression, stated directly.
     * <br>
     * With zip, the single-emission branch completing would dispose the slow one after its
     * first item. Here the slow branch must still deliver all three - the second and third
     * arriving strictly after the fast branch has finished.
     */
    @Test
    public void allThen_doesNotTruncateASlowStreamWhenAFastOneFinishes() {
        final TestScheduler scheduler = new TestScheduler();
        final List<Long> fetched = new ArrayList<>();

        final Observable<Long> slow = Observable.interval(1, TimeUnit.SECONDS, scheduler)
                .take(3)
                .doOnNext(fetched::add);
        final Observable<String> immediate = Observable.just("parsed");

        final TestObserver<Void> observer = RxFlows.allThen(Completable.complete(), slow, immediate).test();

        // The fast branch is already done here; under zip the slow one would now be cancelled.
        scheduler.advanceTimeBy(1, TimeUnit.SECONDS);
        assertEquals("the fast branch finishing must not end the flow", 1, fetched.size());
        observer.assertNotComplete();

        scheduler.advanceTimeBy(2, TimeUnit.SECONDS);
        assertEquals(List.of(0L, 1L, 2L), fetched);
        observer.assertComplete();
    }

    @Test
    public void allThen_runsTheFollowUpExactlyOnceAndOnlyAfterEveryStreamHasFinished() {
        final PublishSubject<String> first = PublishSubject.create();
        final PublishSubject<String> second = PublishSubject.create();
        final AtomicInteger followUpRuns = new AtomicInteger(0);

        final TestObserver<Void> observer = RxFlows.allThen(
                Completable.fromAction(followUpRuns::incrementAndGet), first, second).test();

        first.onNext("a");
        first.onNext("b");
        first.onComplete();
        assertEquals("one stream finishing is not enough", 0, followUpRuns.get());

        second.onNext("c");
        second.onComplete();

        assertEquals(1, followUpRuns.get());
        observer.assertComplete();
    }

    @Test
    public void allThen_skipsTheFollowUpAndReportsWhenAStreamFails() {
        final AtomicInteger followUpRuns = new AtomicInteger(0);
        final RuntimeException boom = new RuntimeException("fetch failed");

        RxFlows.allThen(
                        Completable.fromAction(followUpRuns::incrementAndGet),
                        Observable.error(boom),
                        Observable.just("parsed"))
                .test()
                .assertError(boom);

        assertEquals("geocoding must not run over a half-finished import", 0, followUpRuns.get());
    }

    @Test
    public void allThen_runsTheStreamsConcurrentlyRatherThanInSequence() {
        final PublishSubject<String> first = PublishSubject.create();
        final PublishSubject<String> second = PublishSubject.create();

        RxFlows.allThen(Completable.complete(), first, second).test();

        // Both are live at once; a sequential composition would not have subscribed to the
        // second until the first had completed.
        assertTrue(first.hasObservers());
        assertTrue(second.hasObservers());
    }

    @Test
    public void allThen_withNoStreamsJustRunsTheFollowUp() {
        final AtomicInteger followUpRuns = new AtomicInteger(0);

        RxFlows.allThen(Completable.fromAction(followUpRuns::incrementAndGet))
                .test()
                .assertComplete();

        assertEquals(1, followUpRuns.get());
    }

    // -------------------------------------------------------------------------------------
    // oneAtATime - the per-accessory fetch loop
    // -------------------------------------------------------------------------------------

    /** Records what a fake fetch was asked for, and how many calls overlapped. */
    private static final class RecordingFetch {
        final List<String> requested = new ArrayList<>();
        final AtomicInteger inFlight = new AtomicInteger(0);
        int maxConcurrent = 0;

        Observable<String> of(final String item) {
            return Observable.<String>create(emitter -> {
                this.requested.add(item);
                this.maxConcurrent = Math.max(this.maxConcurrent, this.inFlight.incrementAndGet());
                emitter.onNext(item + "-result");
                this.inFlight.decrementAndGet();
                emitter.onComplete();
            });
        }
    }

    @Test
    public void oneAtATime_fetchesEveryItem() {
        final RecordingFetch fetch = new RecordingFetch();

        RxFlows.oneAtATime(List.of("a", "b", "c"), fetch::of, (done, total) -> { }, (item, error) -> { })
                .test()
                .assertComplete()
                .assertValues("a-result", "b-result", "c-result");

        assertEquals(List.of("a", "b", "c"), fetch.requested);
    }

    @Test
    public void oneAtATime_neverRunsTwoFetchesAtOnce() {
        final RecordingFetch fetch = new RecordingFetch();

        RxFlows.oneAtATime(List.of("a", "b", "c"), fetch::of, (done, total) -> { }, (item, error) -> { })
                .test()
                .assertComplete();

        // Python drives one asyncio event loop; overlapping calls would queue on a lock at
        // best and corrupt the loop at worst.
        assertEquals(1, fetch.maxConcurrent);
    }

    @Test
    public void oneAtATime_emitsEachResultAsItArrivesRatherThanBatchingAtTheEnd() {
        final PublishSubject<String> first = PublishSubject.create();
        final PublishSubject<String> second = PublishSubject.create();

        final TestObserver<String> observer = RxFlows.oneAtATime(
                List.of(first, second),
                item -> item,
                (done, total) -> { },
                (item, error) -> { }).test();

        first.onNext("early");
        // Persisted now, not at the end of the batch: quitting here must not discard it.
        observer.assertValues("early").assertNotComplete();

        first.onComplete();
        second.onNext("late");
        second.onComplete();

        observer.assertValues("early", "late").assertComplete();
    }

    @Test
    public void oneAtATime_carriesOnWhenOneItemFails() {
        final RuntimeException boom = new RuntimeException("Apple said no");
        final List<String> failures = new ArrayList<>();

        RxFlows.oneAtATime(
                        List.of("a", "bad", "c"),
                        item -> "bad".equals(item)
                                ? Observable.error(boom)
                                : Observable.just(item + "-result"),
                        (done, total) -> { },
                        (item, error) -> failures.add(item + ":" + error.getMessage()))
                .test()
                // A short result beats none: one unreachable tag must not cost the others.
                .assertComplete()
                .assertValues("a-result", "c-result");

        assertEquals(List.of("bad:Apple said no"), failures);
    }

    @Test
    public void oneAtATime_carriesOnWhenTheWorkFunctionItselfThrows() {
        final List<String> failures = new ArrayList<>();

        RxFlows.oneAtATime(
                        List.of("a", "bad", "c"),
                        item -> {
                            if ("bad".equals(item)) {
                                throw new IllegalStateException("no accessory json");
                            }
                            return Observable.just(item + "-result");
                        },
                        (done, total) -> { },
                        (item, error) -> failures.add(item))
                .test()
                .assertComplete()
                .assertValues("a-result", "c-result");

        assertEquals(List.of("bad"), failures);
    }

    @Test
    public void oneAtATime_reportsProgressBeforeStartingAndAfterEachItem() {
        final List<String> progress = new ArrayList<>();

        RxFlows.oneAtATime(
                        List.of("a", "b", "c"),
                        item -> Observable.just(item + "-result"),
                        (done, total) -> progress.add(done + "/" + total),
                        (item, error) -> { })
                .test()
                .assertComplete();

        assertEquals(List.of("0/3", "1/3", "2/3", "3/3"), progress);
    }

    @Test
    public void oneAtATime_countsAFailedItemAsDoneSoTheBannerCannotStall() {
        final List<String> progress = new ArrayList<>();

        RxFlows.oneAtATime(
                        List.of("a", "bad", "c"),
                        item -> "bad".equals(item) ? Observable.error(new RuntimeException()) : Observable.just(item),
                        (done, total) -> progress.add(done + "/" + total),
                        (item, error) -> { })
                .test()
                .assertComplete();

        assertEquals(List.of("0/3", "1/3", "2/3", "3/3"), progress);
    }

    @Test
    public void oneAtATime_restartsTheCountOnResubscribe() {
        final List<String> progress = new ArrayList<>();

        final Observable<String> flow = RxFlows.oneAtATime(
                List.of("a", "b"),
                Observable::just,
                (done, total) -> progress.add(done + "/" + total),
                (item, error) -> { });

        flow.test().assertComplete();
        progress.clear();
        // A refresh reuses the same Observable; a leaked counter would report "3/2".
        flow.test().assertComplete();

        assertEquals(List.of("0/2", "1/2", "2/2"), progress);
    }

    @Test
    public void oneAtATime_stopsRequestingWhenTheSubscriptionIsDisposed() {
        final RecordingFetch fetch = new RecordingFetch();
        final PublishSubject<String> blocker = PublishSubject.create();

        final TestObserver<String> observer = RxFlows.oneAtATime(
                List.of("a", "b", "c"),
                item -> "a".equals(item) ? blocker : fetch.of(item),
                (done, total) -> { },
                (item, error) -> { }).test();

        // Leaving the screen mid-batch must not keep hammering Apple in the background.
        observer.dispose();
        blocker.onComplete();

        assertTrue(fetch.requested.isEmpty());
        assertFalse(blocker.hasObservers());
    }

    @Test
    public void oneAtATime_completesImmediatelyForAnEmptyList() {
        final List<String> progress = new ArrayList<>();

        RxFlows.oneAtATime(List.<String>of(), Observable::just, (done, total) -> progress.add(done + "/" + total), (item, error) -> { })
                .test()
                .assertComplete()
                .assertNoValues();

        assertEquals(List.of("0/0"), progress);
    }
}
