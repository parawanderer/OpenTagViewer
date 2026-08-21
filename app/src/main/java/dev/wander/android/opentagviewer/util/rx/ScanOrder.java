package dev.wander.android.opentagviewer.util.rx;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * place ahead of tags known to be answering.
 *
 * <p><b>Within that middle group, the export is consulted.</b> This used to be a plain shuffle,
 * on the reasoning that nothing is known about a tag nobody has scanned. That is true of scans
 * and false of the tag: a bundle from format {@code 0.0.2} onward carries a
 * {@code KeyAlignmentRecord} saying when macOS last observed it, and that predicts what the
 * first fetch will cost. A tag observed yesterday is a request or two; one observed eighteen
 * months ago is a walk through tens of thousands of key indices.
 *
 * <p>It matters most exactly where the app is slowest. On a first import every tag is in this
 * group <i>and</i> gets the full seven-day window rather than the usual twenty-four hours, so a
 * new user's first minutes are the worst the app ever performs - and the order decides whether
 * they see a pin appear within seconds or watch an empty map and conclude it does not work.
 * Tags with no alignment record go last, because they are the expensive ones, and are shuffled
 * among themselves so that none of them is permanently the one that never gets reached.
 *
 * <p>Takes its {@link Random} rather than making one, so a test can ask what the order <i>is</i>
 * instead of only what it is not.
 */
public final class ScanOrder {

    private ScanOrder() {
    }

    /** One tag, in the three facts that decide where it goes. */
    public static final class Candidate {
        private final String beaconId;
        private final boolean everScanned;
        private final boolean lastScanFoundSomething;
        private final Long alignedAtMillis;

        public Candidate(@NonNull final String beaconId, final boolean everScanned,
                         final boolean lastScanFoundSomething) {
            this(beaconId, everScanned, lastScanFoundSomething, null);
        }

        /**
         * @param alignedAtMillis when the export said the tag was last observed, or null if it
         *                        carried no alignment record. Only consulted for a tag nobody
         *                        has scanned yet - once there is a real scan to go on, that is
         *                        better evidence than a date from a file.
         */
        public Candidate(@NonNull final String beaconId, final boolean everScanned,
                         final boolean lastScanFoundSomething,
                         @Nullable final Long alignedAtMillis) {
            this.beaconId = beaconId;
            this.everScanned = everScanned;
            this.lastScanFoundSomething = lastScanFoundSomething;
            this.alignedAtMillis = alignedAtMillis;
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
        final List<Candidate> aligned = new ArrayList<>();
        final List<String> neverAligned = new ArrayList<>();
        final List<String> silent = new ArrayList<>();

        for (final Candidate candidate : candidates) {
            if (!candidate.everScanned) {
                if (candidate.alignedAtMillis == null) {
                    neverAligned.add(candidate.beaconId);
                } else {
                    aligned.add(candidate);
                }
            } else if (candidate.lastScanFoundSomething) {
                answering.add(candidate.beaconId);
            } else {
                silent.add(candidate.beaconId);
            }
        }

        Collections.shuffle(answering, random);
        Collections.shuffle(silent, random);

        // **Most recently observed first, and this is the whole point of the group.** A tag
        // whose export says macOS saw it yesterday has a key window of about a hundred indices;
        // one last seen eighteen months ago has tens of thousands, at Apple's ~290 keys per
        // request. On a first import every tag is in this group and gets the full seven-day
        // window, so this is the slowest the app is ever going to be - and the order decides
        // whether a new user sees a pin in seconds or watches nothing happen for minutes.
        aligned.sort(Comparator.comparingLong(
                (Candidate candidate) -> candidate.alignedAtMillis).reversed());

        // **Shuffled, not sorted, and last.** They cost the most, so they go behind the tags
        // that can answer quickly. But the batch is sequential and routinely abandoned - the
        // user closes the app - so a fixed order here would leave the same tag permanently
        // last, never scanned, and permanently without a location. Shuffling is what stops any
        // individual one from being the unlucky one every time.
        Collections.shuffle(neverAligned, random);

        final List<String> order = new ArrayList<>(candidates.size());
        order.addAll(answering);
        for (final Candidate candidate : aligned) {
            order.add(candidate.beaconId);
        }
        order.addAll(neverAligned);
        order.addAll(silent);

        return order;
    }
}
