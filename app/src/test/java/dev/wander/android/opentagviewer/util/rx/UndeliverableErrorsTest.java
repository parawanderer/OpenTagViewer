package dev.wander.android.opentagviewer.util.rx;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.observers.DisposableObserver;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;

public class UndeliverableErrorsTest {
    private final AtomicReference<Throwable> crashed = new AtomicReference<>();
    private Thread.UncaughtExceptionHandler previous;

    @Before
    public void catchCrashes() {
        this.previous = Thread.currentThread().getUncaughtExceptionHandler();
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> this.crashed.set(e));
        RxJavaPlugins.setErrorHandler(UndeliverableErrors.handler());
    }

    @After
    public void putThemBack() {
        RxJavaPlugins.reset();
        Thread.currentThread().setUncaughtExceptionHandler(this.previous);
    }

    /**
     * The 2026-11-21 crash: the screen was rebuilt, disposing the fetch's subscription while the
     * fetch was running, and the fetch then failed. A checked exception, as the real
     * {@code PythonAppleFindMyException} is.
     */
    @Test
    public void aFetchThatFailsAfterItsScreenWentAwayDoesNotCrash() {
        final DisposableObserver<Object> screen = new DisposableObserver<>() {
            @Override
            public void onNext(final Object o) {
            }

            @Override
            public void onError(final Throwable e) {
            }

            @Override
            public void onComplete() {
            }
        };

        Observable.fromCallable(() -> {
            screen.dispose();
            throw new Exception("Error while retrieving last reports for account via python!");
        }).subscribe(screen);

        assertNull("an error nobody is listening for must not crash the app", this.crashed.get());
    }

    /** RxJava passes these through unwrapped because they mean a bug; those must still crash. */
    @Test
    public void aBugStillCrashes() {
        final IllegalStateException bug = new IllegalStateException("a real bug");

        RxJavaPlugins.onError(bug);

        assertSame(bug, this.crashed.get());
    }
}
