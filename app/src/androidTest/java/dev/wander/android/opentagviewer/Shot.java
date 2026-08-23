package dev.wander.android.opentagviewer;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;

/**
 * A picture of whatever is on the screen, dialogs included.
 *
 * <p><b>Whole-screen, unlike the {@code view.draw(canvas)} pattern used elsewhere.</b> That one
 * needs the view to be in the activity's own hierarchy, and a dialog is not - it lives in its
 * own window, so drawing the activity produces the page behind it with a hole where the dialog
 * should be. {@code UiAutomation} photographs the compositor's output instead, which is what a
 * person actually sees.
 *
 * <p>Writes into the directory AGP passes as {@code additionalTestOutputDir} and does nothing
 * when there isn't one, so it is free in an ordinary run. Names are
 * {@code <subject>-<variant>.png} so {@code .claude/skills/device-screenshots/sheet.py} groups
 * them.
 *
 * <p>As ever: a screenshot is not an assertion. It explains why a failure looks wrong; it cannot
 * fail. Assert the thing that matters as well.
 */
public final class Shot {

    private Shot() {}

    private static final String TAG = "Shot";

    public static void ofTheScreen(final String name) {
        final String dir = InstrumentationRegistry.getArguments()
                .getString("additionalTestOutputDir");
        if (dir == null) {
            return;
        }

        try {
            final Bitmap bitmap =
                    InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if (bitmap == null) {
                Log.w(TAG, "the screen could not be photographed for " + name);
                return;
            }
            try (FileOutputStream out =
                         new FileOutputStream(new File(new File(dir), name + ".png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            bitmap.recycle();
        } catch (final Exception e) {
            // A screenshot explains a failure; it is never the reason for one.
            Log.w(TAG, "could not write " + name, e);
        }
    }
}
