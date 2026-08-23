package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Locale;

import dev.wander.android.opentagviewer.R;

/**
 * A JVM test: the unit decision is locale logic with no {@code Context} in it - see the class
 * doc on {@link NearbyDistanceLabel}.
 */
public class NearbyDistanceLabelTest {

    private static final Locale GERMANY = Locale.GERMANY;
    private static final Locale UNITED_STATES = Locale.US;
    private static final Locale UNITED_KINGDOM = Locale.UK;

    @Test
    public void mostLocalesGetMetres() {
        assertEquals(R.string.distance_metres, NearbyDistanceLabel.unitStringFor(GERMANY));
    }

    @Test
    public void theUnitedKingdomAlsoGetsMetres() {
        // Miles for road distances, but an arm's-length "how far is my tag" reading is metres
        // there same as on the continent - see the class doc on why UK is not in the feet list.
        assertEquals(R.string.distance_metres, NearbyDistanceLabel.unitStringFor(UNITED_KINGDOM));
    }

    @Test
    public void theUnitedStatesGetsFeet() {
        assertEquals(R.string.distance_feet, NearbyDistanceLabel.unitStringFor(UNITED_STATES));
    }

    @Test
    public void roundsToTheNearestMetre() {
        assertEquals(5, NearbyDistanceLabel.roundedValueFor(4.6, GERMANY));
        assertEquals(4, NearbyDistanceLabel.roundedValueFor(4.4, GERMANY));
    }

    @Test
    public void convertsToFeetForTheUnitedStates() {
        // 3 metres is just under 10 feet (9.84), so this also exercises the rounding.
        assertEquals(10, NearbyDistanceLabel.roundedValueFor(3.0, UNITED_STATES));
    }

    @Test
    public void neverRoundsDownToZero() {
        // Already a rough estimate - "~0 m" would read as a more exact claim than "~1 m" while
        // being no truer.
        assertEquals(1, NearbyDistanceLabel.roundedValueFor(0.1, GERMANY));
        assertEquals(1, NearbyDistanceLabel.roundedValueFor(0.1, UNITED_STATES));
    }
}
