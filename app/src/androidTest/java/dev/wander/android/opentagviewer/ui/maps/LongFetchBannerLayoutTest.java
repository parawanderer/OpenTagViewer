package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

import dev.wander.android.opentagviewer.R;

/**
 * The map's "finding your tags" banner, inflated against the app theme in light and dark mode.
 * Its own layout file so this is possible at all: the map screen needs Play Services.
 */
@RunWith(AndroidJUnit4.class)
public class LongFetchBannerLayoutTest {

    private static final int WIDTH_PX = 900;

    private static Context theme(final boolean night) {
        final Context base = getInstrumentation().getTargetContext();
        final Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return new ContextThemeWrapper(
                base.createConfigurationContext(config), R.style.Theme_OpenTagViewer);
    }

    private static int resolve(final Context context, final int attr) {
        final TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    private void check(final boolean night, final String shot) {
        final Context context = theme(night);
        final View[] inflated = new View[1];
        final Bitmap[] drawn = new Bitmap[1];

        getInstrumentation().runOnMainSync(() -> {
            final View view = LayoutInflater.from(context)
                    .inflate(R.layout.long_fetch_banner, null, false);
            ((TextView) view.findViewById(R.id.long_fetch_banner_text)).setText(
                    context.getString(R.string.finding_tags_banner, 2));
            view.measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
            final Bitmap bitmap = Bitmap.createBitmap(Math.max(1, view.getMeasuredWidth()),
                    Math.max(1, view.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(resolve(context, android.R.attr.colorBackground));
            view.draw(new Canvas(bitmap));
            inflated[0] = view;
            drawn[0] = bitmap;
        });

        final View view = inflated[0];
        final View spinner = view.findViewById(R.id.long_fetch_banner_spinner);
        final TextView text = view.findViewById(R.id.long_fetch_banner_text);
        assertNotNull("MapsActivity looks this up", spinner);
        assertNotNull("MapsActivity looks this up", text);

        assertTrue("the spinner has to be visible", spinner.getMeasuredWidth() > 0);
        assertTrue("the spinner sits left of the text", spinner.getRight() <= text.getLeft());
        assertTrue(text.getText().toString(), text.getText().toString().contains("2"));

        final int ink = text.getCurrentTextColor();
        final int ground = resolve(context, android.R.attr.colorBackground);
        final double ratio = ColorUtils.calculateContrast(
                ink, Color.alpha(ground) == 255 ? ground : (ground | 0xFF000000));
        assertTrue("text contrast " + ratio + " is below 4.5:1", ratio >= 4.5);

        write(shot, drawn[0]);
    }

    @Test
    public void inLightMode() {
        check(false, "long_fetch_banner_light.png");
    }

    @Test
    public void inDarkMode() {
        check(true, "long_fetch_banner_dark.png");
    }

    private static void write(final String name, final Bitmap bitmap) {
        final String dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir");
        if (dir == null) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (final Exception e) {
            throw new AssertionError("could not write " + name, e);
        }
    }
}
