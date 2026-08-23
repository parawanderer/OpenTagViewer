package dev.wander.android.opentagviewer.ui.history;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.location.Address;

import org.junit.Test;

import java.util.List;
import java.util.Locale;

/**
 * A history row for somewhere the geocoder cannot name.
 *
 * <p><b>Issue #41, which crashed on the second look and never the first.</b> An empty list is what
 * the geocoder returns when it has no answer - mid-ocean, a device with no geocoding backend, a
 * lookup that failed - and it was cached like any other result. The asynchronous path checked for
 * it; the cache-hit path did not, and read element zero. So the row rendered fine when it was
 * first bound, the empty answer went into the cache, and scrolling away and back threw
 * {@code IndexOutOfBoundsException: Index: 0}.
 *
 * <p>That sequence is why one person hit it and nobody could reproduce it: it needs a point with
 * no address <i>and</i> a re-bind of that row.
 *
 * <p>On the JVM, because none of this needs a device - it is a map, a list and a guard. Rule 13.
 */
public class APointWithNoAddressTest {

    /** Somewhere unlikely to collide with another test's cache entry. */
    private static final double LATITUDE = 41.1234;
    private static final double LONGITUDE = -71.5678;

    /**
     * <b>The crash itself: an empty result must not be read for its first element.</b>
     */
    @Test
    public void nothingIsReadOutOfAnEmptyResult() {
        assertNull("an empty geocode has no name in it, and asking for one used to throw",
                HistoryItemsAdapter.convertLocationToUIName(List.of()));
    }

    /**
     * <b>And an empty result is never served from the cache as though it were an answer.</b>
     *
     * <p>This is the half that makes the crash reachable. Guarding the reader alone would stop the
     * exception and leave the row permanently showing raw coordinates for a point the geocoder
     * might well name a minute later, because the failure would stay cached for the life of the
     * process.
     */
    @Test
    public void anemptyResultIsAMissRatherThanAnAnswer() {
        HistoryItemsAdapter.cacheGeocodeForTest(LATITUDE, LONGITUDE, List.of());

        assertNull("an empty cached result must read as 'not looked up yet', so the point is"
                        + " tried again rather than being named nothing forever",
                HistoryItemsAdapter.getCachedGeocode(LATITUDE, LONGITUDE));
    }

    /**
     * A real answer is still cached and still returned, which is the point of having a cache.
     *
     * <p>Paired with the case above deliberately: a fix that made {@code getCachedGeocode} always
     * return null would pass that test and disable geocoding entirely.
     */
    @Test
    public void arealResultIsStillServedFromTheCache() {
        final List<Address> found = List.of(new Address(Locale.UK));
        HistoryItemsAdapter.cacheGeocodeForTest(LATITUDE + 1, LONGITUDE + 1, found);

        assertSame("a cached address must still come back, or nothing is being cached at all",
                found, HistoryItemsAdapter.getCachedGeocode(LATITUDE + 1, LONGITUDE + 1));
    }

    /** A point nothing has looked up is a miss too, rather than an empty list. */
    @Test
    public void anunknownPointIsAMiss() {
        assertNull(HistoryItemsAdapter.getCachedGeocode(12.3456, 65.4321));
    }
}
