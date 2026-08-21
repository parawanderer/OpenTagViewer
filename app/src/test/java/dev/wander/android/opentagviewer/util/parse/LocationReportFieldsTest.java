package dev.wander.android.opentagviewer.util.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;

/**
 * The debug rendering of a report's three raw numbers.
 *
 * <p>Most of these guard against saying <i>more</i> than is known rather than less. The bit
 * meanings of the status byte are not publicly established - the SEEMOO paper labels the byte and
 * defers to Apple's MFi-gated spec - so the tests below pin that no label is ever attached to one,
 * which is the thing a well-meaning future change would break.
 */
public class LocationReportFieldsTest {

    private static BeaconLocationReport report(
            final long accuracy, final long confidence, final long status) {

        return BeaconLocationReport.builder()
                .latitude(52.370216)
                .longitude(4.895168)
                .description("Wi-Fi")
                .horizontalAccuracy(accuracy)
                .confidence(confidence)
                .status(status)
                .build();
    }

    /** The value this app actually sees, rendered three ways. */
    @Test
    public void thestatusByteIsShownAsDecimalHexAndBinary() {
        assertEquals("status 144 = 0x90 = 0b10010000",
                LocationReportFields.status(144));
    }

    /**
     * <b>Binary is padded to a full byte.</b>
     *
     * <p>Without it {@code 0b10000} and {@code 0b10010000} are hard to tell apart at a glance, and
     * lining the rows up is the entire reason somebody turns this on.
     */
    @Test
    public void binaryIsPaddedToEightBits() {
        assertEquals("status 0 = 0x00 = 0b00000000", LocationReportFields.status(0));
        assertEquals("status 16 = 0x10 = 0b00010000", LocationReportFields.status(16));
        assertEquals("status 255 = 0xFF = 0b11111111", LocationReportFields.status(255));
    }

    /**
     * A value too wide for a byte is shown whole, not truncated.
     *
     * <p>It would mean an assumption is wrong somewhere upstream, and the debug panel is the last
     * place that evidence should be quietly discarded.
     */
    @Test
    public void awiderValueIsNotTruncatedToAByte() {
        final String described = LocationReportFields.status(0x1234);

        assertTrue(described, described.contains("4660"));
        assertTrue(described, described.contains("0x1234"));
        assertTrue(described, described.contains("0b1001000110100"));
    }

    /** A negative can only come from a decoding bug; show it rather than dress it up. */
    @Test
    public void anegativeStatusIsShownPlainly() {
        assertEquals("status -1", LocationReportFields.status(-1));
    }

    /**
     * <b>No status bit is ever given a name.</b>
     *
     * <p>The test that matters. Every circulating bitmask for this byte contradicts another one,
     * and {@code 0x90} would be "battery low" and "battery full" simultaneously under the most
     * commonly repeated one. Anybody adding a label here needs a source, and this failing is how
     * they find out.
     */
    @Test
    public void nobitIsGivenAMeaning() {
        final String[] claimsNobodyHasEstablished = {
                "full", "medium", "low", "critical", "battery",
                "paired", "registered", "nominal", "motion", "sound", "maintained", "separated"};

        for (long value = 0; value <= 0xFF; value++) {
            final String described = LocationReportFields.status(value).toLowerCase(Locale.ROOT);

            for (final String claim : claimsNobodyHasEstablished) {
                assertFalse(
                        "status " + value + " was labelled '" + claim + "'. The bit meanings of "
                                + "this byte are not publicly documented - see the class javadoc "
                                + "before adding one.",
                        described.contains(claim));
            }
        }
    }

    /** Accuracy carries its unit, because a bare number reads as a distance to the tag. */
    @Test
    public void accuracyIsLabelledInMetres() {
        assertEquals("acc 83 m", LocationReportFields.accuracy(83));
        assertEquals("acc 0 m", LocationReportFields.accuracy(0));
    }

    /**
     * <b>At the ceiling it says "or worse", not "255".</b>
     *
     * <p>It is one byte. A tag reported from a very rough fix pins there, and rendering that as an
     * exact 255 m invents a precision the format cannot express.
     */
    @Test
    public void accuracySaysWhenItHasSaturated() {
        assertEquals("acc ≥255 m", LocationReportFields.accuracy(255));
        assertEquals("a value over the ceiling still reads as saturated",
                "acc ≥255 m", LocationReportFields.accuracy(300));
    }

    /** Confidence is passed through untouched - nothing is known about its scale. */
    @Test
    public void confidenceIsShownAsGiven() {
        assertEquals("conf 0", LocationReportFields.confidence(0));
        assertEquals("conf 3", LocationReportFields.confidence(3));
    }

    /** The whole row: coordinates and description first, then the three numbers. */
    @Test
    public void thedebugRowCarriesEveryFieldd() {
        final String text = LocationReportFields.debugText(report(83, 0, 144));

        assertTrue(text, text.contains("52.370216"));
        assertTrue(text, text.contains("4.895168"));
        assertTrue(text, text.contains("Wi-Fi"));
        assertTrue(text, text.contains("acc 83 m"));
        assertTrue(text, text.contains("conf 0"));
        assertTrue(text, text.contains("0b10010000"));
    }

    /**
     * Coordinates keep six decimals regardless of locale.
     *
     * <p>{@code Locale.ROOT} rather than the default, so a device set to a comma-decimal locale
     * does not render {@code 52,370216} into text somebody will paste into a bug report.
     */
    @Test
    public void coordinatesUseADotWhateverTheDeviceLocaleIs() {
        final Locale was = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);

            assertTrue(LocationReportFields.debugText(report(83, 0, 144)).contains("52.370216"));
        } finally {
            Locale.setDefault(was);
        }
    }
}
