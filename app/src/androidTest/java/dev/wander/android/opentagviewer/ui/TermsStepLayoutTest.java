package dev.wander.android.opentagviewer.ui;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import dev.wander.android.opentagviewer.R;

/**
 * The terms step, inflated and drawn without an activity, an account or a network.
 *
 * <p>Cheap cover for the failures that do not throw: an id the code looks up that no longer
 * resolves, a `?attr/` the theme has no value for, a view that measures to nothing, or text that
 * comes out unreadable in one of the two themes. Every one of those leaves a green build and a
 * screen that is simply wrong - and this screen is one somebody has to *read*, so unreadable is
 * not cosmetic.
 */
@RunWith(AndroidJUnit4.class)
public class TermsStepLayoutTest {

    /** Every id {@code AppleLoginActivity} looks up for this step. */
    private static final int[] IDS_THE_CODE_USES = {
            R.id.login_terms_container,
            R.id.login_terms_title,
            R.id.login_terms_intro,
            R.id.login_terms_counter,
            R.id.login_terms_scroll,
            R.id.login_terms_text,
            R.id.login_terms_cannot_accept,
            R.id.login_terms_agree_button,
            R.id.login_terms_back_button,
    };

    private static View inflate(final boolean night) {
        final Context base = getInstrumentation().getTargetContext();

        final Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);

        final Context themed = new ContextThemeWrapper(
                base.createConfigurationContext(configuration), R.style.Theme_OpenTagViewer);

        // The step alone, not `activity_apple_login`, which includes the settings panel and
        // cannot inflate outside an activity. Splitting it out is what made this test possible.
        return LayoutInflater.from(themed).inflate(R.layout.login_terms_step, null);
    }

    /**
     * It inflates at all.
     *
     * <p>A missing {@code ?attr/} or a style this theme does not define throws here and nowhere
     * else until the one screen nobody opened.
     */
    @Test
    public void itinflatesInBothThemes() {
        assertNotNull(inflate(false));
        assertNotNull(inflate(true));
    }

    /**
     * Every id the code looks up resolves.
     *
     * <p>A renamed id still compiles. {@code findViewById} then returns null and the step
     * half-works - or throws on the first line that touches it, which for this screen is after
     * the user has already typed their password.
     */
    @Test
    public void everyIdTheCodeLooksUpIsThere() {
        final View root = inflate(false);

        for (final int id : IDS_THE_CODE_USES) {
            assertNotNull("an id AppleLoginActivity looks up is missing from the layout",
                    root.findViewById(id));
        }
    }

    /** The document goes in a scrolling box - without one, a long contract has no bottom. */
    @Test
    public void thedocumentIsInsideSomethingThatScrolls() {
        final View root = inflate(false);

        final View text = root.findViewById(R.id.login_terms_text);
        assertTrue("the terms text must sit inside a ScrollView, or the end of a long document"
                        + " cannot be reached and the Agree button is pushed off screen",
                text.getParent() instanceof ScrollView);
    }

    /**
     * The step measures to something, with a document in it.
     *
     * <p>{@code wrap_content} on a view that measures to nothing is a blank screen that throws
     * no error at all.
     */
    @Test
    public void itmeasuresToSomethingWithADocumentInIt() {
        final View root = inflate(false);
        final View step = root.findViewById(R.id.login_terms_container);
        step.setVisibility(View.VISIBLE);

        ((TextView) root.findViewById(R.id.login_terms_text)).setText(longDocument());

        measureAndLayout(root);

        assertTrue("the terms step measured to nothing", step.getHeight() > 0);
        assertTrue("the scrolling box has no height, so no document would be visible",
                root.findViewById(R.id.login_terms_scroll).getHeight() > 0);
    }

    /**
     * The back arrow is a drawable that actually paints something.
     *
     * <p>Asserted on the <b>drawable</b>, not on the button. A vector whose colour comes from a
     * theme attribute can load, measure and still draw nothing - which is how the history
     * timeline went blank while its screenshot test stayed green - and that is a property of the
     * drawable and the theme, which is exactly what this harness can check.
     *
     * <p>It deliberately does not assert pixels on the button itself. A {@code MaterialButton}
     * laid out by hand, outside a window, does not finish placing its icon, so a blank render
     * here says something about this scaffolding rather than about the app. Whether the button
     * shows up in the real activity is what {@code AcceptingTermsFlowTest} is for.
     */
    @Test
    public void thebackArrowIsADrawableThatPaints() {
        for (final boolean night : new boolean[] {false, true}) {
            final Context themed = inflate(night).getContext();

            // Loaded with the theme, which is how the app loads it - passing null here is the
            // mistake that makes a theme-attribute vector draw as nothing.
            final android.graphics.drawable.Drawable arrow =
                    androidx.core.content.res.ResourcesCompat.getDrawable(
                            themed.getResources(), R.drawable.arrow_back_24px, themed.getTheme());

            assertNotNull("the back arrow drawable is missing", arrow);
            assertTrue("the back arrow has no intrinsic size", arrow.getIntrinsicWidth() > 0);

            final Bitmap bitmap = Bitmap.createBitmap(
                    arrow.getIntrinsicWidth(), arrow.getIntrinsicHeight(),
                    Bitmap.Config.ARGB_8888);
            arrow.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
            arrow.draw(new Canvas(bitmap));

            boolean painted = false;
            for (int x = 0; x < bitmap.getWidth() && !painted; x++) {
                for (int y = 0; y < bitmap.getHeight(); y++) {
                    if (Color.alpha(bitmap.getPixel(x, y)) != 0) {
                        painted = true;
                        break;
                    }
                }
            }

            assertTrue("the back arrow drew nothing at all in "
                    + (night ? "dark" : "light") + " mode", painted);
        }
    }

    /**
     * The text is readable against what is behind it, in both themes.
     *
     * <p>A number rather than a screenshot, because "looks fine" is exactly what a 1.2:1 contrast
     * ratio looks like to whoever picked the colours. 4.5:1 is WCAG AA for body text, which this
     * is: a legal document nobody can skim.
     */
    @Test
    public void thedocumentIsReadableInBothThemes() {
        assertReadable(false);
        assertReadable(true);
    }

    private static void assertReadable(final boolean night) {
        final View root = inflate(night);
        final TextView text = root.findViewById(R.id.login_terms_text);
        final View box = root.findViewById(R.id.login_terms_scroll);

        final int foreground = text.getCurrentTextColor();
        final int background = resolvedBackground(box, root);

        final double ratio = contrast(foreground, background);

        assertTrue(String.format(
                        "terms text is %.2f:1 against its box in %s mode - AA for body text is"
                                + " 4.5:1, and this is a document somebody has to read",
                        ratio, night ? "dark" : "light"),
                ratio >= 4.5d);
    }

    /** Renders both themes to the output directory, to explain what a failure looks like. */
    @Test
    public void itlooksLikeSomethingWorthReading() throws IOException {
        write(render(false), "terms_step-light.png");
        write(render(true), "terms_step-dark.png");
    }

    private static Bitmap render(final boolean night) {
        final View root = inflate(night);
        root.findViewById(R.id.login_terms_container).setVisibility(View.VISIBLE);
        ((TextView) root.findViewById(R.id.login_terms_text)).setText(longDocument());

        measureAndLayout(root);

        final View step = root.findViewById(R.id.login_terms_container);
        final Bitmap bitmap = Bitmap.createBitmap(
                Math.max(step.getWidth(), 1), Math.max(step.getHeight(), 1),
                Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        // Composited rather than drawn onto transparency: this layout has no background of its
        // own, and straight to RGB every transparent pixel comes out black - which looks like a
        // catastrophic bug that is not there.
        canvas.drawColor(night ? Color.BLACK : Color.WHITE);
        step.draw(canvas);

        return bitmap;
    }

    private static void measureAndLayout(final View root) {
        final int width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        final int height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);

        root.measure(width, height);
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
    }

    private static String longDocument() {
        final StringBuilder document = new StringBuilder(
                "ICLOUD TERMS OF SERVICE\n-----------------------\n\n");
        for (int i = 0; i < 12; i++) {
            document.append("This agreement covers your use of the service, and goes on at the")
                    .append(" length these documents go on at.\n\n");
        }
        return document.append("  - You must be of legal age.").toString();
    }

    /** The nearest ancestor background that is actually painted, since views inherit nothing. */
    private static int resolvedBackground(final View from, final View root) {
        View current = from;
        while (current != null) {
            if (current.getBackground() instanceof android.graphics.drawable.ColorDrawable) {
                return ((android.graphics.drawable.ColorDrawable)
                        current.getBackground()).getColor();
            }
            if (current == root || !(current.getParent() instanceof View)) {
                break;
            }
            current = (View) current.getParent();
        }

        // Nothing painted between here and the root, so what shows through is the window
        // background - which is what the activity would paint.
        final android.util.TypedValue value = new android.util.TypedValue();
        from.getContext().getTheme()
                .resolveAttribute(android.R.attr.colorBackground, value, true);
        return value.data;
    }

    private static double contrast(final int foreground, final int background) {
        final double first = luminance(foreground);
        final double second = luminance(background);
        final double lighter = Math.max(first, second);
        final double darker = Math.min(first, second);

        return (lighter + 0.05d) / (darker + 0.05d);
    }

    private static double luminance(final int color) {
        return 0.2126d * channel(Color.red(color))
                + 0.7152d * channel(Color.green(color))
                + 0.0722d * channel(Color.blue(color));
    }

    private static double channel(final int value) {
        final double proportion = value / 255d;
        return proportion <= 0.03928d
                ? proportion / 12.92d
                : Math.pow((proportion + 0.055d) / 1.055d, 2.4d);
    }

    private static void write(final Bitmap bitmap, final String name) throws IOException {
        final String directory = androidx.test.platform.app.InstrumentationRegistry
                .getArguments().getString("additionalTestOutputDir");
        if (directory == null) {
            return;
        }

        final File file = new File(directory, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** Guards the guard: a layout with no children would pass everything above vacuously. */
    @Test
    public void thestepActuallyHasContent() {
        final View step = inflate(false).findViewById(R.id.login_terms_container);

        assertTrue(step instanceof ViewGroup);
        assertEquals("if this drops, the assertions above are checking an empty box",
                9, IDS_THE_CODE_USES.length);
        assertTrue(((ViewGroup) step).getChildCount() >= 5);
    }
}
