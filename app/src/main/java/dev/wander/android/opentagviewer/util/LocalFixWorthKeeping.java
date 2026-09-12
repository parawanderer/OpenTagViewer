package dev.wander.android.opentagviewer.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Whether a position heard over Bluetooth is worth writing down, given the last one that was.
 *
 * <p><b>The sighting rate and the useful position rate are two different things.</b> A tag in
 * range is heard every second or two, and the sighting callback is already throttled to once a
 * minute per tag - right for a battery reading, which costs one row that is overwritten. A
 * position costs a row that is <i>kept</i>, and is reverse-geocoded when it is shown. A tag
 * sitting beside somebody all evening would write several hundred rows describing the same
 * spot, and the history it exists to build would become unreadable in the process.
 *
 * <p>So a position is kept when it says something the last one did not: the phone has moved far
 * enough that this is a different place, or enough time has passed that "still here" is itself
 * worth recording.
 *
 * <p>No Android in here - {@code Location.distanceBetween} would drag the whole rule onto a
 * device - so both the distance and the rule are covered by a JVM test.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LocalFixWorthKeeping {

    /**
     * How far the phone must have moved before the same tag earns another row.
     *
     * <p>Bluetooth range is the yardstick, not GPS precision. A match means the tag was within
     * roughly ten metres of the phone, so two fixes closer together than this describe the same
     * place as far as anybody looking for the tag is concerned. Below it the rows would differ
     * only by GPS noise, which is itself several metres when standing still.
     */
    public static final double MOVED_METRES = 25.0;

    /**
     * How long the same place stays worth re-recording.
     *
     * <p>Not zero, because "the keys were still here an hour later" is information a history
     * should carry - it is the difference between a tag last seen at home this morning and one
     * that has been there all day. Long enough that a stationary tag writes a couple of dozen
     * rows a day rather than a thousand.
     */
    public static final long AGAIN_AFTER_MS = 15 * 60 * 1000L;

    /**
     * Mean Earth radius in metres, for {@link #metresBetween}.
     *
     * <p>A sphere, not the ellipsoid the map projects on. Over the distances this rule cares
     * about - tens of metres - the two disagree by centimetres, and the threshold above is a
     * judgement call to within metres anyway.
     */
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    /**
     * True when this fix should be written as a new report for the tag.
     *
     * @param lastMs        when the last local report for this tag was written, or null if there
     *                      is none - the first sighting of a tag is always worth keeping.
     */
    public static boolean worthKeeping(
            final Double lastLatitude,
            final Double lastLongitude,
            final Long lastMs,
            final double latitude,
            final double longitude,
            final long nowMs) {

        if (lastMs == null || lastLatitude == null || lastLongitude == null) {
            return true;
        }

        if (nowMs - lastMs >= AGAIN_AFTER_MS) {
            return true;
        }

        return metresBetween(lastLatitude, lastLongitude, latitude, longitude) >= MOVED_METRES;
    }

    /**
     * Great-circle distance in metres between two coordinates, by the haversine formula.
     *
     * <p>Chosen over the flat-earth approximation because the latter needs a cosine correction
     * that is easy to leave out, and gets worse the further from the equator the user happens to
     * live - a bug nobody in the wrong hemisphere would ever report.
     */
    public static double metresBetween(
            final double lat1, final double lon1, final double lat2, final double lon2) {

        final double dLat = Math.toRadians(lat2 - lat1);
        final double dLon = Math.toRadians(lon2 - lon1);

        final double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
