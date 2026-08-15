package dev.wander.android.opentagviewer.ui.theme;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.color.DynamicColors;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import dev.wander.android.opentagviewer.R;

/**
 * Renders the layouts that carried hard-coded palette colours, with and without wallpaper
 * colouring, and writes both to disk.
 *
 * <p>The reason this exists rather than a colour assertion alone: the bug it guards against is
 * that a layout naming {@code @color/md_theme_*} directly cannot follow a theme overlay, so it
 * keeps the app palette while everything around it moves. Nothing throws, no attribute is
 * missing, and the only symptom is that the screen looks wrong - which is exactly the class of
 * defect an assertion on a single resolved colour will not catch.
 *
 * <p>Images land in the directory AGP hands us via {@code additionalTestOutputDir} and are
 * copied back to the host after the run, so a person can look at them. The assertions below
 * are what fails the build; the images are what explains why.
 *
 * <p>No activity, no account, no map. Layouts are inflated against a themed context, measured
 * at a fixed width and drawn straight to a bitmap.
 */
@RunWith(AndroidJUnit4.class)
public class SystemColorsLayoutTest {
    private static final String TAG = SystemColorsLayoutTest.class.getSimpleName();

    /** Wide enough for the list rows to lay out as they would on a phone. */
    private static final int WIDTH_PX = 1080;

    /**
     * The layouts that had the most palette references, and the ones a user sees most.
     *
     * <p>Layouts driven entirely by data-binding variables are deliberately not here: inflated
     * without their variables they draw nothing, so a screenshot of one proves nothing.
     */
    private static final int[] SUBJECTS = {
            R.layout.my_device_list_item,
            R.layout.inline_top_toolbar,
            R.layout.history_list_item,
    };

    /**
     * The history timeline tiles, drawn directly.
     *
     * <p>The selected tile is the only conversion in this change where the colour genuinely
     * moved rather than merely becoming theme-driven - it was a fixed mid-teal and is now
     * {@code colorPrimary} - and the layout above always renders the *unselected* tile, so it
     * would never show up there.
     */
    private static final int[] TILE_SUBJECTS = {
            R.drawable.pin_drop_tile_pin_filled,
            R.drawable.pin_drop_tile_empty_filled,
    };

    /** Tiles are a narrow timeline column; their natural size is tiny. */
    private static final int TILE_WIDTH_PX = 64;

    // Rendering against arbitrary seed colours was tried and removed. DynamicColorsOptions
    // .setContentBasedSource builds a ThemeOverlay.Material3.PersonalizedColors that cannot
    // resolve every attribute a real layout asks for, so inflation throws
    // UnsupportedOperationException on a drawable attribute rather than producing a preview.
    // The wallpaper path the app actually uses is covered by the assertions below.

    private static File outputDir;

    @BeforeClass
    public static void resolveOutputDir() {
        final String fromAgp = InstrumentationRegistry.getArguments()
                .getString("additionalTestOutputDir");

        outputDir = fromAgp != null
                ? new File(fromAgp)
                // Falls back to the app's own external files dir when run outside AGP, so the
                // test is still useful from the IDE - just with the images left on the device.
                : getInstrumentation().getTargetContext().getExternalFilesDir("theme-shots");

        if (outputDir != null && !outputDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outputDir.mkdirs();
        }
        Log.i(TAG, "Writing theme screenshots to " + outputDir);
    }

    /**
     * The premise of the whole feature: wrapping a context in dynamic colours has to actually
     * change what the colour attributes resolve to. If this fails, the device does not support
     * it and the rest of the results mean nothing.
     */
    @Test
    public void wallpaperColoursActuallyChangeWhatAttributesResolveTo() {
        assertTrue("this device cannot do dynamic colour, so the rest of this test is moot",
                DynamicColors.isDynamicColorAvailable());

        final int fixed = resolve(appTheme(), com.google.android.material.R.attr.colorPrimary);
        final int dynamic = resolve(dynamicTheme(), com.google.android.material.R.attr.colorPrimary);

        assertNotEquals("colorPrimary should differ once wallpaper colours are applied",
                fixed, dynamic);
    }

    /**
     * The regression this branch is about. {@code colorOnSurfaceVariant} is the role the
     * converted text and icons now use; if a layout still names the colour directly it will not
     * move, and this is the attribute it should have been using.
     */
    @Test
    public void theConvertedTextRoleFollowsTheWallpaper() {
        final int fixed = resolve(appTheme(),
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        final int dynamic = resolve(dynamicTheme(),
                com.google.android.material.R.attr.colorOnSurfaceVariant);

        assertNotEquals("colorOnSurfaceVariant should follow the wallpaper once converted",
                fixed, dynamic);
    }

    @Test
    public void renderEverySubjectBothWays() throws Throwable {
        for (int layout : SUBJECTS) {
            final String name = getInstrumentation().getTargetContext()
                    .getResources().getResourceEntryName(layout);

            write(name + "-fixed.png", render(appTheme(), layout));
            write(name + "-wallpaper.png", render(dynamicTheme(), layout));
        }
    }

    @Test
    public void renderTheTimelineTilesBothWays() {
        for (int drawable : TILE_SUBJECTS) {
            final String name = getInstrumentation().getTargetContext()
                    .getResources().getResourceEntryName(drawable);

            write(name + "-fixed.png", renderDrawable(appTheme(), drawable));
            write(name + "-wallpaper.png", renderDrawable(dynamicTheme(), drawable));
        }
    }

    /**
     * Draws a vector drawable at its own aspect ratio, resolved against the given theme.
     *
     * <p>{@code AppCompatResources} rather than {@code Resources.getDrawable} so the theme
     * attributes inside the vector resolve - which is the entire thing under test.
     */
    private Bitmap renderDrawable(final Context context, final int drawableRes) {
        android.graphics.drawable.Drawable drawable =
                androidx.appcompat.content.res.AppCompatResources.getDrawable(context, drawableRes);

        if (drawable == null) {
            throw new AssertionError("could not load drawable " + drawableRes);
        }

        final int height = Math.max(1, drawable.getIntrinsicHeight() * TILE_WIDTH_PX
                / Math.max(1, drawable.getIntrinsicWidth()));

        Bitmap bitmap = Bitmap.createBitmap(TILE_WIDTH_PX, height, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, TILE_WIDTH_PX, height);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static Context appTheme() {
        return new ContextThemeWrapper(
                getInstrumentation().getTargetContext(), R.style.Theme_OpenTagViewer);
    }

    private static Context dynamicTheme() {
        return DynamicColors.wrapContextIfAvailable(appTheme());
    }

    private static int resolve(final Context context, final int attr) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    /** Inflates, measures and draws a layout, on the main thread because Material insists. */
    private Bitmap render(final Context context, final int layout) throws Throwable {
        final Bitmap[] result = new Bitmap[1];

        getInstrumentation().runOnMainSync(() -> {
            View view = LayoutInflater.from(context).inflate(layout, null, false);

            view.measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            view.layout(0, 0, view.getMeasuredWidth(), Math.max(1, view.getMeasuredHeight()));

            Bitmap bitmap = Bitmap.createBitmap(
                    Math.max(1, view.getMeasuredWidth()),
                    Math.max(1, view.getMeasuredHeight()),
                    Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));
            result[0] = bitmap;
        });

        return result[0];
    }

    private void write(final String fileName, final Bitmap bitmap) {
        if (outputDir == null) {
            Log.w(TAG, "No output directory; skipping " + fileName);
            return;
        }
        File target = new File(outputDir, fileName);
        try (FileOutputStream out = new FileOutputStream(target)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            // Fail rather than log: a run that quietly produced no images looks like a pass,
            // and the images are half the point of this test.
            throw new AssertionError("could not write " + target, e);
        }
        Log.i(TAG, "Wrote " + target.getAbsolutePath());
    }
}
