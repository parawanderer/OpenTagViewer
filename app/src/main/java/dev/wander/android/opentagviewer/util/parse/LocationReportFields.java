package dev.wander.android.opentagviewer.util.parse;

import androidx.annotation.NonNull;

import java.util.Locale;

import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The three raw numbers on a location report, rendered for the debug toggle.
 *
 * <p>A row used to read {@code status:144, conf:0, acc:83} and stop there, which is true and tells
 * nobody anything. This adds what can be cited and deliberately stops short of what cannot - the
 * line between those two is the point of this class, so it is drawn explicitly below.
 *
 * <p>The source throughout is Heinrich, Stute, Kornhuber and Hollick, <i>Who Can Find My Devices?
 * Security and Privacy of Apple's Crowd-Sourced Bluetooth Location Tracking System</i>, PoPETs
 * 2021(3), arXiv:2103.02282 - the SEEMOO paper that reverse-engineered offline finding. Section
 * numbers below are that paper's.
 *
 * <h2>Where the fields come from</h2>
 *
 * <p>Fig. 2 of the paper gives the report layout, and the pinned FindMy.py parses exactly it:
 *
 * <pre>
 *   Timestamp   Confidence   Ephemeral public key   Encrypted location   AES-GCM tag
 *    4 bytes      1 byte           57 bytes              10 bytes          16 bytes
 *                                                            |
 *                              Latitude 4 | Longitude 4 | Horizontal accuracy 1 | Status 1
 * </pre>
 *
 * <p>Those add to 88, which is the payload length {@code findmy/reports/reports.py} branches on.
 * A second, longer format exists - macOS 14 added a byte, and the parser drops it - so the byte
 * offsets are worth re-reading if the pin ever moves.
 *
 * <p><b>Confidence sits outside the encryption.</b> It is ahead of the ephemeral key in the
 * figure, and the AES-GCM tag covers only the ten encrypted bytes; the parser agrees, deriving the
 * key from {@code encrypted_data[1:58]} and authenticating {@code [58:]} while confidence is
 * {@code encrypted_data[0]}. So Apple's server could return any value in that byte and nothing
 * here or anywhere else would detect it. Accuracy and status are inside the tag, so those two
 * genuinely came from whichever iPhone heard the tag.
 *
 * <h2>Horizontal accuracy: metres, and not the metres people assume</h2>
 *
 * <p>The paper is careful about the unit rather than assertive, and this note copies that: "We
 * assume that the accuracy value is encoded in metric meters as it matches the experimentally
 * determined positioning error of the coordinates in the location reports" (§ 6.3, footnote 3).
 *
 * <p><b>It describes the finder's own position, not the tag's.</b> Some stranger's iPhone reports
 * how well it knew where <i>it</i> was when it overheard the beacon. It says nothing about how far
 * the tag was from that phone.
 *
 * <p>And § 7.1 measured the byte against a GPS ground truth, which is worth knowing before
 * trusting it (Table 6, mean distance in metres):
 *
 * <table>
 *   <tr><th>Scenario</th><th>Reported</th><th>Actually</th></tr>
 *   <tr><td>Walking</td><td>121.9</td><td>81.4</td></tr>
 *   <tr><td>Restaurant</td><td>117.2</td><td>60.2</td></tr>
 *   <tr><td>Train</td><td>171.0</td><td>440.7</td></tr>
 *   <tr><td>Car</td><td>145.2</td><td>580.7</td></tr>
 * </table>
 *
 * <p>So it is pessimistic for a tag sitting still and <b>badly optimistic for one in motion</b> -
 * 145 m claimed where the truth was 581 m. It is a hint, not an error bar, and drawing it as a
 * confident radius on a map would be a lie in exactly the case a user cares most about.
 *
 * <p>Being one byte, it also saturates: 255 means "255 or worse".
 *
 * <h2>Status: decoded only when the byte says it can be</h2>
 *
 * <p>The accessory's own status byte - byte 12 of its BLE advertisement (paper, Table 2), copied
 * through by the finder. The paper labels it {@code Status (e.g., battery level)} and defers to
 * Apple's <i>Find My Network Accessory Specification</i>. That document does define it, in
 * Table 5-5, "Payload for separated state", byte 2:
 *
 * <pre>
 *   Bits 0-1: Reserved.
 *   Bit 2:    Maintained
 *   Bits 3-4: Reserved
 *   Bits 5:   0b1
 *   Bits 6-7: Battery state.
 *
 *   Maintained: Set if owner connected within current key rotation period (15 minutes)
 *   Battery state definition: 0 = Full, 1 = Medium, 2 = Low, 3 = Critically low
 * </pre>
 *
 * <p>Which settles it for accessories that follow the specification, and disposes of the bitmasks
 * in circulation. {@code go-haystack} is half right - its {@code 0x40}/{@code 0x80}/{@code 0xC0}
 * are exactly medium/low/critical shifted into bits 6-7, and its {@code 0x10} for "full" should
 * have been {@code 0x20}. The tables a chatbot will produce - {@code 0x80} paired, {@code 0x02}
 * sound playing, {@code 0x04} motion detected - are invention. That is why this class exists.
 *
 * <p><b>But an AirTag does not follow that table, and this app mostly sees AirTags.</b> The value
 * it observes is {@code 0x90}: bit 5 clear where the specification requires it set, and reserved
 * bit 4 set. Adam Catley's teardown records a real AirTag advertising {@code 0x10}, which breaks
 * the same two rules. The specification governs third-party MFi accessories; AirTag is Apple's own
 * hardware and predates it. Decoding {@code 0x90} against Table 5-5 anyway yields "battery Low"
 * for a tag whose own record reads Full - a confident, wrong answer, which is worse than none.
 *
 * <p><b>And the byte is not trustworthy even when it is well-formed.</b> Caesar Creek Software's
 * write-up of this network puts it plainly: "it's supposed to indicate the battery level and
 * device type, but the user can actually set it to whatever they want". They also record that
 * marking a beacon as an Apple Device or Find My Device <i>through this byte</i> suppresses
 * unwanted-tracking alerts - so those "Reserved" bits carry a device type in practice, and a
 * beacon has an active reason to lie about them.
 *
 * <p>So {@link #status(long)} decodes only a byte that actually conforms to Table 5-5 - bit 5 set
 * and every reserved bit clear - and otherwise shows the number alone. A conforming byte is
 * annotated as what the beacon <i>claimed</i>, never as a measurement. Every value carries decimal,
 * hex and binary regardless, because those three are certainly right and the binary is what
 * somebody correlating this against their own tags actually needs.
 *
 * <p>Not translated, matching the rest of the debug output - a translated bit pattern helps
 * nobody.
 *
 * <h2>Sources</h2>
 *
 * <p>Linked because every one of them was hard to find, and because the next person to doubt a
 * sentence above should be able to check it rather than re-derive it:
 *
 * <ul>
 *   <li>Heinrich, Stute, Kornhuber and Hollick, <i>Who Can Find My Devices?</i>, PoPETs 2021(3) -
 *       <a href="https://arxiv.org/abs/2103.02282">arXiv:2103.02282</a>,
 *       <a href="https://petsymposium.org/popets/2021/popets-2021-0045.pdf">PDF</a>. Fig. 2 for
 *       the report layout, Table 2 for the advertisement, § 6.3 fn. 3 for the accuracy unit,
 *       Table 6 for its measured error.</li>
 *   <li>Apple, <i>Find My Network Accessory Specification</i>, release R2, 2022-05-17 -
 *       <a href="https://www.scribd.com/document/809845350/Find-My-Network-Accessory-Specification-r-2">
 *       mirror</a>. Table 5-5 for the status byte. The primary source, and MFi-gated, so the
 *       mirror is the only way most people will read it.</li>
 *   <li>Caesar Creek Software, <i>Find My and Find Hub Network Research</i> -
 *       <a href="https://cc-sw.com/find-my-and-find-hub-network-research/">cc-sw.com</a>. That the
 *       byte is beacon-controlled, and that a device type in it suppresses stalking alerts.</li>
 *   <li>Adam Catley, <i>AirTag Reverse Engineering</i> -
 *       <a href="https://adamcatley.com/AirTag.html#advertising-data">adamcatley.com</a>. A real
 *       AirTag observed advertising {@code 0x10}.</li>
 *   <li><a href="https://pkg.go.dev/github.com/HattoriHanzo031/go-haystack/lib/findmy">go-haystack</a>
 *       - the half-right battery constants, kept here as the example of what this class is for.</li>
 * </ul>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LocationReportFields {

    /** One byte, so this is the ceiling rather than a reading. */
    static final long ACCURACY_SATURATES_AT = 255;

    /** The two lines under a history row when the debug toggle is on. */
    @NonNull
    public static String debugText(@NonNull final BeaconLocationReport report) {
        return String.format(
                Locale.ROOT,
                "%.6f,%.6f · %s%n%s · %s · %s",
                report.getLatitude(),
                report.getLongitude(),
                report.getDescription(),
                accuracy(report.getHorizontalAccuracy()),
                confidence(report.getConfidence()),
                status(report.getStatus()));
    }

    /**
     * {@code "acc 83 m"}, or {@code "acc ≥255 m"} at the ceiling.
     *
     * <p>The unit is spelled out because a bare number invites being read as a distance to the
     * tag, which it is not.
     */
    @NonNull
    public static String accuracy(final long metres) {
        if (metres >= ACCURACY_SATURATES_AT) {
            return "acc ≥" + ACCURACY_SATURATES_AT + " m";
        }
        return "acc " + metres + " m";
    }

    /** {@code "conf 0"}. Left bare - see the note above on it being unauthenticated. */
    @NonNull
    public static String confidence(final long value) {
        return "conf " + value;
    }

    /** Table 5-5 requires this set. An AirTag leaves it clear, which is how they are told apart. */
    private static final long SPEC_MARKER_BIT = 0b0010_0000;

    /** Bits 0-1 and 3-4. Publicly "Reserved"; a device type rides in them in practice. */
    private static final long SPEC_RESERVED_BITS = 0b0001_1011;

    /** Bit 2: the owner device connected within the current 15-minute key rotation period. */
    private static final long SPEC_MAINTAINED_BIT = 0b0000_0100;

    /** Bits 6-7, as an index into {@link #SPEC_BATTERY_STATES}. */
    private static final int SPEC_BATTERY_SHIFT = 6;

    /** Apple's words, in Apple's order (Table 5-5). */
    private static final String[] SPEC_BATTERY_STATES = {"full", "medium", "low", "critically low"};

    /**
     * {@code "status 144 = 0x90 = 0b10010000"}, plus a reading when the byte earns one.
     *
     * <p>Decimal, hex and binary always: three renderings of one number, every one certainly true,
     * and the binary is what somebody correlating bits against their own tags needs.
     *
     * <p><b>The Table 5-5 reading is appended only to a byte that conforms to Table 5-5</b> - the
     * marker bit set and every reserved bit clear. An AirTag satisfies neither, so the value this
     * app usually sees stays a bare number rather than being told it has a low battery when it
     * does not. And a conforming byte is phrased as a claim, because a beacon sets this field
     * itself and can put anything in it.
     *
     * <p>A value too wide for a byte is shown at its natural width rather than truncated to eight
     * bits: it would mean an assumption is wrong somewhere upstream, and hiding the evidence would
     * be the worst response to that.
     */
    @NonNull
    public static String status(final long value) {
        if (value < 0) {
            return "status " + value;
        }

        final String bits = Long.toBinaryString(value);
        final String padded = value <= 0xFF
                ? "00000000".substring(bits.length()) + bits
                : bits;

        return String.format(Locale.ROOT, "status %d = 0x%02X = 0b%s%s",
                value, value, padded, specReading(value));
    }

    /** {@code " (claims battery full, maintained)"}, or empty when the byte does not conform. */
    @NonNull
    private static String specReading(final long value) {
        final boolean conforms = value <= 0xFF
                && (value & SPEC_MARKER_BIT) != 0
                && (value & SPEC_RESERVED_BITS) == 0;

        if (!conforms) {
            return "";
        }

        final String battery = SPEC_BATTERY_STATES[(int) (value >> SPEC_BATTERY_SHIFT) & 0b11];
        final String maintained = (value & SPEC_MAINTAINED_BIT) != 0
                ? "maintained" : "not maintained";

        return " (claims battery " + battery + ", " + maintained + ")";
    }
}
