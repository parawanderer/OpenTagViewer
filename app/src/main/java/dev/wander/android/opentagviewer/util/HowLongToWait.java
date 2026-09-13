package dev.wander.android.opentagviewer.util;

import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;

import java.util.Locale;

/**
 * A wait Apple asked for, as a person would say it.
 *
 * <p><b>Always rounded up.</b> Somebody told to come back too early is refused again, and then
 * trusts the number less - so 61 seconds is "2 minutes", never "1 minute".
 *
 * <p>Seconds below a minute, minutes below two hours, hours after that. The desktop exporter's
 * {@code _a_wait_a_person_reads} draws the same lines, so the two say the same thing about the
 * same header.
 *
 * <p>The rounding is here and plain Java so the JVM suite can pin it; the wording comes from
 * {@code android.icu}, which knows every locale's plural forms and so needs no string per unit.
 */
public final class HowLongToWait {

    public enum Unit { SECONDS, MINUTES, HOURS }

    private static final long MINUTE = 60;
    private static final long HOUR = 60 * MINUTE;

    private final long amount;
    private final Unit unit;

    private HowLongToWait(final long amount, final Unit unit) {
        this.amount = amount;
        this.unit = unit;
    }

    public static HowLongToWait of(final double seconds) {
        final long whole = Math.max(0, (long) Math.ceil(seconds));
        if (whole < MINUTE) {
            return new HowLongToWait(whole, Unit.SECONDS);
        }
        if (whole < 2 * HOUR) {
            return new HowLongToWait(ceilDiv(whole, MINUTE), Unit.MINUTES);
        }
        return new HowLongToWait(ceilDiv(whole, HOUR), Unit.HOURS);
    }

    public long getAmount() {
        return this.amount;
    }

    public Unit getUnit() {
        return this.unit;
    }

    /** "2 minutes", "2 Minuten", "2 分钟" - in the given locale, with its own plural rules. */
    public String describe(final Locale locale) {
        final MeasureUnit measured;
        switch (this.unit) {
            case SECONDS: measured = MeasureUnit.SECOND; break;
            case MINUTES: measured = MeasureUnit.MINUTE; break;
            default: measured = MeasureUnit.HOUR; break;
        }
        return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE)
                .format(new Measure(this.amount, measured));
    }

    private static long ceilDiv(final long x, final long y) {
        return (x + y - 1) / y;
    }
}
