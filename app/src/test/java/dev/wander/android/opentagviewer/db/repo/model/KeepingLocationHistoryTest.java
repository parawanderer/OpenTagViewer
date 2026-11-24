package dev.wander.android.opentagviewer.db.repo.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static dev.wander.android.opentagviewer.db.repo.model.UserSettings.HISTORY_RETENTION_CHOICES_DAYS;
import static dev.wander.android.opentagviewer.db.repo.model.UserSettings.HISTORY_RETENTION_FOREVER;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import dev.wander.android.opentagviewer.db.repo.LocationHistoryRetention;

/**
 * How long location history is kept, and what that means for the cutoff.
 *
 * <p>The default has to be "keep everything": until this setting existed the app never deleted a
 * report, so anyone updating must not lose history to a setting they never saw.
 */
public class KeepingLocationHistoryTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void nobodyHasChosenSoEverythingIsKept() {
        final UserSettings settings = UserSettings.builder().build();
        assertEquals(HISTORY_RETENTION_FOREVER, settings.resolveHistoryRetentionDays());
        assertTrue(settings.keepsHistoryForever());
    }

    @Test
    public void zeroAndNegativeMeanForever() {
        assertEquals(HISTORY_RETENTION_FOREVER, resolve(0));
        assertEquals(HISTORY_RETENTION_FOREVER, resolve(-5));
    }

    @Test
    public void everyChoiceResolvesToItself() {
        for (final int choice : HISTORY_RETENTION_CHOICES_DAYS) {
            assertEquals(choice, resolve(choice));
        }
    }

    @Test
    public void theChoicesAreForeverThenAYearDownToAMonth() {
        assertEquals(HISTORY_RETENTION_FOREVER, (int) HISTORY_RETENTION_CHOICES_DAYS.get(0));
        // Each is the longest that period can be on a calendar, so "a month" never keeps less.
        assertEquals(List.of(0, 366, 184, 92, 31), HISTORY_RETENTION_CHOICES_DAYS);
    }

    /** A value from somewhere else (a later version, say) keeps at least what it asked for. */
    @Test
    public void anUnknownPeriodRoundsUpToTheNextChoiceNeverDown() {
        assertEquals(31, resolve(1));
        assertEquals(31, resolve(7));
        assertEquals(92, resolve(45));
        assertEquals(366, resolve(200));
        assertEquals(HISTORY_RETENTION_FOREVER, resolve(400));
        assertFalse(UserSettings.builder().historyRetentionDays(45).build().keepsHistoryForever());
    }

    @Test
    public void foreverHasNoCutoff() {
        assertEquals(LocationHistoryRetention.NO_CUTOFF,
                LocationHistoryRetention.cutoffFor(HISTORY_RETENTION_FOREVER, NOW));
        assertEquals(LocationHistoryRetention.NO_CUTOFF,
                LocationHistoryRetention.cutoffFor(-1, NOW));
    }

    @Test
    public void aPeriodCutsOffThatManyWholeDaysAgo() {
        assertEquals(NOW - TimeUnit.DAYS.toMillis(31),
                LocationHistoryRetention.cutoffFor(31, NOW));
        assertEquals(NOW - TimeUnit.DAYS.toMillis(366),
                LocationHistoryRetention.cutoffFor(366, NOW));
    }

    @Test
    public void theCutoffUsesTheRoundedUpPeriod() {
        assertEquals(NOW - TimeUnit.DAYS.toMillis(92),
                LocationHistoryRetention.cutoffFor(45, NOW));
    }

    private static int resolve(final int days) {
        return UserSettings.builder().historyRetentionDays(days).build()
                .resolveHistoryRetentionDays();
    }
}
