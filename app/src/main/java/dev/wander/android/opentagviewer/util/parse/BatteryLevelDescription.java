package dev.wander.android.opentagviewer.util.parse;

import android.content.Context;

import androidx.annotation.NonNull;

import dev.wander.android.opentagviewer.R;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * What the {@code batteryLevel} number on an accessory's record means.
 *
 * <p>The record carries a small integer and nothing else, so the debug panel showed "1" - true,
 * and useless to anybody who does not already know the scale. This turns it into
 * {@code 1 - Full (100%)}: the raw value first, because that is what a bug report should quote
 * and what every other source discusses, then the reading.
 *
 * <p><b>The scale, as far as anybody outside Apple knows it:</b>
 *
 * <table>
 *   <tr><td>0</td><td>Unknown - nothing has reported on this tag yet</td></tr>
 *   <tr><td>1</td><td>Full, around 100%</td></tr>
 *   <tr><td>2</td><td>Medium, roughly 50-70%</td></tr>
 *   <tr><td>3</td><td>Low, around 20%. This is where iOS starts showing its own warnings</td></tr>
 *   <tr><td>4</td><td>Critically low, roughly 5-10%. The tag is near the end of its battery</td></tr>
 * </table>
 *
 * <p><b>The four state names and their order are Apple's.</b> Its <i>Find My Network Accessory
 * Specification</i> (R2, Table 5-5) defines the battery state an accessory advertises as
 * {@code 0 = Full, 1 = Medium, 2 = Low, 3 = Critically low} - the same four states in the same
 * order, offset by one because this field reserves 0 for "not reported yet". A fresh tag reading 1
 * confirms the offset from the other end.
 *
 * <p><b>The percentages are not.</b> Neither is the claim that iOS warns at 3. Apple's enum is
 * four words with no numbers attached, and nobody here has checked those figures against a device
 * or a reference implementation - they are repeated knowledge. Said plainly because a comment like
 * this is trusted years later by somebody with no way to tell how much work went into it.
 *
 * <p>Note also that the specification's field is the byte a beacon <i>broadcasts</i>, which is not
 * this one - see {@link LocationReportFields} for that byte, why an AirTag's copy of it does not
 * follow the table, and why it is not decoded on sight.
 *
 * <p>That is also why the raw number always appears alongside the label. If a reading is wrong,
 * the value beside it still is not, and a bug report quoting "3" stays useful to whoever
 * disagrees with the word next to it. Anybody who does check this against real tags should
 * correct the table above and say so.
 *
 * <p><b>It only means anything for a tag read from an Apple account.</b> The field is updated by
 * Apple's own devices as they see the accessory, so a tag imported from a zip carries whatever
 * value was true when the export was made and never changes it again - possibly years ago. This
 * is why nothing outside the debug panel uses any of it. Anyone who wants to put a battery icon
 * on the device list should read this note first, and should probably only do it for account
 * tags.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BatteryLevelDescription {

    /** Apple has not been told yet - a tag nothing has reported on since it was paired. */
    public static final int UNKNOWN = 0;

    /** Full. Confirmable on a fresh tag. */
    public static final int FULL = 1;

    /** Roughly 50-70%. */
    public static final int MEDIUM = 2;

    /** Around 20%, and the point at which iOS begins warning about it itself. */
    public static final int LOW = 3;

    /** Roughly 5-10%: the tag is close to stopping. */
    public static final int VERY_LOW = 4;

    /**
     * {@code "1 - Full (100%)"}, or just the number when the value is not one this knows.
     *
     * <p>Unrecognised values are shown bare rather than guessed at. A new state, or a field that
     * turns out not to be a battery level at all on some accessory, must not be relabelled into
     * something that reads as certain.
     */
    @NonNull
    public static String describe(@NonNull final Context context, final int level) {
        final Integer meaning = meaningOf(level);

        if (meaning == null) {
            return String.valueOf(level);
        }

        return context.getString(R.string.battery_level_described, level, context.getString(meaning));
    }

    private static Integer meaningOf(final int level) {
        switch (level) {
            case UNKNOWN: return R.string.battery_level_unknown;
            case FULL: return R.string.battery_level_full;
            case MEDIUM: return R.string.battery_level_medium;
            case LOW: return R.string.battery_level_low;
            case VERY_LOW: return R.string.battery_level_very_low;
            default: return null;
        }
    }
}
