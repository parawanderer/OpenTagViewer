package dev.wander.android.opentagviewer.ui;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;

import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.color.MaterialColors;

import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.R;

/**
 * The "and erase this too" box that the Remove and Logout dialogs now carry, inflated the way
 * they inflate it: inside a Material 3 alert dialog's theme, over the activities' own theme, in
 * both modes.
 *
 * <p>It is the only thing on screen standing between the user and an erase that cannot be undone,
 * so it has to be there, unticked, and readable against the dialog it sits in.
 */
@RunWith(AndroidJUnit4.class)
public class AlsoEraseDialogLayoutTest {

    @Test
    public void itInflatesUntickedAndLegibleInLightMode() {
        this.check(Configuration.UI_MODE_NIGHT_NO);
    }

    /** Half of what breaks only breaks in one mode. */
    @Test
    public void itInflatesUntickedAndLegibleInDarkMode() {
        this.check(Configuration.UI_MODE_NIGHT_YES);
    }

    private void check(final int nightMode) {
        final Context target = getInstrumentation().getTargetContext();
        final Configuration config = new Configuration(target.getResources().getConfiguration());
        config.uiMode = nightMode | Configuration.UI_MODE_TYPE_NORMAL;
        final Context activity = new ContextThemeWrapper(
                target.createConfigurationContext(config),
                R.style.Theme_OpenTagViewer_GenericGreyishActivity);
        final Context dialog = new ContextThemeWrapper(activity,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog);

        final View[] root = new View[1];
        getInstrumentation().runOnMainSync(() -> {
            root[0] = LayoutInflater.from(dialog).inflate(R.layout.dialog_also_erase, null);
            ((CheckBox) root[0].findViewById(R.id.dialog_also_erase))
                    .setText(R.string.logout_also_erase_data);
            root[0].measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        });

        final CheckBox box = root[0].findViewById(R.id.dialog_also_erase);
        assertNotNull("the id both screens look up does not resolve", box);
        assertFalse("erasing must be opted into, not out of", box.isChecked());
        assertTrue("the box measured to nothing", box.getMeasuredHeight() > 0);

        // An M3 alert dialog draws on colorSurfaceContainerHigh.
        final int background = MaterialColors.getColor(dialog,
                com.google.android.material.R.attr.colorSurfaceContainerHigh, "no dialog surface");
        final double ratio = ColorUtils.calculateContrast(
                box.getCurrentTextColor() | 0xFF000000, background | 0xFF000000);
        assertTrue("the label is " + ratio + ":1 against the dialog, under the 4.5:1 AA needs",
                ratio >= 4.5);
    }
}
