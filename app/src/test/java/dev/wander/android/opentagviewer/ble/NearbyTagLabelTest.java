package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import dev.wander.android.opentagviewer.R;

/** A JVM test: {@link NearbyTagLabel} chooses resources without touching a {@code Context}. */
public class NearbyTagLabelTest {

    @Test
    public void shortBatteryLabelPicksTheMatchingWord() {
        assertEquals(R.string.battery_short_full,
                NearbyTagLabel.shortBatteryLabel(FindMyAdvertisement.BatteryLevel.FULL));
        assertEquals(R.string.battery_short_medium,
                NearbyTagLabel.shortBatteryLabel(FindMyAdvertisement.BatteryLevel.MEDIUM));
        assertEquals(R.string.battery_short_low,
                NearbyTagLabel.shortBatteryLabel(FindMyAdvertisement.BatteryLevel.LOW));
        assertEquals(R.string.battery_short_very_low,
                NearbyTagLabel.shortBatteryLabel(FindMyAdvertisement.BatteryLevel.VERY_LOW));
    }

    @Test
    public void aStrongSignalIsStrong() {
        assertEquals(R.string.signal_strength_strong, NearbyTagLabel.signalStrengthLabel(-50));
        assertEquals(R.string.signal_strength_strong, NearbyTagLabel.signalStrengthLabel(-60));
    }

    @Test
    public void aMidRangeSignalIsMedium() {
        assertEquals(R.string.signal_strength_medium, NearbyTagLabel.signalStrengthLabel(-61));
        assertEquals(R.string.signal_strength_medium, NearbyTagLabel.signalStrengthLabel(-75));
    }

    @Test
    public void aFaintSignalIsWeak() {
        assertEquals(R.string.signal_strength_weak, NearbyTagLabel.signalStrengthLabel(-76));
        assertEquals(R.string.signal_strength_weak, NearbyTagLabel.signalStrengthLabel(-95));
    }

    @Test
    public void aStrongerReadingNeverRanksBelowAWeakerOne() {
        // The whole point of showing this at all: as a reading improves while someone moves,
        // the label must not go backwards.
        final int[] fromWeakToStrong = {-95, -80, -75, -70, -60, -50};
        int previousRank = -1;
        for (final int rssi : fromWeakToStrong) {
            final int rank = rankOf(NearbyTagLabel.signalStrengthLabel(rssi));
            org.junit.Assert.assertTrue(
                    "rssi=" + rssi + " ranked below a weaker reading", rank >= previousRank);
            previousRank = rank;
        }
    }

    private static int rankOf(final int stringRes) {
        if (stringRes == R.string.signal_strength_weak) {
            return 0;
        }
        if (stringRes == R.string.signal_strength_medium) {
            return 1;
        }
        return 2;
    }
}
