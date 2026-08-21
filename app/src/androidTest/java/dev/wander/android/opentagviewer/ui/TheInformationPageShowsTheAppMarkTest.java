package dev.wander.android.opentagviewer.ui;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.InformationActivity;
import dev.wander.android.opentagviewer.R;

/**
 * The app mark on the Information page draws something.
 *
 * <p><b>Not "the ImageView is there" - that it puts ink on the screen.</b> The mark is
 * {@code opentagviewer_icon_themed}, a vector whose fills are <i>theme attributes</i>
 * ({@code colorPrimary} and {@code colorOnPrimaryFixedVariant}), and a theme attribute cannot be
 * resolved without a theme. Load it through a context that has none and it draws as nothing at
 * all: no error, no warning, an empty square where the logo should be.
 *
 * <p>That has happened in this app before. The history timeline went blank exactly this way,
 * and its screenshot test stayed green throughout, because the test passed a theme and the app
 * did not. Rule 12 in AGENTS.md is written around that incident, and this is the same drawable
 * risk on a new screen.
 *
 * <p><b>So the assertion counts pixels with real opacity</b>, in both themes. A mark that
 * resolves under one and not the other is precisely the failure this is looking for -
 * {@code drawable-night} is a separate file picking different tones, so either can break alone.
 *
 * <p><b>It runs the real screen rather than inflating the layout</b>, which is forced rather
 * than preferred: the mark is set with {@code app:srcCompat}, and AppCompat only resolves that
 * through a view inflater an {@code AppCompatActivity} installs. Inflated against a bare themed
 * context the view comes out as a plain {@code ImageView} with {@code srcCompat} ignored and no
 * drawable at all - a failure describing a bug that exists only in the test. Asking the running
 * screen is the more honest question anyway.
 */
@RunWith(AndroidJUnit4.class)
public class TheInformationPageShowsTheAppMarkTest {

    private int nightModeBefore;

    @Before
    public void rememberTheDevicesTheme() {
        this.nightModeBefore = AppCompatDelegate.getDefaultNightMode();
    }

    /**
     * Put it back. This flips a process-wide default, and leaving it flipped hands the next
     * test in the run a device in whichever theme this one finished in.
     */
    @After
    public void restoreTheDevicesTheme() {
        getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setDefaultNightMode(this.nightModeBefore));
        getInstrumentation().waitForIdleSync();
    }

    /**
     * <b>The mark is on the page at all.</b>
     *
     * <p>Thin, and it is the one that fails if somebody removes the view or renames the id -
     * neither of which stops the app compiling.
     */
    @Test
    public void theappMarkIsOnTheInformationPage() {
        try (ActivityScenario<InformationActivity> screen = this.openTheInformationPage(false)) {
            screen.onActivity(activity -> assertNotNull(
                    "the Information page has no app mark on it",
                    activity.findViewById(R.id.appLogo)));
        }
    }

    /** <b>And it draws ink, in the light theme.</b> */
    @Test
    public void themarkDrawsSomethingInTheLightTheme() {
        this.assertTheMarkIsVisible(false);
    }

    /**
     * <b>And in the dark one, which is a different drawable.</b>
     *
     * <p>{@code drawable-night} carries its own copy, so this is not the same file being asked
     * twice - the night version picks different tones so the extruded side stays darker than
     * the face. Either could be broken on its own.
     */
    @Test
    public void themarkDrawsSomethingInTheDarkTheme() {
        this.assertTheMarkIsVisible(true);
    }

    // ------------------------------------------------------------------ the work

    private void assertTheMarkIsVisible(final boolean night) {
        final int[] inkPixels = {0};

        try (ActivityScenario<InformationActivity> screen = this.openTheInformationPage(night)) {
            screen.onActivity(activity -> {
                final ImageView mark = activity.findViewById(R.id.appLogo);
                assertNotNull("the Information page has no app mark on it", mark);

                final Drawable drawn = mark.getDrawable();
                assertNotNull("the app mark has no drawable, so nothing will be shown", drawn);
                assertTrue("the app mark measured " + mark.getWidth() + "x" + mark.getHeight()
                                + ", so there is nowhere for it to draw",
                        mark.getWidth() > 0 && mark.getHeight() > 0);

                final Bitmap rendered = Bitmap.createBitmap(
                        mark.getWidth(), mark.getHeight(), Bitmap.Config.ARGB_8888);
                drawn.setBounds(0, 0, rendered.getWidth(), rendered.getHeight());
                drawn.draw(new Canvas(rendered));

                for (int x = 0; x < rendered.getWidth(); x += 2) {
                    for (int y = 0; y < rendered.getHeight(); y += 2) {
                        // Any pixel with real opacity. The vector is drawn onto a transparent
                        // bitmap, so an unresolved attribute and nothing-drawn are both zero.
                        if (((rendered.getPixel(x, y) >>> 24) & 0xFF) > 0x10) {
                            inkPixels[0]++;
                        }
                    }
                }
                rendered.recycle();
            });
        }

        assertTrue("the app mark drew " + inkPixels[0] + " pixels in the "
                        + (night ? "dark" : "light") + " theme, which means its theme-attribute"
                        + " fills resolved to nothing - the logo is an empty square on screen,"
                        + " with no error anywhere",
                inkPixels[0] > 100);
    }

    /**
     * The real screen, in the theme asked for.
     *
     * <p><b>An activity rather than an inflated layout, and that is not incidental.</b> The mark
     * is set with {@code app:srcCompat}, which AppCompat resolves through a view inflater that
     * an {@code AppCompatActivity} installs. Inflate the same layout against a bare themed
     * context and the {@code ImageView} comes out as a plain one with {@code srcCompat}
     * ignored - {@code getDrawable()} returns null, and the test fails describing a bug that is
     * only in the test.
     *
     * <p>Which makes this the more honest shape anyway: it asks what the app shows, not what a
     * layout file could show under ideal conditions.
     */
    private ActivityScenario<InformationActivity> openTheInformationPage(final boolean night) {
        getInstrumentation().runOnMainSync(() -> AppCompatDelegate.setDefaultNightMode(
                night ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO));
        getInstrumentation().waitForIdleSync();

        return ActivityScenario.launch(InformationActivity.class);
    }
}
