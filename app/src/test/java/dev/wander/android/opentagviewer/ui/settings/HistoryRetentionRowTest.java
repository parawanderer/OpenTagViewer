package dev.wander.android.opentagviewer.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static dev.wander.android.opentagviewer.db.repo.model.UserSettings.HISTORY_RETENTION_CHOICES_DAYS;
import static dev.wander.android.opentagviewer.db.repo.model.UserSettings.HISTORY_RETENTION_FOREVER;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import dev.wander.android.opentagviewer.R;

/**
 * Which changes to the history period ask before deleting, and the labels the row shows.
 *
 * <p>Only a change that deletes something somebody would otherwise have kept asks first. Asking
 * on every change teaches people to press Delete without reading.
 */
public class HistoryRetentionRowTest {

    @Test
    public void choosingAPeriodWhenEverythingIsKeptAsks() {
        for (final int chosen : HISTORY_RETENTION_CHOICES_DAYS) {
            if (chosen != HISTORY_RETENTION_FOREVER) {
                assertTrue("forever -> " + chosen,
                        HistoryRetentionRow.deletesMore(HISTORY_RETENTION_FOREVER, chosen));
            }
        }
    }

    @Test
    public void aShorterPeriodAsks() {
        assertTrue(HistoryRetentionRow.deletesMore(366, 31));
        assertTrue(HistoryRetentionRow.deletesMore(92, 31));
    }

    @Test
    public void aLongerPeriodOrForeverDoesNotAsk() {
        assertFalse(HistoryRetentionRow.deletesMore(31, 366));
        assertFalse(HistoryRetentionRow.deletesMore(31, HISTORY_RETENTION_FOREVER));
        assertFalse(HistoryRetentionRow.deletesMore(
                HISTORY_RETENTION_FOREVER, HISTORY_RETENTION_FOREVER));
    }

    @Test
    public void everyChoiceHasItsOwnLabel() {
        final Set<Integer> labels = new HashSet<>();
        for (final int choice : HISTORY_RETENTION_CHOICES_DAYS) {
            labels.add(HistoryRetentionRow.labelFor(choice));
        }
        assertEquals(HISTORY_RETENTION_CHOICES_DAYS.size(), labels.size());
    }

    @Test
    public void theLabelsLineUpWithThePeriods() {
        assertEquals(R.string.history_retention_forever,
                HistoryRetentionRow.labelFor(HISTORY_RETENTION_FOREVER));
        assertEquals(R.string.history_retention_1_year, HistoryRetentionRow.labelFor(366));
        assertEquals(R.string.history_retention_6_months, HistoryRetentionRow.labelFor(184));
        assertEquals(R.string.history_retention_3_months, HistoryRetentionRow.labelFor(92));
        assertEquals(R.string.history_retention_1_month, HistoryRetentionRow.labelFor(31));
    }
}
