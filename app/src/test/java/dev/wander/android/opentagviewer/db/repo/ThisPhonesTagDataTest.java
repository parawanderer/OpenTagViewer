package dev.wander.android.opentagviewer.db.repo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.functions.Supplier;

/**
 * The order "erase all data" does things in, and that a failure stops it.
 *
 * <p>What each step deletes is pinned against an in-memory database by
 * {@code RemovingATagForgetsItsKeysTest}. This is about the sequence, which is where the bugs were:
 * two of the steps are DataStore writes, which start when called rather than when subscribed
 * (AGENTS.md rule 23), and the background scan kept listening for erased tags until it was
 * restarted.
 */
public class ThisPhonesTagDataTest {

    private final List<String> steps = new ArrayList<>();

    /**
     * Stands in for each step. Like a DataStore write, it is recorded when called, not when
     * subscribed, so a step started too early shows up in {@link #steps}.
     */
    private Supplier<Completable> step(final String name, final Set<String> failing) {
        return () -> {
            this.steps.add(name);
            return failing.contains(name)
                    ? Completable.error(new IllegalStateException(name + " failed"))
                    : Completable.complete();
        };
    }

    private ThisPhonesTagData eraser(final Set<String> failing) {
        return new ThisPhonesTagData(
                this.step("link", failing),
                this.step("map position", failing),
                this.step("database", failing),
                () -> this.steps.add("scan"));
    }

    @Test
    public void nothingIsErasedUntilSubscribed() {
        final Completable unused = this.eraser(Set.of()).eraseEverything();

        assertEquals(List.of(), this.steps);
    }

    /** The link first, so no account read can refill an emptied database; the scan last. */
    @Test
    public void erasesInOrderAndRestartsTheScanLast() {
        this.eraser(Set.of()).eraseEverything().test().assertComplete();

        assertEquals(List.of("link", "map position", "database", "scan"), this.steps);
    }

    /** A failed erase must leave the scan alone: the tags it is listening for are still there. */
    @Test
    public void aFailedEraseStopsThereAndLeavesTheScanAlone() {
        this.eraser(Set.of("database")).eraseEverything()
                .test().assertError(IllegalStateException.class);

        assertEquals(List.of("link", "map position", "database"), this.steps);
    }

    /** And a failure at the start erases nothing at all. */
    @Test
    public void aFailureForgettingTheLinkErasesNothingElse() {
        this.eraser(Set.of("link")).eraseEverything()
                .test().assertError(IllegalStateException.class);

        assertEquals(List.of("link"), this.steps);
    }
}
