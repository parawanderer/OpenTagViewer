package dev.wander.android.opentagviewer.util.rx;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * What order to ask about tags in, on a scheduled fetch.
 *
 * <p><b>Order matters because the batch is sequential and can be abandoned.</b> One accessory is
 * fetched at a time - Python's account drives a single event loop - and a tag with no key
 * alignment record can take minutes on its own. So a batch is rarely finished in one sitting: the
 * user closes the app, or the screen goes away, and whatever was at the end never got asked.
 *
 * <p>A fixed order therefore does not distribute that cost, it concentrates it. Whichever tag
 * sorts last is the one that is always last, every time, for as long as the app is installed -
 * and it is the one whose location is permanently stalest through no property of its own.
 *
 * <p><b>Two rules, in this order:</b>
 *
 * <ol>
 *   <li><b>Tags that answered last time go first.</b> They are the cheap ones - alignment is
 *       narrow, so each is a request or two - and they are the ones whose rows visibly change.
 *       Putting them first means the screen finishes updating early rather than after the
 *       expensive silent tags have been ground through.</li>
 *   <li><b>Within every group, shuffle.</b> This is what stops any individual tag being
 *       permanently last.</li>
 * </ol>
 *
 * <p>Tags nobody has ever scanned sit between the two: there is no evidence about them either
 * way, so they should not be made to wait behind known-silent ones, and they have not earned a
 * place ahead of tags known to be answering. On a fresh install every tag is in that middle
 * group, which makes the whole batch simply random - the right answer when nothing is known.
 *
 * <p>Takes its {@link Random} rather than making one, so a test can ask what the order <i>is</i>
 * instead of only what it is not.
 */
public final class ScanOrder {

    private ScanOrder() {
    }

    /** One tag, in the two facts that decide where it goes. */
    public static final class Candidate {
        private final String beaconId;
        private final boolean everScanned;
        private final boolean lastScanFoundSomething;

        public Candidate(@NonNull final String beaconId, final boolean everScanned,
                         final boolean lastScanFoundSomething) {
            this.beaconId = beaconId;
            this.everScanned = everScanned;
            this.lastScanFoundSomething = lastScanFoundSomething;
        }

        public String getBeaconId() {
            return this.beaconId;
        }
    }

    /**
     * The ids to ask about, in the order to ask.
     *
     * @param random supplied so the shuffle can be pinned in a test.
     */
    @NonNull
    public static List<String> forScheduledFetch(
            @NonNull final List<Candidate> candidates, @NonNull final Random random) {

        final List<String> answering = new ArrayList<>();
        final List<String> unknown = new ArrayList<>();
        final List<String> silent = new ArrayList<>();

        for (final Candidate candidate : candidates) {
            if (!candidate.everScanned) {
                unknown.add(candidate.beaconId);
            } else if (candidate.lastScanFoundSomething) {
                answering.add(candidate.beaconId);
            } else {
                silent.add(candidate.beaconId);
            }
        }

        Collections.shuffle(answering, random);
        Collections.shuffle(unknown, random);
        Collections.shuffle(silent, random);

        final List<String> order = new ArrayList<>(candidates.size());
        order.addAll(answering);
        order.addAll(unknown);
        order.addAll(silent);

        return order;
    }
}
