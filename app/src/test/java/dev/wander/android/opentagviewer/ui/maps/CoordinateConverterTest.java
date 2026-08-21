package dev.wander.android.opentagviewer.ui.maps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The GCJ-02 shift AMap needs, and the places it must not touch.
 *
 * <p><b>This is the only piece of the AMap path anything tests.</b> Both real map providers are
 * untestable on the managed device - {@code aosp-atd} has no Play Services and the AMap SDK is
 * optional at compile time - so everything else runs against {@code FakeMapProvider}. This class
 * is the exception, because it is arithmetic: no Android, no device, no map. It runs on the JVM
 * in milliseconds.
 *
 * <p><b>Worth having out of all proportion to its size.</b> A wrong conversion does not fail: it
 * puts every pin a few hundred metres from where the tag actually is, consistently, for everyone
 * using AMap - which is to say everyone in mainland China, the users least able to report it
 * back. Nothing else in the app would notice.
 *
 * <p><b>Properties, not published magic numbers.</b> The reference values for a given landmark
 * vary between sources by a few metres, so asserting one would be pinning somebody else's
 * rounding. What is asserted instead is what has to be true of any correct implementation: the
 * round trip comes back, the offset is the size GCJ-02 offsets actually are, and coordinates
 * outside the covered region are returned untouched.
 */
public class CoordinateConverterTest {

    /** Metres per degree of latitude, near enough for a sanity bound. */
    private static final double METRES_PER_DEGREE = 111_320.0;

    // Inside the region the converter treats as covered.
    private static final double BEIJING_LAT = 39.90869;
    private static final double BEIJING_LON = 116.39123;

    private static final double SHANGHAI_LAT = 31.23039;
    private static final double SHANGHAI_LON = 121.47370;

    // Comfortably outside it - and where most of this app's users are.
    private static final double AMSTERDAM_LAT = 52.370216;
    private static final double AMSTERDAM_LON = 4.895168;

    private static final double NEW_YORK_LAT = 40.712776;
    private static final double NEW_YORK_LON = -74.005974;

    // --- the shift itself -------------------------------------------------------------------

    /**
     * <b>A covered coordinate is moved, and by the distance GCJ-02 actually moves things.</b>
     *
     * <p>The published offset runs from roughly a hundred metres to about seven hundred,
     * depending where you are. Bounded on both sides on purpose: too small catches a conversion
     * that quietly became a no-op, too large catches one that ran twice or lost a unit.
     */
    @Test
    public void acoordinateInsideTheRegionIsShiftedByARealisticAmount() {
        for (final double[] point : new double[][] {
                {BEIJING_LAT, BEIJING_LON}, {SHANGHAI_LAT, SHANGHAI_LON}}) {

            final double[] shifted = CoordinateConverter.wgs84ToGcj02(point[0], point[1]);
            final double metres = roughDistanceInMetres(point[0], point[1],
                    shifted[0], shifted[1]);

            assertTrue("a coordinate inside the region moved " + Math.round(metres)
                            + "m, which is too little to be the GCJ-02 shift - the conversion"
                            + " has probably become a no-op",
                    metres > 50);
            assertTrue("a coordinate inside the region moved " + Math.round(metres)
                            + "m, far more than GCJ-02 ever shifts - the conversion has probably"
                            + " been applied twice, or a unit is wrong",
                    metres < 1_000);
        }
    }

    /**
     * <b>And the round trip comes back to where it started.</b>
     *
     * <p>The property the app depends on most, because it converts in both directions at the
     * provider boundary: out to AMap for markers and the camera, back for map clicks and
     * {@code getCameraPosition}. A round trip that drifted would move a tag every time the user
     * tapped the map.
     *
     * <p>Ten metres of tolerance because the inverse is a single-step approximation rather than
     * an exact inverse - that is how every implementation of this does it, and it is far below
     * the accuracy of the location reports themselves.
     */
    @Test
    public void theroundTripReturnsToWhereItStarted() {
        for (final double[] point : new double[][] {
                {BEIJING_LAT, BEIJING_LON}, {SHANGHAI_LAT, SHANGHAI_LON}}) {

            final double[] there = CoordinateConverter.wgs84ToGcj02(point[0], point[1]);
            final double[] back = CoordinateConverter.gcj02ToWgs84(there[0], there[1]);

            final double metres = roughDistanceInMetres(point[0], point[1], back[0], back[1]);

            assertTrue("a round trip landed " + Math.round(metres) + "m from where it started,"
                            + " so a tag would walk every time the map is tapped",
                    metres < 10);
        }
    }

    /** Converting the same point twice gives the same answer - there is nothing stateful here. */
    @Test
    public void theconversionIsDeterministic() {
        final double[] first = CoordinateConverter.wgs84ToGcj02(BEIJING_LAT, BEIJING_LON);
        final double[] second = CoordinateConverter.wgs84ToGcj02(BEIJING_LAT, BEIJING_LON);

        assertEquals(first[0], second[0], 0.0);
        assertEquals(first[1], second[1], 0.0);
    }

    // --- everywhere else --------------------------------------------------------------------

    /**
     * <b>Outside the region, nothing is touched at all.</b>
     *
     * <p>The case that matters to almost every user of this app. The shift is a property of
     * Chinese survey law and applies nowhere else, so applying it to Amsterdam or New York would
     * move a tag several hundred metres for no reason - and only for people who had chosen AMap,
     * which is nobody who would expect a Chinese coordinate system to affect them.
     *
     * <p>Asserted as exactly equal, not approximately: the correct behaviour is to return the
     * input, and "close enough" would hide a shift that had started leaking out of the region.
     */
    @Test
    public void acoordinateOutsideTheRegionIsReturnedUntouched() {
        for (final double[] point : new double[][] {
                {AMSTERDAM_LAT, AMSTERDAM_LON}, {NEW_YORK_LAT, NEW_YORK_LON}}) {

            final double[] out = CoordinateConverter.wgs84ToGcj02(point[0], point[1]);
            assertEquals("latitude was shifted outside the covered region",
                    point[0], out[0], 0.0);
            assertEquals("longitude was shifted outside the covered region",
                    point[1], out[1], 0.0);

            final double[] back = CoordinateConverter.gcj02ToWgs84(point[0], point[1]);
            assertEquals(point[0], back[0], 0.0);
            assertEquals(point[1], back[1], 0.0);
        }
    }

    /**
     * <b>The region is a bounding box, and these are its edges.</b>
     *
     * <p>Pinned because the box is a crude rectangle - it takes in a good deal of Mongolia and
     * the sea - and somebody tightening it later would silently change which coordinates get
     * shifted. That is a correctness change for real users, not a tidy-up, so it should have to
     * come past a red test.
     */
    @Test
    public void theregionIsTheBoundingBoxItClaimsToBe() {
        // Just inside each edge: shifted.
        assertTrue("a point just inside the western edge was not shifted",
                isShifted(30.0, 72.1));
        assertTrue("a point just inside the eastern edge was not shifted",
                isShifted(30.0, 137.8));
        assertTrue("a point just inside the southern edge was not shifted",
                isShifted(0.9, 100.0));
        assertTrue("a point just inside the northern edge was not shifted",
                isShifted(55.8, 100.0));

        // Just outside: untouched.
        assertTrue("a point west of the region was shifted anyway", !isShifted(30.0, 71.9));
        assertTrue("a point east of the region was shifted anyway", !isShifted(30.0, 137.9));
        assertTrue("a point south of the region was shifted anyway", !isShifted(0.8, 100.0));
        assertTrue("a point north of the region was shifted anyway", !isShifted(55.9, 100.0));
    }

    // --- helpers ----------------------------------------------------------------------------

    private static boolean isShifted(final double latitude, final double longitude) {
        final double[] out = CoordinateConverter.wgs84ToGcj02(latitude, longitude);
        return out[0] != latitude || out[1] != longitude;
    }

    /**
     * Distance in metres, flat-earth style.
     *
     * <p>Good to a fraction of a percent over the hundreds of metres being measured here, and
     * this is a sanity bound rather than a survey.
     */
    private static double roughDistanceInMetres(
            final double lat1, final double lon1, final double lat2, final double lon2) {

        final double dLat = (lat2 - lat1) * METRES_PER_DEGREE;
        final double dLon = (lon2 - lon1) * METRES_PER_DEGREE
                * Math.cos(Math.toRadians((lat1 + lat2) / 2));

        return Math.sqrt((dLat * dLat) + (dLon * dLon));
    }
}
