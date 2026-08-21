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
     * <b>The value this app actually sees is never given a battery reading.</b>
     *
     * <p>The test that matters. Apple's Table 5-5 does define this byte, but an AirTag does not
     * follow it - {@code 0x90} has the marker bit clear and a reserved bit set. Decoded anyway it
     * comes out as "battery low", for a tag whose own record says full. A confident wrong answer
     * is worse than no answer, and this is what stops one being reintroduced.
     */
    @Test
    public void anairTagsStatusIsNotDecodedAsABatteryLevel() {
        final String described = LocationReportFields.status(0x90).toLowerCase(Locale.ROOT);

        for (final String label : new String[] {
                "full", "medium", "low", "critical", "battery", "maintained"}) {

            assertFalse("0x90 does not conform to Table 5-5 - marker bit clear, reserved bit 4 "
                            + "set - so it must not be labelled '" + label + "': " + described,
                    described.contains(label));
        }
    }

    /**
     * Only a byte that conforms to Table 5-5 gets a reading at all.
     *
     * <p>Swept across the whole range rather than spot-checked, because the interesting failure is
     * a decode leaking onto some value nobody thought about.
     */
    @Test
    public void onlyaconformingByteIsGivenAReading() {
        for (long value = 0; value <= 0xFF; value++) {
            final boolean conforms = (value & 0b0010_0000) != 0 && (value & 0b0001_1011) == 0;
            final boolean read = LocationReportFields.status(value).contains("claims");

            assertEquals("status 0x" + Long.toHexString(value) + " conformance vs reading",
                    conforms, read);
        }
    }

    /**
     * A conforming byte is read out per Apple's Table 5-5.
     *
     * <p>Bits 6-7 are the battery state (0 full, 1 medium, 2 low, 3 critically low), bit 2 is
     * "maintained" - the owner device connected within the current 15-minute key rotation period.
     */
    @Test
    public void aconformingByteIsReadPerTheSpecification() {
        assertTrue(LocationReportFields.status(0x20), // 0b00100000
                LocationReportFields.status(0x20).endsWith("(claims battery full, not maintained)"));
        assertTrue(LocationReportFields.status(0x24), // 0b00100100
                LocationReportFields.status(0x24).endsWith("(claims battery full, maintained)"));
        assertTrue(LocationReportFields.status(0x60), // 0b01100000
                LocationReportFields.status(0x60).endsWith("(claims battery medium, not maintained)"));
        assertTrue(LocationReportFields.status(0xA0), // 0b10100000
                LocationReportFields.status(0xA0).endsWith("(claims battery low, not maintained)"));
        assertTrue(LocationReportFields.status(0xE4), // 0b11100100
                LocationReportFields.status(0xE4).endsWith("(claims battery critically low, maintained)"));
    }

    /**
     * <b>It says "claims", because the beacon chooses this byte.</b>
     *
     * <p>Caesar Creek's write-up: the user "can actually set it to whatever they want", and a
     * device type set in these bits suppresses unwanted-tracking alerts - so a beacon has an active
     * reason to lie here. The wording is the only thing standing between a reader and treating this
     * as a measurement.
     */
    @Test
    public void areadingIsWordedAsAClaimNotAMeasurement() {
        assertTrue(LocationReportFields.status(0x24),
                LocationReportFields.status(0x24).contains("claims"));
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
