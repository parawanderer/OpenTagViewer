package dev.wander.android.opentagviewer;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Log;
import android.view.View;

import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.color.MaterialColors;

import java.io.File;
import java.io.FileOutputStream;

/**
 * A picture of what is on screen, for looking at afterwards.
 *
 * <p><b>{@link #ofTheScreen} needs a device with a display, and says so rather than lying.</b>
 * {@code UiAutomation.takeScreenshot()} photographs the compositor's output, which is the only
 * way to capture an activity and a dialog together - and on the headless managed device it
 * returns a <i>completely black bitmap</i>, without failing and without warning. Five screens
 * were captured that way and every one was black; nothing in the run said so, and the pictures
 * were believed to be evidence until somebody opened them.
 *
 * <p>So it now checks. A frame that came back entirely one colour is not written at all: a
 * missing file gets noticed, a black one gets mistaken for a screen that renders nothing.
 *
 * <p>{@link #of(Activity, String)} and {@link #of(Dialog, String)} draw a window's view hierarchy
 * instead, which works on both kinds of device - and is what every other screenshot test here
 * does. The catch is that a window is all they can draw: an activity drawn while a dialog is up
 * is the page behind it, with a hole where the dialog should be.
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

    /** Everything the compositor is showing, dialogs included. Needs a display. */
    public static void ofTheScreen(final String name) {
        final String dir = outputDir();
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

            if (isBlank(bitmap)) {
                // The headless case. Writing it would produce a black PNG that looks like a
                // rendering bug in the app rather than an absent display.
                Log.w(TAG, "the screenshot for " + name + " came back blank, which means this"
                        + " device has no display - use Shot.of(activity/dialog) instead");
                bitmap.recycle();
                return;
            }

            write(bitmap, dir, name);
        } catch (final Exception e) {
            // A screenshot explains a failure; it is never the reason for one.
            Log.w(TAG, "could not write " + name, e);
        }
    }

    /** The activity's own window, drawn rather than photographed. Works headless. */
    public static void of(final Activity activity, final String name) {
        draw(activity.getWindow().getDecorView(), name);
    }

    /**
     * A dialog's window, which is a separate one from the activity's.
     *
     * <p>Drawing the activity while a dialog is up gives the page behind it and a hole where the
     * dialog should be - they are different windows, and on a device with no compositor there is
     * nothing to put them back together.
     */
    public static void of(final Dialog dialog, final String name) {
        if (dialog.getWindow() == null) {
            Log.w(TAG, "the dialog has no window yet, so nothing was drawn for " + name);
            return;
        }
        draw(dialog.getWindow().getDecorView(), name);
    }

    private static void draw(final View view, final String name) {
        final String dir = outputDir();
        if (dir == null) {
            return;
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                if (view.getWidth() == 0 || view.getHeight() == 0) {
                    // Not laid out yet, and a 0x0 bitmap throws. Said out loud rather than
                    // skipped quietly, for the same reason the blank check exists.
                    Log.w(TAG, "nothing to draw for " + name + " - the view measured 0x0");
                    return;
                }

                final Bitmap bitmap = Bitmap.createBitmap(
                        view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
                final Canvas canvas = new Canvas(bitmap);

                // Several layouts here have no background of their own, and a transparent pixel
                // flattened into a PNG reads as black - the very thing this class exists to stop
                // being mistaken for a real screen.
                canvas.drawColor(MaterialColors.getColor(
                        view, com.google.android.material.R.attr.colorSurface));
                view.draw(canvas);

                write(bitmap, dir, name);
            } catch (final Exception e) {
                Log.w(TAG, "could not write " + name, e);
            }
        });
    }

    /**
     * Whether every pixel is the same colour, sampled on a grid.
     *
     * <p>Sampled rather than exhaustive: a 1080x2400 frame is 2.6 million pixels and this runs
     * between steps of a UI test. A grid catches the case that matters - a frame that is entirely
     * one colour - and a real screen differs somewhere within a few samples.
     */
    private static boolean isBlank(final Bitmap bitmap) {
        final int first = bitmap.getPixel(0, 0);

        for (int x = 0; x < bitmap.getWidth(); x += Math.max(1, bitmap.getWidth() / 32)) {
            for (int y = 0; y < bitmap.getHeight(); y += Math.max(1, bitmap.getHeight() / 32)) {
                if (bitmap.getPixel(x, y) != first) {
                    return false;
                }
            }
        }

        return true;
    }

    private static String outputDir() {
        return InstrumentationRegistry.getArguments().getString("additionalTestOutputDir");
    }

    private static void write(final Bitmap bitmap, final String dir, final String name)
            throws java.io.IOException {
        try (FileOutputStream out = new FileOutputStream(new File(new File(dir), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
}
