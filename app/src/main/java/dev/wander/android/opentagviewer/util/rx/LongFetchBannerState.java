package dev.wander.android.opentagviewer.util.rx;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Tracks whether the "finding your tags" banner should be up, and what it should count.
 * <br>
 * The banner exists because a tag whose export carried no {@code KeyAlignmentRecord} starts at
 * index 0 from its pairing date, so its first fetch searches the tag's whole life - tens of
 * thousands of key indices, at roughly 290 per request. That is minutes during which nothing
 * changes on screen, and it is indistinguishable from a hang. It also matters that the user
 * does not walk away mid-batch.
 * <br>
 * <b>Only for tags that have nothing to show.</b> It used to go up for any slow batch, so a tag
 * already on the map, or heard Nearby over Bluetooth, got "please keep the app open" about a
 * wait that changed nothing the user could see. Now it counts the batch's tags that have
 * neither, and is hidden whenever that is zero.
 * <br>
 * Only the bookkeeping lives here; the delayed show, the views and the string resources stay
 * in {@code MapsActivity}. Fetches overlap - a manual refresh can start while the periodic one
 * is running - and getting the counting wrong shows a banner that never goes away, or hides one
 * that should still be up. Neither throws, so neither is visible to anything but a person
 * watching the screen.
 * <br>
 * Not synchronised: every method is called from the banner handler, i.e. the main thread.
 */
public final class LongFetchBannerState {

    private int fetchesInFlight = 0;
    /** Set once the delay has passed for a slow batch; cleared when the batch ends. */
    private boolean due = false;
    private final Set<String> lookingFor = new LinkedHashSet<>();

    /**
     * @return true if this is the first fetch of a batch, and the caller should start the
     *         delay after which the banner appears
     */
    public boolean fetchStarted() {
        final boolean first = this.fetchesInFlight == 0;
        this.fetchesInFlight++;
        if (first) {
            // The previous run's tags, so a stale count cannot flash up before this batch says
            // what it is fetching.
            this.lookingFor.clear();
            this.due = false;
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
        final boolean idle = this.fetchesInFlight == 0;
        if (idle) {
            this.due = false;
            this.lookingFor.clear();
        }
        return idle;
    }

    /** The tags a batch is fetching. Added to, because overlapping fetches each name theirs. */
    public void lookingFor(final Collection<String> beaconIds) {
        this.lookingFor.addAll(beaconIds);
    }

    /** The slow-fetch delay has passed: the banner may show, if anything is still missing. */
    public void becameDue() {
        if (this.fetchesInFlight > 0) {
            this.due = true;
        }
    }

    public boolean isFetching() {
        return this.fetchesInFlight > 0;
    }

    /**
     * How many of the batch's tags have nothing on screen yet.
     *
     * @param found whether a tag has a location report or a recent Bluetooth sighting
     */
    public int stillMissing(final Predicate<String> found) {
        int missing = 0;
        for (final String beaconId : this.lookingFor) {
            if (!found.test(beaconId)) {
                missing++;
            }
        }
        return missing;
    }

    /** Whether the banner should be up right now. */
    public boolean shouldShow(final Predicate<String> found) {
        return this.due && this.fetchesInFlight > 0 && this.stillMissing(found) > 0;
    }
}
