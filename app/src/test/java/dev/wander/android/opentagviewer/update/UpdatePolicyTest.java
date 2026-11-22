package dev.wander.android.opentagviewer.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** When to ask GitHub, and when the map should say what it answered. */
public class UpdatePolicyTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = UpdatePolicy.CHECK_EVERY_MS;

    @Test
    public void nothingIsAskedUnlessTheUserOptedIn() {
        assertFalse(UpdatePolicy.checkIsDue(null, 0L, NOW));
        assertFalse(UpdatePolicy.checkIsDue(false, 0L, NOW));
        assertTrue(UpdatePolicy.checkIsDue(true, 0L, NOW));
    }

    @Test
    public void atMostOnceADay() {
        assertFalse(UpdatePolicy.checkIsDue(true, NOW - DAY + 1, NOW));
        assertTrue(UpdatePolicy.checkIsDue(true, NOW - DAY, NOW));
    }

    @Test
    public void aClockSetBackwardsDoesNotStopTheChecks() {
        assertTrue(UpdatePolicy.checkIsDue(true, NOW + DAY, NOW));
    }

    @Test
    public void theSettingsRowSaysWhereThingsStand() {
        assertEquals(UpdatePolicy.RowState.OFF, UpdatePolicy.rowState(null, 0, null, "1.1.2"));
        assertEquals(UpdatePolicy.RowState.OFF, UpdatePolicy.rowState(false, NOW, "1.1.2", "1.1.2"));
        assertEquals(UpdatePolicy.RowState.NOT_CHECKED, UpdatePolicy.rowState(true, 0, null, "1.1.2"));
        assertEquals(UpdatePolicy.RowState.UP_TO_DATE,
                UpdatePolicy.rowState(true, NOW, "1.1.2", "1.1.2-debug"));
        assertEquals(UpdatePolicy.RowState.AVAILABLE,
                UpdatePolicy.rowState(true, NOW, "1.2.0", "1.1.2"));
        assertEquals("still true with checks turned off since", UpdatePolicy.RowState.AVAILABLE,
                UpdatePolicy.rowState(false, NOW, "1.2.0", "1.1.2"));
    }

    @Test
    public void theMapOffersANewerVersionOnce() {
        assertTrue(UpdatePolicy.offerOnTheMap(true, "1.2.0", "1.1.2", null));
        assertFalse("dismissed", UpdatePolicy.offerOnTheMap(true, "1.2.0", "1.1.2", "1.2.0"));
        assertTrue("the next one is offered again",
                UpdatePolicy.offerOnTheMap(true, "1.3.0", "1.1.2", "1.2.0"));
    }

    @Test
    public void theMapOffersNothingItShouldNot() {
        assertFalse("up to date", UpdatePolicy.offerOnTheMap(true, "1.1.2", "1.1.2", null));
        assertFalse("never checked", UpdatePolicy.offerOnTheMap(true, null, "1.1.2", null));
        assertFalse("turned off since", UpdatePolicy.offerOnTheMap(false, "1.2.0", "1.1.2", null));
    }
}
