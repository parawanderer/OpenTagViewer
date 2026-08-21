package dev.wander.android.opentagviewer.util.parse;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

/**
 * Turning the raw {@code batteryLevel} integer into something readable.
 *
 * <p>Apple documents none of this, so the labels are a community reading rather than a
 * specification - which is exactly why the raw number is kept alongside them, and why the tests
 * below care more about "an unknown value is not relabelled" than about any particular word.
 */
@RunWith(AndroidJUnit4.class)
public class BatteryLevelDescriptionTest {

    private Context context() {
        return getInstrumentation().getTargetContext();
    }

    /** The number comes first, because that is what a bug report quotes. */
    @Test
    public void thenumberIsShownBeforeItsMeaning() {
        final String described = BatteryLevelDescription.describe(
                this.context(), BatteryLevelDescription.FULL);

        assertTrue("the raw value must survive into the text: " + described,
                described.startsWith("1"));
        assertTrue("the meaning should be alongside it: " + described, described.length() > 1);
    }

    /**
     * <b>A value nobody documented is shown bare, not guessed at.</b>
     *
     * <p>The important one. A new state, or a field that turns out not to be a battery level on
     * some accessory, must not be dressed up as something certain - a label reads as knowledge in
     * a way a number does not.
     */
    @Test
    public void anunrecognisedValueIsLeftAsANumber() {
        for (final int strange : new int[] {5, 9, 42, -1, Integer.MAX_VALUE}) {
            assertEquals("a value with no documented meaning must not be relabelled",
                    String.valueOf(strange),
                    BatteryLevelDescription.describe(this.context(), strange));
        }
    }

    /** The five documented values each say something, and say something different. */
    @Test
    public void thedocumentedValuesAreAllDistinct() {
        final Set<String> seen = new HashSet<>();

        for (final int level : new int[] {
                BatteryLevelDescription.UNKNOWN,
                BatteryLevelDescription.FULL,
                BatteryLevelDescription.MEDIUM,
                BatteryLevelDescription.LOW,
                BatteryLevelDescription.VERY_LOW}) {

            final String described = BatteryLevelDescription.describe(this.context(), level);

            assertTrue("level " + level + " was left as a bare number",
                    described.length() > String.valueOf(level).length());
            assertTrue("two levels describe themselves identically: " + described,
                    seen.add(described));
        }
    }

    /**
     * Zero is "not reported yet", not "flat".
     *
     * <p>Worth its own test because getting it backwards is both easy and alarming: a tag nobody
     * has walked past yet would be reported as a dead battery, and the user would go and change
     * a perfectly good one.
     */
    @Test
    public void zeroIsNotKnownRatherThanEmpty() {
        final String described = BatteryLevelDescription.describe(
                this.context(), BatteryLevelDescription.UNKNOWN);

        assertEquals(this.context().getString(
                        dev.wander.android.opentagviewer.R.string.battery_level_described,
                        0,
                        this.context().getString(
                                dev.wander.android.opentagviewer.R.string.battery_level_unknown)),
                described);
    }
}
