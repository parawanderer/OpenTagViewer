package dev.wander.android.opentagviewer.service.web;

import java.io.IOException;

import io.reactivex.rxjava3.core.Observable;

/**
 * An Anisette server that is up, or is not, on request.
 *
 * <p>The sign-in screen tests a server before it will let anybody past it, so without this a
 * test of the fall-back path would depend on a stranger's machine being reachable from
 * wherever it runs - including CI. Which is the same reason the fall-back path exists.
 *
 * <p>Subclasses the real thing rather than implementing an interface: what it returns is a
 * Retrofit-shaped object, and inventing an interface for one method would move more code than
 * it is worth. The engine is never touched, so null is fine.
 */
public final class FakeAnisetteServerTester extends AnisetteServerTesterService {

    private final boolean reachable;
    private int calls;

    private FakeAnisetteServerTester(final boolean reachable) {
        super(null);
        this.reachable = reachable;
    }

    public static FakeAnisetteServerTester thatIsUp() {
        return new FakeAnisetteServerTester(true);
    }

    public static FakeAnisetteServerTester thatIsDown() {
        return new FakeAnisetteServerTester(false);
    }

    @Override
    public Observable<AnisetteServerRootData> getIndex(final String anisetteServerRootUrl) {
        this.calls++;

        return this.reachable
                ? Observable.just(new AnisetteServerRootData())
                : Observable.error(new IOException("pretend this server is down"));
    }

    /** How many times a server was asked. Zero is the point when Anisette is produced here. */
    public int calls() {
        return this.calls;
    }
}
