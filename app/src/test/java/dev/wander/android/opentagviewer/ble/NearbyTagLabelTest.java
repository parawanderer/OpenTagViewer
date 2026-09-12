package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
    public void aStrongSignalFillsAllFiveDots() {
        assertEquals(5, NearbyTagLabel.signalStrengthLevel(-50));
        assertEquals("●●●●●", NearbyTagLabel.signalStrengthBars(-50));
    }

    @Test
    public void aFaintSignalFillsOnlyOneDot() {
        assertEquals(1, NearbyTagLabel.signalStrengthLevel(-95));
        assertEquals("●○○○○", NearbyTagLabel.signalStrengthBars(-95));
    }

    @Test
    public void neverFillsZeroDots() {
        // A sighting existing at all means some signal was heard, however faint.
        assertEquals(1, NearbyTagLabel.signalStrengthLevel(-200));
    }

    @Test
    public void barsAlwaysHaveFiveDotsTotal() {
        for (int rssi = -100; rssi <= -40; rssi++) {
            assertEquals("rssi=" + rssi, 5, NearbyTagLabel.signalStrengthBars(rssi).length());
        }
    }

    @Test
    public void aStrongerReadingNeverRanksBelowAWeakerOne() {
        // The whole point of showing this at all: as a reading improves while someone moves,
        // the dot count must not go backwards.
        final int[] fromWeakToStrong = {-95, -85, -84, -75, -74, -65, -64, -55, -54, -50};
        int previousLevel = 0;
        for (final int rssi : fromWeakToStrong) {
            final int level = NearbyTagLabel.signalStrengthLevel(rssi);
            assertTrue("rssi=" + rssi + " ranked below a weaker reading", level >= previousLevel);
            previousLevel = level;
        }
    }
}
