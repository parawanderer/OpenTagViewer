package dev.wander.android.opentagviewer.util.rx;

import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.functions.BiConsumer;
import io.reactivex.rxjava3.functions.Function;

/**
 * Stream compositions that the app depends on and that are easy to get subtly wrong.
 * <br>
 * Both methods here exist because the composition, not the work, was the bug. They are kept
 * free of Android types so they can be tested on the JVM: the originals lived inside
 * {@code MapsActivity}, where nothing could reach them, and the failure they produced was a
 * missing card rather than an exception - no crash, no log, no test.
 *
 * @see dev.wander.android.opentagviewer.MapsActivity
 */
public final class RxFlows {

    private RxFlows() {
    }

    /**
     * Runs every stream to completion, concurrently, and then runs {@code then} once.
     * <br>
     * Written to replace an {@link Observable#zip} fork-join. zip completes as soon as its
     * <em>shortest</em> source does and disposes the rest, so pairing a stream that emits once
     * per accessory with one that emits a single parsed list cancelled every accessory after
     * the first - silently, because a disposal is not an error. Importing three tags fetched
     * one. Merging cannot truncate a source; it ends when all of them have.
     * <br>
     * Emissions are dropped, so any per-item work belongs in a {@code doOnNext} on the stream
     * that is passed in. If any stream fails, {@code then} does not run.
     *
     * @param then    run once, after every stream has completed
     * @param streams run concurrently; their values are ignored
     */
    public static Completable allThen(final Completable then, final Observable<?>... streams) {
        final Completable[] parts = new Completable[streams.length];
        for (int i = 0; i < streams.length; i++) {
            parts[i] = streams[i].ignoreElements();
        }
        return Completable.mergeArray(parts).andThen(then);
    }

    /**
     * Runs {@code work} for each item, one at a time and in order, carrying on if one fails.
     * <br>
     * Sequential on purpose. FindMy.py's synchronous account drives a single asyncio event
     * loop, and calls into Python are serialised anyway, so {@code concatMap} makes explicit
     * what would otherwise be threads queued on a lock.
     * <br>
     * Each result is emitted as it arrives rather than batched at the end. That matters
     * because the caller persists them: handing Python the whole list meant one dict at the
     * very end, so quitting part-way through discarded the updated key alignment for every
     * accessory - including ones that had already resolved - and the next launch searched the
     * same tens of thousands of key indices again.
     * <br>
     * A failure is reported to {@code onError} and the item is skipped. One tag that Apple
     * will not answer for must not take the rest of the batch with it.
     *
     * @param items    processed in list order
     * @param work     the request for one item; may emit any number of results
     * @param progress called with (completed, total) before starting and after each item
     * @param onError  called with the item and its failure; the batch continues regardless
     */
    public static <T, R> Observable<R> oneAtATime(
            final List<T> items,
            final Function<? super T, ? extends Observable<R>> work,
            final BiConsumer<Integer, Integer> progress,
            final BiConsumer<? super T, ? super Throwable> onError) {

        final int total = items.size();
        final int[] completed = {0};

        return Observable.<R>defer(() -> {
                    // Inside defer so a re-subscribe restarts the count rather than resuming
                    // from where the last subscription left off.
                    completed[0] = 0;
                    progress.accept(0, total);

                    return Observable.fromIterable(items).concatMap(item ->
                            asObservable(work, item)
                                    .doOnError(error -> onError.accept(item, error))
                                    .onErrorResumeNext(__ -> Observable.empty())
                                    .doOnComplete(() -> progress.accept(++completed[0], total)));
                });
    }

    /** Keeps a throwing {@code work} function from breaking the chain before it starts. */
    private static <T, R> Observable<R> asObservable(
            final Function<? super T, ? extends Observable<R>> work, final T item) {
        try {
            return work.apply(item);
        } catch (Throwable error) {
            return Observable.error(error);
        }
    }
}
