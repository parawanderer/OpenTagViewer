package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A JVM test: {@link RssiDistance} is pure math, deliberately. */
public class RssiDistanceTest {

    @Test
    public void theReferenceRssiEstimatesAboutOneMetre() {
        assertEquals(1.0, RssiDistance.estimateMetres(-59), 0.001);
    }

    @Test
    public void aWeakerSignalEstimatesFurtherAway() {
        final double closer = RssiDistance.estimateMetres(-59);
        final double further = RssiDistance.estimateMetres(-79);

        assertTrue("a 20 dB weaker signal must estimate a larger distance",
                further > closer);
    }

    @Test
    public void aStrongerSignalEstimatesCloser() {
        final double atReference = RssiDistance.estimateMetres(-59);
        final double stronger = RssiDistance.estimateMetres(-39);

        assertTrue("a 20 dB stronger signal must estimate a smaller distance",
                stronger < atReference);
    }

    @Test
    public void tenDbWeakerRoughlyTriplesTheEstimate() {
        // The model is 10^((ref - rssi) / (10 * n)) with n = 2, so a 10 dB step is a factor of
        // 10^0.5 ~= 3.16 - checked here rather than assumed, since it is the whole shape of the
        // curve a caller sees as "further away".
        final double atReference = RssiDistance.estimateMetres(-59);
        final double tenDbWeaker = RssiDistance.estimateMetres(-69);

        assertEquals(Math.sqrt(10), tenDbWeaker / atReference, 0.01);
    }
}
