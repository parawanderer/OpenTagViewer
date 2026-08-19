package dev.wander.android.opentagviewer.ui.theme;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.view.ContextThemeWrapper;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.wander.android.opentagviewer.R;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The day and night themes have to agree about everything that is not a colour.
 *
 * <p><b>They are two independent declarations, not one inheriting the other.</b>
 * {@code values-night/themes.xml} redeclares {@code Theme.OpenTagViewer} from scratch, so an
 * attribute listed in one file and not the other does not fall back to the app's value - it
 * falls back to the <i>platform's</i>, silently, in one mode only.
 *
 * <p>That is how the dropdown menu came to have rounded corners in light mode and square ones in
 * dark: {@code android:popupMenuStyle} was set in the day theme alone. Nothing failed, nothing
 * logged, and it is invisible to anyone who does not switch themes.
 */
@RunWith(AndroidJUnit4.class)
public class ThemeAttributesMatchTest {

    /**
     * Attributes that must resolve to the same thing in both modes.
     *
     * <p>Shape, typeface and elevation describe the app's identity rather than its palette, so a
     * difference here is a mistake by definition. Colours are deliberately absent - those are
     * supposed to differ, and that is the whole point of having two files.
     */
    private static final int[] SAME_IN_BOTH_MODES = {
            android.R.attr.popupMenuStyle,
            android.R.attr.fontFamily,
            android.R.attr.buttonStyle,
    };

    private static Context themed(final int nightMode) {
        final Context base = getInstrumentation().getTargetContext();
        final Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.uiMode =
                (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightMode;

        return new ContextThemeWrapper(
                base.createConfigurationContext(configuration), R.style.Theme_OpenTagViewer);
    }

    private static int resolve(final Context context, final int attribute) {
        final TypedArray values = context.obtainStyledAttributes(new int[]{attribute});
        try {
            return values.getResourceId(0, 0);
        } finally {
            values.recycle();
        }
    }

    /** The headline: whatever the day theme says, the night theme says too. */
    @Test
    public void bothThemesResolveTheSameNonColourAttributes() {
        final Context light = themed(Configuration.UI_MODE_NIGHT_NO);
        final Context dark = themed(Configuration.UI_MODE_NIGHT_YES);

        for (final int attribute : SAME_IN_BOTH_MODES) {
            final int inLight = resolve(light, attribute);
            final int inDark = resolve(dark, attribute);

            assertTrue("neither theme sets attribute " + attribute
                            + ", so it is not being checked at all",
                    inLight != 0 || inDark != 0);
            assertEquals("attribute " + attribute + " differs between light and dark, so one of "
                            + "them is falling back to the platform default",
                    inLight, inDark);
        }
    }

    /**
     * And specifically, the popup is ours in both - which is the one that was wrong.
     *
     * <p>Named separately from the loop above so a failure says what the user would see rather
     * than an attribute id.
     */
    @Test
    public void thedropdownMenuIsTheAppsInBothThemes() {
        for (final int mode : new int[]{
                Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
            assertEquals("the dropdown falls back to the platform's square-cornered popup",
                    R.style.PopupMenu, resolve(themed(mode), android.R.attr.popupMenuStyle));
        }
    }
}
