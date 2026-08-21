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
 * <h2>Status: shown, not decoded</h2>
 *
 * <p>The accessory's own status byte - byte 12 of its BLE advertisement (Table 2), copied through
 * by the finder. The paper labels it {@code Status (e.g., battery level)} and stops there, citing
 * Apple's accessory specification § 5.1 for the detail. That document is MFi-gated, so <b>the bit
 * layout is not publicly established and this class does not pretend otherwise.</b>
 *
 * <p>The temptation is real, because confident-sounding tables of it circulate. They disagree:
 *
 * <ul>
 *   <li>{@code go-haystack} reads the whole byte as a battery level - {@code 0x10} full,
 *       {@code 0x40} medium, {@code 0x80} low, {@code 0xC0} critical - and calls anything else
 *       unknown, which includes every value this app has actually seen.</li>
 *   <li>Adam Catley's AirTag teardown, that library's own cited source, records a real AirTag
 *       advertising {@code 0x10} and does not decode the bits at all.</li>
 *   <li>Chatbots will readily produce a full bitmask - paired, sound playing, motion detected -
 *       and attribute it to the paper above. It is not in the paper. That is how this class came
 *       to exist.</li>
 * </ul>
 *
 * <p>Those cannot all hold. {@code 0x90} - ordinary, and the value this app sees - would be
 * {@code 0x80 | 0x10}: "low" and "full" at once. The reading that resolves it is that bits 6-7 are
 * a two-bit battery enum ({@code 00} full, {@code 01} medium, {@code 10} low, {@code 11} critical)
 * and {@code 0x10} is a different bit that happens to always be set, which would also line up with
 * the accessory record's enum in {@link BatteryLevelDescription} shifted by one for "unknown".
 * <b>That is an inference drawn here from two conflicting third-party sources and nothing else.</b>
 * It is written down because it is the obvious thing to test next, not because it is known.
 *
 * <p>Hence the rendering: decimal, hex and binary of one number. All three are certainly right,
 * and the binary is what somebody correlating this against their own tags actually needs. Whoever
 * does that work should replace this section with what they found.
 *
 * <p>Not translated, matching the rest of the debug output - a translated bit pattern helps
 * nobody.
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

    /**
     * {@code "status 144 = 0x90 = 0b10010000"}.
     *
     * <p><b>No bit is named.</b> Three renderings of one number, every one of them certainly true,
     * and the binary is the useful one. A value too wide for a byte is shown at its natural width
     * rather than truncated to eight bits: a status that does not fit in a byte means an
     * assumption somewhere is wrong, and hiding the evidence would be the worst response to that.
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

        return String.format(Locale.ROOT, "status %d = 0x%02X = 0b%s", value, value, padded);
    }
}
