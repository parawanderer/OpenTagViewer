package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;

import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.R;

/**
 * The colour a map pin is filled with.
 *
 * <p><b>The pins and the cards drifted apart on exactly the setting meant to make them match.</b>
 * A pin was filled from {@code R.color.md_theme_background} while the tag card tinted itself with
 * {@code ?android:attr/colorBackground}. Those are the same value in every built-in theme, night
 * included - and stop being the same the moment system colours are on, because
 * {@code DynamicColors} rewrites the theme attribute and cannot rewrite a fixed value in
 * colors.xml. So the cards took the wallpaper's tint and the pins stayed on the app's palette.
 *
 * <p><b>The real risk in fixing it is contrast, not colour.</b> Once the fill can be anything a
 * wallpaper suggests, an icon drawn in a fixed grey can land on top of something almost exactly
 * its own shade. A pin here once measured 1.23:1 - present, correct, and invisible - which is why
 * the ratio is asserted rather than eyeballed, and why the icon colour moved to one Material
 * guarantees against the surfaces around it.
 */
@RunWith(AndroidJUnit4.class)
public class MarkerFollowsTheThemeTest {

    /** WCAG for non-text. A pin is a graphical object, so 3:1 rather than 4.5:1. */
    private static final double MINIMUM_CONTRAST = 3.0d;

    private Context themed(final boolean night) {
        final Context base = getInstrumentation().getTargetContext();

        final Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);

        return new ContextThemeWrapper(
                base.createConfigurationContext(configuration), R.style.Theme_OpenTagViewer);
    }

    private int cardBackgroundOf(final Context context) {
        final TypedValue found = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorBackground, found, true);
        return found.data;
    }

    /**
     * <b>The fill is whatever the card asked for.</b>
     *
     * <p>Asserted against the resolved attribute rather than a named colour, so it keeps holding
     * when a theme changes what that attribute points at - which is the entire scenario.
     */
    @Test
    public void thepinIsFilledWithTheCardsOwnBackground() {
        for (final boolean night : new boolean[] {false, true}) {
            final Context context = this.themed(night);

            assertEquals("the pin fill stopped following the card background, night=" + night,
                    this.cardBackgroundOf(context), MarkerPalette.fill(context));
        }
    }

    /**
     * <b>It is read from the theme, not from the colour resource.</b>
     *
     * <p>The regression itself. Reading the resource passes every assertion above in the built-in
     * themes, which is why it went unnoticed - so this drives the case that told them apart: a
     * theme whose {@code colorBackground} has deliberately been overridden, standing in for what
     * DynamicColors does at runtime.
     */
    @Test
    public void arethemedBackgroundIsPreferredOverTheStaticResource() {
        final Context recoloured = new ContextThemeWrapper(
                getInstrumentation().getTargetContext(), R.style.Theme_OpenTagViewer);
        recoloured.getTheme().applyStyle(R.style.TestOnly_RecolouredBackground, true);

        final int staticResource =
                getInstrumentation().getTargetContext().getColor(R.color.md_theme_background);

        assertNotEquals("the test theme did not actually change anything, so this proves nothing",
                staticResource, this.cardBackgroundOf(recoloured));

        assertEquals("the pin is still reading the colour resource, so a themed background "
                        + "leaves it behind",
                this.cardBackgroundOf(recoloured), MarkerPalette.fill(recoloured));
    }

    /**
     * <b>And the icon stays visible on it.</b>
     *
     * <p>3:1 against the fill, in both modes. Without this the change could quietly make a pin
     * whose icon is the same shade as the pin.
     */
    @Test
    public void theiconOnApinIsLegibleAgainstIt() {
        for (final boolean night : new boolean[] {false, true}) {
            final Context context = this.themed(night);

            final double ratio = ColorUtils.calculateContrast(
                    MarkerPalette.icon(context), MarkerPalette.fill(context));

            assertTrue(String.format(
                            "the pin icon is %.2f:1 against its own fill (night=%s), below the "
                                    + "%.1f:1 a graphical object needs",
                            ratio, night, MINIMUM_CONTRAST),
                    ratio >= MINIMUM_CONTRAST);
        }
    }

    /**
     * <b>The colour reaches the pixels.</b>
     *
     * <p>Everything above is about numbers agreeing. This renders the pin the way the map does
     * and reads the middle of its head, because a fill that is computed correctly and then not
     * painted looks exactly like one that was never computed.
     */
    @Test
    public void thefillIsWhatActuallyGetsDrawn() {
        final Context context = this.themed(false);
        final int fill = MarkerPalette.fill(context);

        final Bitmap pin = VectorImageGeneratorUtil.makeMarker(
                context.getResources(), R.drawable.apple, fill, MarkerPalette.icon(context));

        // Just inside the head of the pin and clear of the icon: a quarter down, a quarter across.
        final int sampled = pin.getPixel(pin.getWidth() / 4, pin.getHeight() / 4);

        assertEquals("the pin's red channel is not the fill's",
                Color.red(fill), Color.red(sampled), 8);
        assertEquals("the pin's green channel is not the fill's",
                Color.green(fill), Color.green(sampled), 8);
        assertEquals("the pin's blue channel is not the fill's",
                Color.blue(fill), Color.blue(sampled), 8);
    }

    /**
     * <b>An emoji sits where the icon sits.</b>
     *
     * <p>Spotted by @parawanderer in the render: the bike was low and left, next to an apple that
     * was not. The two are drawn by different means - {@code drawText} against {@code setBounds} -
     * and the emoji was placed by halving the whole drawable's height, which is below the middle
     * of a pin's head because a pin is a circle with a point hanging off it.
     *
     * <p>Measured rather than looked at, by finding what each pin actually painted: the bounding
     * box of every pixel that is not the fill, whose centre is where the thing on the pin really
     * is. Comparing the two against each other rather than against a constant means this keeps
     * holding if the pin drawable itself ever changes shape.
     */
    @Test
    public void anemojiIsCentredWhereTheIconIs() {
        final Context context = this.themed(false);
        final int fill = MarkerPalette.fill(context);

        final int[] icon = paintedCentreOf(VectorImageGeneratorUtil.makeMarker(
                context.getResources(), R.drawable.apple, fill, MarkerPalette.icon(context)), fill);
        final int[] emoji = paintedCentreOf(VectorImageGeneratorUtil.makeMarker(
                context.getResources(), "🚲", fill), fill);

        assertEquals("the emoji is " + Math.abs(icon[0] - emoji[0])
                        + "px off the icon horizontally",
                icon[0], emoji[0], 4);
        assertEquals("the emoji is " + Math.abs(icon[1] - emoji[1])
                        + "px off the icon vertically",
                icon[1], emoji[1], 4);
    }

    /** The centre of everything drawn on the pin that is not the pin itself. */
    private static int[] paintedCentreOf(final Bitmap pin, final int fill) {
        int minX = pin.getWidth();
        int minY = pin.getHeight();
        int maxX = -1;
        int maxY = -1;

        // Inset, so the pin's own outline and shadow are not mistaken for its contents.
        final int inset = pin.getWidth() / 4;

        for (int x = inset; x < pin.getWidth() - inset; x++) {
            for (int y = inset; y < pin.getHeight() - inset; y++) {
                final int pixel = pin.getPixel(x, y);

                if (Math.abs(Color.red(pixel) - Color.red(fill)) < 24
                        && Math.abs(Color.green(pixel) - Color.green(fill)) < 24
                        && Math.abs(Color.blue(pixel) - Color.blue(fill)) < 24) {
                    continue;
                }

                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }

        assertTrue("nothing was drawn on the pin at all", maxX >= 0);

        return new int[] {(minX + maxX) / 2, (minY + maxY) / 2};
    }

    /**
     * Render the pins beside the background they are meant to match, so somebody can look.
     *
     * <p><b>Not an assertion</b> - everything above is. This exists so that when one of those
     * fails, the picture says what "wrong" looked like. Each panel is a pin drawn on a block of
     * the very colour its fill is taken from, so a pin that has stopped following the theme shows
     * up as the one square with something visible in the middle of it.
     */
    @Test
    public void renderThePinsForSomebodyToLookAt() {
        this.render("map_marker-light", this.themed(false));
        this.render("map_marker-dark", this.themed(true));

        final Context recoloured = new ContextThemeWrapper(
                getInstrumentation().getTargetContext(), R.style.Theme_OpenTagViewer);
        recoloured.getTheme().applyStyle(R.style.TestOnly_RecolouredBackground, true);
        this.render("map_marker-recoloured", recoloured);
    }

    private void render(final String name, final Context context) {
        final int fill = MarkerPalette.fill(context);

        final Bitmap withIcon = VectorImageGeneratorUtil.makeMarker(
                context.getResources(), R.drawable.apple, fill, MarkerPalette.icon(context));
        final Bitmap withEmoji = VectorImageGeneratorUtil.makeMarker(
                context.getResources(), "🚲", fill);

        final int pad = 16;
        final Bitmap sheet = Bitmap.createBitmap(
                withIcon.getWidth() + withEmoji.getWidth() + pad * 3,
                Math.max(withIcon.getHeight(), withEmoji.getHeight()) + pad * 2,
                Bitmap.Config.ARGB_8888);

        final Canvas canvas = new Canvas(sheet);
        // The card's colour behind them, which is the whole claim being made.
        canvas.drawColor(fill);
        canvas.drawBitmap(withIcon, pad, pad, null);
        canvas.drawBitmap(withEmoji, pad * 2f + withIcon.getWidth(), pad, null);

        write(name + ".png", sheet);
    }

    private static void write(final String fileName, final Bitmap bitmap) {
        final String fromAgp = InstrumentationRegistry.getArguments()
                .getString("additionalTestOutputDir");

        final File dir = fromAgp != null
                ? new File(fromAgp)
                : getInstrumentation().getTargetContext().getExternalFilesDir("marker-shots");

        if (dir == null) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        final File target = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(target)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException problem) {
            // Fail rather than log: a run that quietly produced no images looks like a pass.
            throw new AssertionError("could not write " + target, problem);
        }
    }

    /**
     * Two themes produce two different pins.
     *
     * <p>Guards the bitmap cache, which is keyed on the colour. A cache keyed on anything less
     * would hand the second theme the first theme's pin, and the symptom would be identical to
     * the bug this all fixes.
     */
    @Test
    public void twothemesDoNotShareOnePin() {
        final Context light = this.themed(false);
        final Context dark = this.themed(true);

        final Bitmap onLight = VectorImageGeneratorUtil.makeMarker(
                light.getResources(), "🚲", MarkerPalette.fill(light));
        final Bitmap onDark = VectorImageGeneratorUtil.makeMarker(
                dark.getResources(), "🚲", MarkerPalette.fill(dark));

        assertNotEquals("the light and dark themes resolved to the same fill, so this proves "
                        + "nothing about the cache",
                MarkerPalette.fill(light), MarkerPalette.fill(dark));

        assertNotEquals("both themes were handed the same cached pin",
                onLight.getPixel(onLight.getWidth() / 4, onLight.getHeight() / 4),
                onDark.getPixel(onDark.getWidth() / 4, onDark.getHeight() / 4));
    }
}
