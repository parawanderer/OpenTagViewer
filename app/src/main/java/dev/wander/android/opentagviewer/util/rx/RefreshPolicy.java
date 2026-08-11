package dev.wander.android.opentagviewer.util.rx;

/**
 * Decides when the map may refresh, and how much history to ask for when it does.
 * <br>
 * Pure and free of Android types so it can be tested: the rules live in a scheduled callback
 * inside {@code MapsActivity}, where every one of them was previously only observable by
 * watching the app for a minute and guessing. Both bugs this encodes were found that way.
 * <br>
 * Holds the time of the last successful fetch, and nothing else. The caller supplies the
 * current time rather than the class reading a clock, because the fetch has to capture one
 * timestamp and use it for both the request window and the bookkeeping afterwards - taking a
 * fresh reading at the end would leave a gap in history the width of the fetch, which for an
 * unaligned tag is minutes.
 */
public final class RefreshPolicy {

    /** Why a refresh did or did not happen. The reason is logged, so it has to be readable. */
    public enum Decision {
        REFRESH(true, "performing the scheduled refresh"),
        SERVICE_NOT_READY(false, "the Apple service is not initialised yet"),
        INITIAL_FETCH_INCOMPLETE(false, "the first fetch has not finished yet"),
        FETCH_IN_PROGRESS(false, "a fetch is still in progress"),
        TOO_SOON(false, "not enough time has passed since the last fetch");

        private final boolean shouldRefresh;
        private final String reason;

        Decision(final boolean shouldRefresh, final String reason) {
            this.shouldRefresh = shouldRefresh;
            this.reason = reason;
        }

        public boolean shouldRefresh() {
            return this.shouldRefresh;
        }

        public String reason() {
            return this.reason;
        }
    }

    private final long minIntervalMillis;
    private final int maxHoursBack;

    /** Volatile: written from the fetch's thread, read by the scheduler on the main thread. */
    private volatile long lastFetchAt = 0L;

    /**
     * The one everybody shares, for as long as the process lives.
     *
     * <p>"When did we last ask Apple" is a fact about the app, not about a screen. Held per
     * activity, it was reset every time the activity was rebuilt - and changing the theme,
     * language or map provider rebuilds every activity in the process, so the map came back
     * believing it had never fetched and immediately asked Apple for everything again.
     *
     * <p>That is not merely slow. A full fetch walks each tag's key history, and repeating it
     * because somebody toggled dark mode three times is the kind of traffic AGENTS.md rule 6
     * is about.
     */
    private static volatile RefreshPolicy shared;

    public static synchronized RefreshPolicy shared(
            final long minIntervalMillis, final int maxHoursBack) {
        if (shared == null) {
            shared = new RefreshPolicy(minIntervalMillis, maxHoursBack);
        }
        return shared;
    }

    /** Forget the shared instance, so a test starts from "never fetched". */
    public static synchronized void resetShared() {
        shared = null;
    }

    public RefreshPolicy(final long minIntervalMillis, final int maxHoursBack) {
        this.minIntervalMillis = minIntervalMillis;
        this.maxHoursBack = maxHoursBack;
    }

    /**
     * Whether the periodic tick should refresh now.
     * <br>
     * The in-progress check is the important one. Calls into Python are serialised, and a
     * first fetch for an accessory with no key alignment record can run for minutes, so
     * without it a tick does not skip - it blocks on the lock. The backlog then grows by one
     * every minute for the whole fetch and fires all at once when it finally clears.
     */
    public Decision decide(
            final long now,
            final boolean serviceReady,
            final boolean initialFetchComplete,
            final boolean fetchInProgress) {

        if (!serviceReady) {
            return Decision.SERVICE_NOT_READY;
        }
        if (!initialFetchComplete) {
            return Decision.INITIAL_FETCH_INCOMPLETE;
        }
        if (fetchInProgress) {
            return Decision.FETCH_IN_PROGRESS;
        }
        if (now < this.lastFetchAt + this.minIntervalMillis) {
            return Decision.TOO_SOON;
        }
        return Decision.REFRESH;
    }

    /**
     * How many hours of history to request: enough to cover the gap since the last successful
     * fetch, capped at {@code maxHoursBack}.
     * <br>
     * Never returns 0. Asking Apple for zero hours returns nothing, and the caller cannot
     * tell that apart from a tag that has genuinely not been seen - it would look like the
     * refresh worked and the tag had vanished. Reachable whenever two fetches land in the
     * same millisecond, which the manual refresh button allows.
     */
    public int hoursToGoBack(final long now) {
        final long elapsed = Math.max(0L, now - this.lastFetchAt);
        final long hours = (elapsed + ONE_HOUR_MS - 1) / ONE_HOUR_MS; // ceiling division
        return (int) Math.min(Math.max(1L, hours), this.maxHoursBack);
    }

    /**
     * Records a successful fetch.
     *
     * @param at the time the fetch <em>started</em>, not the time it finished
     */
    public void markFetched(final long at) {
        this.lastFetchAt = at;
    }

    public long lastFetchAt() {
        return this.lastFetchAt;
    }

    /** False until the first successful fetch. Distinguishes "never" from "a long time ago". */
    public boolean hasEverFetched() {
        return this.lastFetchAt > 0L;
    }

    public long millisSinceLastFetch(final long now) {
        return now - this.lastFetchAt;
    }

    /**
     * How long since the last fetch, for logging.
     * <br>
     * Says so plainly when there has not been one, rather than reporting the interval since
     * the epoch - which reads as a 56-year-old fetch and sends the reader looking for a clock
     * bug that is not there.
     */
    public String describeTimeSinceLastFetch(final long now) {
        return this.hasEverFetched()
                ? this.millisSinceLastFetch(now) + " ms since the last fetch"
                : "no successful fetch yet";
    }

    private static final long ONE_HOUR_MS = 1000L * 60L * 60L;
}
