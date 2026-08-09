package dev.wander.android.opentagviewer.util.rx;

/**
 * Tracks whether the "still locating your tags" banner should be up, and what it should count.
 * <br>
 * The banner exists because a tag whose export carried no {@code KeyAlignmentRecord} starts at
 * index 0 from its pairing date, so its first fetch searches the tag's whole life - tens of
 * thousands of key indices, at roughly 290 per request. That is minutes during which nothing
 * changes on screen, and it is indistinguishable from a hang. It also matters that the user
 * does not walk away mid-batch.
 * <br>
 * Only the bookkeeping lives here; the delayed show, the {@code TextView} and the string
 * resources stay in {@code MapsActivity}. Fetches overlap - a manual refresh can start while
 * the periodic one is running - and getting the counting wrong shows a banner that never goes
 * away, or hides one that should still be up. Neither throws, so neither is visible to
 * anything but a person watching the screen.
 * <br>
 * Not synchronised: every method is called from the banner handler, i.e. the main thread.
 */
public final class LongFetchBannerState {

    private int fetchesInFlight = 0;
    private int done = 0;
    private int total = 0;

    /**
     * @return true if this is the first fetch of a batch, and the caller should start the
     *         delay after which the banner appears
     */
    public boolean fetchStarted() {
        final boolean first = this.fetchesInFlight == 0;
        this.fetchesInFlight++;
        if (first) {
            // Clear the previous run's counts, so a stale "3 of 5" cannot flash up before the
            // new batch reports its own total.
            this.done = 0;
            this.total = 0;
        }
        return first;
    }

    /**
     * @return true if nothing is left in flight, and the caller should cancel the pending show
     *         and hide the banner
     */
    public boolean fetchFinished() {
        // Floored rather than allowed to go negative: an unbalanced finish would otherwise
        // leave the count below zero, and the next start would not be seen as the first - so
        // the banner would never appear again for the rest of the session.
        this.fetchesInFlight = Math.max(0, this.fetchesInFlight - 1);
        return this.fetchesInFlight == 0;
    }

    public void setProgress(final int done, final int total) {
        this.done = done;
        this.total = total;
    }

    public boolean isFetching() {
        return this.fetchesInFlight > 0;
    }

    /** Whether to show "(2 of 5)" at all. "1 of 1" tells the user nothing they cannot see. */
    public boolean hasCount() {
        return this.total > 1;
    }

    /**
     * The one-based position to display: the accessory being worked on, not the count of
     * finished ones. Clamped so the last one cannot read "6 of 5" once it completes.
     */
    public int displayedPosition() {
        return Math.min(this.done + 1, this.total);
    }

    public int total() {
        return this.total;
    }
}
