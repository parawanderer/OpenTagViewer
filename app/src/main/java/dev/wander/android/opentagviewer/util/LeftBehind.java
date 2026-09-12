package dev.wander.android.opentagviewer.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Whether a tag looks left behind: heard until recently, quiet now, and the phone has moved on.
 *
 * <p><b>Two conditions, and the second one is what makes this usable.</b> Silence alone proves
 * nothing - a tag in a bag, behind a body, or simply quiet during a low-power scan window is
 * indistinguishable from one left on a table. Requiring the phone to have <i>moved away</i> since
 * the last sighting turns an absence into something worth saying out loud: you are somewhere
 * else now, and the tag is not with you.
 *
 * <p><b>Not the same thing as the tag reporting itself separated.</b> A Find My accessory says
 * in its own advertisement whether its owner device is near - see {@code FindMyAdvertisement} -
 * but for somebody with no Apple device at all, every tag they own is separated all the time.
 * That signal answers "is this tag away from its owner's iPhone", which is not the question
 * anybody is asking when they walk out of a cafe.
 *
 * <p>No Android in here, and the clock and position are parameters, so the rule is covered by a
 * JVM test rather than by leaving tags in cafes.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LeftBehind {

    /**
     * How long a tag must be unheard before it counts as gone.
     *
     * <p><b>A trigger, not evidence.</b> It used to be the whole rule, which meant it had to be
     * long enough to outlast a radio gap - 90 seconds, and a 66-second gap was measured while
     * simply carrying a tag in a pocket. That is late enough to be useless: somebody who has
     * left a cafe wants to know before the next street, not after it.
     *
     * <p>Since {@code NearbyScanService} verifies with a targeted scan before alerting, silence
     * no longer has to prove anything - it only has to be worth checking. Being wrong here costs
     * a few seconds of listening, so it can afford to be wrong often.
     */
    public static final long QUIET_FOR_MS = 30 * 1000L;

    /**
     * How far the phone must have moved from where the tag was last heard.
     *
     * <p>Well past the range at which the tag would still be audible, so this cannot fire while
     * somebody is still in the same room as it. Roughly the distance from a table to the far
     * side of the building, or a minute's walk.
     */
    public static final double MOVED_AWAY_METRES = 100.0;

    /**
     * True when this tag should be reported as left behind.
     *
     * @param lastHeardMs      when the tag was last heard, or null if it never has been - a tag
     *                         this phone has not met is not one somebody walked away from.
     * @param lastHeardLatitude where the phone was then, or null if it had no fix. Without one
     *                          there is no way to tell moving away from standing still, and the
     *                          silence alone is not enough to alert on.
     */
    public static boolean looksLeftBehind(
            final Long lastHeardMs,
            final Double lastHeardLatitude,
            final Double lastHeardLongitude,
            final long nowMs,
            final double latitude,
            final double longitude) {

        if (lastHeardMs == null || lastHeardLatitude == null || lastHeardLongitude == null) {
            return false;
        }

        if (nowMs - lastHeardMs < QUIET_FOR_MS) {
            return false;
        }

        return LocalFixWorthKeeping.metresBetween(
                lastHeardLatitude, lastHeardLongitude, latitude, longitude) >= MOVED_AWAY_METRES;
    }
}
