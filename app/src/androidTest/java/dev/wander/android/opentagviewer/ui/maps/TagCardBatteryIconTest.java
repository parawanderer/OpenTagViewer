package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.BatteryLevel;
import dev.wander.android.opentagviewer.ble.NearbyTagLabel;
import java.nio.charset.StandardCharsets;

/**
 * The battery icon on a map tag card's "Nearby" line (#228), which replaced the word.
 *
 * <p>Inflates the real card against the app theme, light and dark, and applies
 * {@link NearbyBatteryIcon} the way {@code MapsActivity#showNearbyStatusOn} does. No activity,
 * no map and no Apple session. Writes before/after screenshots for every level and theme to the
 * directory AGP passes as {@code additionalTestOutputDir}; see the device-screenshots skill.
 */
@RunWith(AndroidJUnit4.class)
public class TagCardBatteryIconTest {

    private static final String TAG = TagCardBatteryIconTest.class.getSimpleName();

    /** Matches TagCardLayoutTest: roughly a card's width on a phone. */
    private static final int CARD_WIDTH_PX = 1000;

    /** WCAG 2.1 minimum for a non-text element against what is behind it. */
    private static final double MINIMUM_NON_TEXT_CONTRAST = 3.0d;

    private static final String NEARBY_LINE = "Nearby (●●●○○)";

    private static File outputDir;

    @BeforeClass
    public static void resolveOutputDir() {
        final String fromAgp = InstrumentationRegistry.getArguments()
                .getString("additionalTestOutputDir");
        outputDir = fromAgp != null
                ? new File(fromAgp)
                : getInstrumentation().getTargetContext().getExternalFilesDir("tag-card-shots");
        if (outputDir != null && !outputDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outputDir.mkdirs();
        }
    }

    @Test
    public void everyLevelShowsAnIconAndKeepsTheWordForScreenReaders() {
        for (final BatteryLevel level : BatteryLevel.values()) {
            final FrameLayout card = this.inflateCard(lightTheme(), NEARBY_LINE, level);
            final ImageView icon = card.findViewById(R.id.device_nearby_battery);

            assertEquals(level + " icon not shown", View.VISIBLE, icon.getVisibility());
            assertNotNull(level + " icon has no drawable", icon.getDrawable());
            assertTrue(level + " drawable has no size", icon.getDrawable().getIntrinsicWidth() > 0);

            final String word = card.getContext().getString(NearbyTagLabel.shortBatteryLabel(level));
            assertEquals("Battery " + word, String.valueOf(icon.getContentDescription()));
        }
    }

    /** The English wording, spelled out, so the check is not only resources agreeing with themselves. */
    @Test
    public void theMediumIconIsReadAsBatteryMedium() {
        final FrameLayout card = this.inflateCard(lightTheme(), NEARBY_LINE, BatteryLevel.MEDIUM);
        final ImageView icon = card.findViewById(R.id.device_nearby_battery);
        assertEquals("Battery medium", String.valueOf(icon.getContentDescription()));
    }

    /**
     * Each level draws something, in both themes, and no two levels draw the same picture.
     *
     * <p>Rendered from the inflated view rather than from the drawable resource, because a vector
     * loaded without the card's theme draws as nothing while a test that supplies a theme stays
     * green - see AGENTS.md rule 12.
     */
    @Test
    public void everyLevelDrawsADifferentVisibleIconInBothThemes() {
        for (final Context theme : new Context[]{lightTheme(), darkTheme()}) {
            final Bitmap[] drawn = new Bitmap[BatteryLevel.values().length];
            for (final BatteryLevel level : BatteryLevel.values()) {
                final FrameLayout card = this.inflateCard(theme, NEARBY_LINE, level);
                final Bitmap bitmap = this.draw(card.findViewById(R.id.device_nearby_battery));
                assertTrue(level + " icon drew no pixels", opaquePixels(bitmap) > 0);
                drawn[level.ordinal()] = bitmap;
            }
            for (int a = 0; a < drawn.length; a++) {
                for (int b = a + 1; b < drawn.length; b++) {
                    assertFalse(BatteryLevel.values()[a] + " and " + BatteryLevel.values()[b]
                            + " draw the same icon", drawn[a].sameAs(drawn[b]));
                }
            }
        }
    }

    @Test
    public void aSightingWithoutBatteryFlagsHidesTheIcon() {
        final FrameLayout card = this.inflateCard(lightTheme(), NEARBY_LINE, null);
        final ImageView icon = card.findViewById(R.id.device_nearby_battery);
        assertEquals(View.GONE, icon.getVisibility());
        assertNull(icon.getContentDescription());
    }

    /** The icon measures to its 16dp, and does not make the card taller than the plain line. */
    @Test
    public void theIconMeasuresAndDoesNotGrowTheCard() {
        final FrameLayout baseline = this.inflateCard(lightTheme(), "Last Updated: 2 minutes ago", null);
        final FrameLayout withIcon = this.inflateCard(lightTheme(), NEARBY_LINE, BatteryLevel.VERY_LOW);
        final ImageView icon = withIcon.findViewById(R.id.device_nearby_battery);

        final int expectedPx = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                16, withIcon.getResources().getDisplayMetrics()));
        assertEquals(expectedPx, icon.getMeasuredWidth());
        assertEquals(expectedPx, icon.getMeasuredHeight());
        assertEquals("the icon grew the card", baseline.getMeasuredHeight(), withIcon.getMeasuredHeight());
    }

    /**
     * The tint against the card background clears 3:1 in both themes.
     *
     * <p>Resolved colours, not attribute names: a selected map pin here once shipped at 1.23:1.
     * The figures are also written next to the screenshots for the pull request.
     */
    @Test
    public void theTintClearsNonTextContrastInBothThemes() {
        final StringBuilder figures = new StringBuilder();
        for (final Context theme : new Context[]{lightTheme(), darkTheme()}) {
            final int tint = resolve(theme, com.google.android.material.R.attr.colorOutline);
            final int background = resolve(theme, android.R.attr.colorBackground);
            final double ratio = contrastRatio(tint, background);
            final String name = isNight(theme) ? "dark" : "light";
            figures.append(String.format(Locale.ROOT, "%s: tint #%06X on #%06X = %.2f:1%n",
                    name, tint & 0xFFFFFF, background & 0xFFFFFF, ratio));
            assertTrue(String.format(Locale.ROOT, "%s battery icon contrast %.2f:1 is under 3:1",
                    name, ratio), ratio >= MINIMUM_NON_TEXT_CONTRAST);
        }
        Log.i(TAG, figures.toString());
        this.writeText("tag_card_battery_contrast.txt", figures.toString());
    }

    /** Not an assertion: the before/after images for the pull request. */
    @Test
    public void writeBeforeAndAfterScreenshots() {
        for (final Context theme : new Context[]{lightTheme(), darkTheme()}) {
            final String variant = isNight(theme) ? "dark" : "light";
            for (final BatteryLevel level : BatteryLevel.values()) {
                final String word = theme.getString(NearbyTagLabel.shortBatteryLabel(level));
                final String name = level.name().toLowerCase(Locale.ROOT);
                // What the line said before #228, through the same card.
                this.write("tag_card_before_" + name + "-" + variant + ".png",
                        this.draw(this.inflateCard(theme, NEARBY_LINE + " · Battery " + word, null)));
                this.write("tag_card_after_" + name + "-" + variant + ".png",
                        this.draw(this.inflateCard(theme, NEARBY_LINE, level)));
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private FrameLayout inflateCard(final Context theme, final String line, final BatteryLevel level) {
        final FrameLayout[] result = new FrameLayout[1];
        getInstrumentation().runOnMainSync(() -> {
            final FrameLayout card = (FrameLayout) LayoutInflater.from(theme)
                    .inflate(R.layout.maps_tag_card, null);
            card.setLayoutParams(new ViewGroup.LayoutParams(
                    CARD_WIDTH_PX, ViewGroup.LayoutParams.WRAP_CONTENT));
            ((TextView) card.findViewById(R.id.device_name)).setText("Keys");
            ((TextView) card.findViewById(R.id.device_location)).setText("Amsterdam, Netherlands");
            ((TextView) card.findViewById(R.id.device_last_update)).setText(line);
            NearbyBatteryIcon.show(card.findViewById(R.id.device_nearby_battery), level);
            card.measure(
                    View.MeasureSpec.makeMeasureSpec(CARD_WIDTH_PX, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            card.layout(0, 0, card.getMeasuredWidth(), card.getMeasuredHeight());
            result[0] = card;
        });
        return result[0];
    }

    private Bitmap draw(final View view) {
        final Bitmap[] result = new Bitmap[1];
        getInstrumentation().runOnMainSync(() -> {
            final Bitmap bitmap = Bitmap.createBitmap(Math.max(1, view.getWidth()),
                    Math.max(1, view.getHeight()), Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));
            result[0] = bitmap;
        });
        return result[0];
    }

    private static int opaquePixels(final Bitmap bitmap) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static Context lightTheme() {
        return new ContextThemeWrapper(
                getInstrumentation().getTargetContext(), R.style.Theme_OpenTagViewer);
    }

    private static Context darkTheme() {
        final Context base = getInstrumentation().getTargetContext();
        final Configuration night = new Configuration(base.getResources().getConfiguration());
        night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | Configuration.UI_MODE_NIGHT_YES;
        return new ContextThemeWrapper(
                base.createConfigurationContext(night), R.style.Theme_OpenTagViewer);
    }

    private static boolean isNight(final Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int resolve(final Context context, final int attr) {
        final TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    /** WCAG 2.1 relative luminance. */
    private static double relativeLuminance(final int color) {
        final double[] channel = {
                Color.red(color) / 255d,
                Color.green(color) / 255d,
                Color.blue(color) / 255d,
        };
        for (int i = 0; i < channel.length; i++) {
            channel[i] = channel[i] <= 0.04045d
                    ? channel[i] / 12.92d
                    : Math.pow((channel[i] + 0.055d) / 1.055d, 2.4d);
        }
        return 0.2126d * channel[0] + 0.7152d * channel[1] + 0.0722d * channel[2];
    }

    private static double contrastRatio(final int a, final int b) {
        final double la = relativeLuminance(a);
        final double lb = relativeLuminance(b);
        return (Math.max(la, lb) + 0.05d) / (Math.min(la, lb) + 0.05d);
    }

    private void write(final String fileName, final Bitmap bitmap) {
        if (outputDir == null) {
            Log.w(TAG, "No output directory; skipping " + fileName);
            return;
        }
        final File target = new File(outputDir, fileName);
        try (FileOutputStream out = new FileOutputStream(target)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            throw new AssertionError("could not write " + target, e);
        }
    }

    private void writeText(final String fileName, final String text) {
        if (outputDir == null) {
            return;
        }
        final File target = new File(outputDir, fileName);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("could not write " + target, e);
        }
    }
}
