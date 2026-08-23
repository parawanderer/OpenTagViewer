package dev.wander.android.opentagviewer.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;

/**
 * A value rendered as inline code - monospace, on a tinted rounded chip.
 *
 * <p><b>For the one string in this app that people compare character by character.</b> The serial
 * {@code 0PENTAGVIEWR} is the only field distinguishing this app's entry in an Apple device list
 * from real hardware, and it is deliberately near-miss shaped: a zero where an O belongs, and no
 * vowel in VIEWR. In body text it reads as a typo. Set as code it reads as a value to be matched,
 * which is what somebody is about to do with it.
 *
 * <p>Written as a span rather than as markup because there is no markup for it. Android's string
 * resources carry {@code <b>}, {@code <i>} and {@code <u>}; a chip needs a measured background,
 * which nothing in a resource can express. It is applied where the text is built.
 *
 * <p><b>A {@link ReplacementSpan}, so the chip cannot be split across two lines.</b> If it does
 * not fit on the rest of the line it moves to the next one whole. That is the right trade for
 * short values and the wrong one for long ones - the text does not wrap inside a chip, so a
 * sentence-length argument here would run off the edge.
 */
public class CodeChipSpan extends ReplacementSpan {

    /** Breathing room either side of the text, in pixels. */
    private final float horizontalPadding;

    /** How far the chip is drawn above and below the text, in pixels. */
    private final float verticalPadding;

    private final float cornerRadius;

    private final int backgroundColour;

    private final int textColour;

    public CodeChipSpan(final float density, final int backgroundColour, final int textColour) {
        this.horizontalPadding = 5f * density;
        this.verticalPadding = 2f * density;
        this.cornerRadius = 4f * density;
        this.backgroundColour = backgroundColour;
        this.textColour = textColour;
    }

    /**
     * Wrap one run of an existing string in a chip.
     *
     * <p>Returns the text unchanged when {@code value} is not in it, rather than throwing. The
     * caller is a screen: a chip that failed to apply is a cosmetic loss, and a crash on a page
     * whose whole job is to be read is not a trade worth making.
     */
    public static CharSequence applyTo(
            final CharSequence text, final String value, final CodeChipSpan span) {
        final int start = text.toString().indexOf(value);
        if (start < 0) {
            return text;
        }

        final SpannableStringBuilder out = new SpannableStringBuilder(text);
        out.setSpan(span, start, start + value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    @Override
    public int getSize(@NonNull final Paint paint, final CharSequence text,
                       final int start, final int end, final Paint.FontMetricsInt fontMetrics) {
        // The metrics the line is laid out with have to come from the same typeface the text is
        // drawn in, or a monospace run measured as the body font clips its own last character.
        final Paint measuring = this.chipPaint(paint);

        if (fontMetrics != null) {
            final Paint.FontMetricsInt monospace = measuring.getFontMetricsInt();
            fontMetrics.ascent = Math.min(fontMetrics.ascent, monospace.ascent);
            fontMetrics.descent = Math.max(fontMetrics.descent, monospace.descent);
            fontMetrics.top = Math.min(fontMetrics.top, monospace.top);
            fontMetrics.bottom = Math.max(fontMetrics.bottom, monospace.bottom);
        }

        return Math.round(
                measuring.measureText(text, start, end) + (this.horizontalPadding * 2f));
    }

    @Override
    public void draw(@NonNull final Canvas canvas, final CharSequence text,
                     final int start, final int end, final float x,
                     final int top, final int y, final int bottom,
                     @NonNull final Paint paint) {
        final Paint chip = this.chipPaint(paint);
        final Paint.FontMetrics metrics = chip.getFontMetrics();

        final RectF box = new RectF(
                x,
                y + metrics.ascent - this.verticalPadding,
                x + chip.measureText(text, start, end) + (this.horizontalPadding * 2f),
                y + metrics.descent + this.verticalPadding);

        final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(this.backgroundColour);
        canvas.drawRoundRect(box, this.cornerRadius, this.cornerRadius, background);

        chip.setColor(this.textColour);
        canvas.drawText(text, start, end, x + this.horizontalPadding, y, chip);
    }

    /**
     * The body paint, in monospace, slightly smaller.
     *
     * <p>Monospace runs visually larger than the proportional body face at the same size, so
     * matching the number would leave the chip looking like a heading. A copy each time rather
     * than a mutated {@code paint}: the one handed in belongs to the layout, and changing its
     * typeface leaks into the rest of the line.
     */
    private Paint chipPaint(final Paint paint) {
        final Paint chip = new Paint(paint);
        chip.setTypeface(Typeface.MONOSPACE);
        chip.setTextSize(paint.getTextSize() * 0.92f);
        return chip;
    }
}
