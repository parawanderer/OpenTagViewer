package dev.wander.android.opentagviewer.ui;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.progressindicator.BaseProgressIndicator;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import dev.wander.android.opentagviewer.R;

/**
 * Every circular spinner in the app is configured to look like one.
 *
 * <p><b>Left bare, Material's indeterminate circular indicator is a thin arc with square ends and
 * nothing behind it</b>, which at 18-24dp does not read as something turning - it reads as a
 * stray refresh glyph somebody forgot to remove. Four properties fix that: a track behind the
 * arc, rounded ends, and inward show/hide so it grows out of nothing instead of appearing.
 *
 * <p>This exists because the sign-in screen got all four and the Anisette status line beside it
 * did not, and the difference is invisible in a diff and obvious on a device - @parawanderer
 * spotted it in a slow-motion run of a test that was passing. One screen having a spinner and
 * its neighbour having a glyph is the kind of thing that is noticed without being nameable.
 *
 * <p><b>Found by walking every layout rather than by listing the ones known about</b>, so a
 * spinner added later is covered by having been added. The count is asserted too: if inflation
 * started failing wholesale this would find nothing and pass, which is the shape of a test that
 * has quietly stopped testing.
 */
@RunWith(AndroidJUnit4.class)
public class SpinnersLookLikeSpinnersTest {

    /** What the app has today. A new spinner should raise this, not be excluded from it. */
    private static final int AT_LEAST_THIS_MANY = 8;

    private static Context themed() {
        return new ContextThemeWrapper(
                getInstrumentation().getTargetContext(), R.style.Theme_OpenTagViewer);
    }

    /** Every layout the app ships, by reflection over the generated R class. */
    private static List<Integer> everyLayout() {
        final List<Integer> layouts = new ArrayList<>();

        for (final Field field : R.layout.class.getFields()) {
            try {
                layouts.add(field.getInt(null));
            } catch (final IllegalAccessException ignored) {
                // Not a layout id we can read; nothing to inflate.
            }
        }

        return layouts;
    }

    private static void collectSpinners(final View view,
                                        final List<CircularProgressIndicator> into) {
        if (view instanceof CircularProgressIndicator) {
            into.add((CircularProgressIndicator) view);
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectSpinners(group.getChildAt(i), into);
            }
        }
    }

    private static List<CircularProgressIndicator> everySpinnerInTheApp() {
        final Context context = themed();
        final LayoutInflater inflater = LayoutInflater.from(context);
        final List<CircularProgressIndicator> found = new ArrayList<>();

        getInstrumentation().runOnMainSync(() -> {
            for (final int layout : everyLayout()) {
                try {
                    collectSpinners(inflater.inflate(layout, null, false), found);
                } catch (final Exception | Error ignored) {
                    // Some layouts need an activity, a binding, or Play Services. Skipping one
                    // is only safe because the count below would notice if skipping became the
                    // rule rather than the exception.
                }
            }
        });

        return found;
    }

    @Test
    public void everySpinnerHasATrackBehindIt() {
        final List<CircularProgressIndicator> spinners = everySpinnerInTheApp();

        assertTrue("only " + spinners.size() + " spinner(s) were found; inflation is failing and"
                + " this test is no longer checking anything", spinners.size() >= AT_LEAST_THIS_MANY);

        for (final CircularProgressIndicator spinner : spinners) {
            assertTrue("a spinner has no track behind its arc, so it reads as a stray glyph"
                            + " rather than as something turning",
                    spinner.getTrackColor() != Color.TRANSPARENT);
        }
    }

    @Test
    public void everySpinnerHasRoundedEnds() {
        for (final CircularProgressIndicator spinner : everySpinnerInTheApp()) {
            assertTrue("a spinner still has square ends", spinner.getTrackCornerRadius() > 0);
        }
    }

    /**
     * Inward, so it grows out of nothing rather than appearing mid-turn.
     *
     * <p>The one that is easiest to leave off and hardest to argue about afterwards: without it
     * the spinner pops into existence at full size, which on a screen that is already changing
     * reads as a flicker.
     */
    @Test
    public void everySpinnerGrowsAndShrinksRatherThanAppearing() {
        for (final CircularProgressIndicator spinner : everySpinnerInTheApp()) {
            assertTrue("a spinner appears rather than growing in",
                    spinner.getShowAnimationBehavior() == BaseProgressIndicator.SHOW_INWARD);
            assertTrue("a spinner vanishes rather than shrinking away",
                    spinner.getHideAnimationBehavior() == BaseProgressIndicator.HIDE_INWARD);
        }
    }
}
