package dev.wander.android.opentagviewer.ui;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.widget.ImageView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.data.model.BeaconInformation;

/**
 * The Find My accessory mark, which is the first icon here that colours itself.
 *
 * <p>Every other icon in this app is a single-colour path flattened to whatever the screen wants.
 * This one keeps Apple's blue cone - the part that makes it read as "a findable thing" at 24dp -
 * and takes its surround from the theme, so it sits on the row instead of on top of it.
 *
 * <p><b>Three ways that goes wrong silently, and one test each:</b>
 *
 * <ul>
 *   <li><b>A tint flattens it.</b> All three surfaces tinted their icon to {@code colorOutline},
 *       and left alone that turns every path in this vector one colour - a featureless grey
 *       blob, which is a perfectly good-looking icon of nothing.</li>
 *   <li><b>A theme attribute with no theme draws nothing at all.</b> The history timeline went
 *       blank exactly this way while its screenshot test stayed green, because the test passed a
 *       theme and the app did not.</li>
 *   <li><b>The silhouette disappears into the row.</b> Themed greys move with the palette, and
 *       "present, correct, invisible" is a thing this repo has shipped - a selected pin once
 *       landed at 1.23:1.</li>
 * </ul>
 */
@RunWith(AndroidJUnit4.class)
public class ThemedFindMyIconTest {

    /** Rendered at twice the 48-unit viewport, so one viewport unit is two pixels. */
    private static final int PIXELS_PER_UNIT = 2;

    /** The group transform in the drawable. Features sit this much further out than their path. */
    private static final float ARTWORK_SCALE = 1.4f;

    /** How far down from the centre, in pixels, a feature at this viewport radius lands. */
    private static int at(final double viewportRadius) {
        return (int) Math.round(viewportRadius * ARTWORK_SCALE * PIXELS_PER_UNIT);
    }

    /** Anything that is not Apple and not self-generated gets the Find My mark. */
    private static BeaconInformation aThirdPartyTag() {
        return BeaconInformation.builder()
                .beaconId("chipolo")
                .originalName("Keys")
                .vendorId(0x08C3)
                .build();
    }

    private static BeaconInformation anAppleTag() {
        return BeaconInformation.builder()
                .beaconId("airtag")
                .originalName("Wallet")
                .vendorId(76)
                .build();
    }

    private static Context themed(final boolean night) {
        final Context base = getInstrumentation().getTargetContext();

        final Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);

        return new ContextThemeWrapper(
                base.createConfigurationContext(configuration), R.style.Theme_OpenTagViewer);
    }

    private static Bitmap drawn(final Context context, final BeaconInformation beacon) {
        final ImageView view = new ImageView(context);
        getInstrumentation().runOnMainSync(() -> BeaconIcon.applyTo(view, beacon));

        final Drawable icon = view.getDrawable();
        final Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, 96, 96);

        // Through the view's own tint, so what is measured is what the screen paints - a version
        // that drew the raw drawable would pass whatever the surfaces did to it.
        if (view.getImageTintList() != null) {
            icon.setTint(view.getImageTintList().getDefaultColor());
        }
        icon.draw(new Canvas(bitmap));

        return bitmap;
    }

    private static Set<Integer> coloursIn(final Bitmap bitmap) {
        final Set<Integer> found = new HashSet<>();
        for (int x = 0; x < bitmap.getWidth(); x += 2) {
            for (int y = 0; y < bitmap.getHeight(); y += 2) {
                final int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) > 200) {
                    found.add(pixel);
                }
            }
        }
        return found;
    }

    private static double luminance(final int colour) {
        final double[] channel = new double[3];
        final int[] raw = {Color.red(colour), Color.green(colour), Color.blue(colour)};

        for (int i = 0; i < 3; i++) {
            final double v = raw[i] / 255d;
            channel[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }

        return 0.2126 * channel[0] + 0.7152 * channel[1] + 0.0722 * channel[2];
    }

    private static double contrast(final int a, final int b) {
        final double first = luminance(a);
        final double second = luminance(b);

        return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
    }

    private static int resolved(final Context context, final int attribute) {
        final TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attribute, value, true);
        return value.data;
    }

    /** <b>It draws something.</b> A themed attribute with no theme draws nothing at all. */
    @Test
    public void themarkIsNotBlank() {
        for (final boolean night : new boolean[] {false, true}) {
            final Set<Integer> colours = coloursIn(drawn(themed(night), aThirdPartyTag()));

            assertTrue("the Find My mark drew nothing in " + (night ? "dark" : "light")
                    + " mode - a theme attribute with no theme paints nothing",
                    colours.size() > 1);
        }
    }

    /**
     * <b>It is still more than one colour once the screen has had it.</b>
     *
     * <p>The failure this catches is a tint left on the view: it is applied to every path at
     * once, so the cone, the ring and the surround all become {@code colorOutline} and the icon
     * becomes a circle. Nothing throws, and it looks like a deliberate design.
     */
    @Test
    public void thetintDoesNotFlattenItIntoABlob() {
        for (final boolean night : new boolean[] {false, true}) {
            final Set<Integer> colours = coloursIn(drawn(themed(night), aThirdPartyTag()));

            assertTrue("the Find My mark came out in " + colours.size() + " colour(s) in "
                            + (night ? "dark" : "light") + " mode; a tint has flattened it",
                    colours.size() >= 4);
        }
    }

    /** The blue that makes it recognisable survives both themes untouched. */
    @Test
    public void thecomeIsStillApplesBlue() {
        for (final boolean night : new boolean[] {false, true}) {
            assertTrue("the cone's blue was recoloured in " + (night ? "dark" : "light") + " mode",
                    coloursIn(drawn(themed(night), aThirdPartyTag()))
                            .contains(Color.parseColor("#2979ff")));
        }
    }

    /**
     * <b>The silhouette carries against the row it sits on</b>, in both modes.
     *
     * <p>3:1 is the WCAG floor for something that is not text. The outer disc is
     * {@code colorOutline}, and the row behind it is {@code colorSurface}.
     */
    @Test
    public void thesilhouetteClearsThreeToOneAgainstTheRow() {
        for (final boolean night : new boolean[] {false, true}) {
            final Context context = themed(night);

            final int silhouette = resolved(context, com.google.android.material.R.attr.colorOutline);
            final int row = resolved(context, com.google.android.material.R.attr.colorSurface);
            final double ratio = contrast(silhouette, row);

            assertTrue("the Find My mark sits at " + String.format("%.2f", ratio) + ":1 against"
                            + " the row in " + (night ? "dark" : "light") + " mode",
                    ratio >= 3.0);
        }
    }

    /**
     * The tonal ladder inside the disc, <b>measured off the pixels rather than off the theme</b>.
     *
     * <p>The first version of this resolved three theme attributes and compared those, which
     * asserted something true about Material's palette and nothing at all about the icon. It
     * passed happily while the ordering was inverted and the disc had gone dark-to-white inward.
     *
     * <p>Sampled straight down from the centre, which is the one direction the cone does not
     * cross. The ordering is Apple's: the ring is the lightest, the inner disc sits between it
     * and the surround, and the surround is the darkest.
     */
    @Test
    public void theringsInsideItKeepApplesTonalOrdering() {
        for (final boolean night : new boolean[] {false, true}) {
            final Bitmap drawn = drawn(themed(night), aThirdPartyTag());
            final String mode = night ? "dark" : "light";

            final int inner = drawn.getPixel(48, 48 + at(8));
            final int ring = drawn.getPixel(48, 48 + at(10.5));
            final int surround = drawn.getPixel(48, 48 + at(13));

            assertTrue("the ring is not lighter than the inner disc in " + mode + " mode",
                    luminance(ring) > luminance(inner));
            assertTrue("the inner disc is not lighter than the surround in " + mode + " mode",
                    luminance(inner) > luminance(surround));
            assertTrue("the ring and the surround are the same colour in " + mode + " mode",
                    contrast(ring, surround) > 1.2);
        }
    }

    /**
     * The white ring around the centre dot is still visible against what it sits on.
     *
     * <p><b>This is what the first attempt broke.</b> Mapping the inner disc to
     * {@code colorSurfaceVariant} made it near-white in light mode, and a near-white ring on a
     * near-white disc is not a subtle ring - it is a missing one.
     *
     * <p>The bar is 1.5:1 rather than 3:1 on purpose: Apple's own green sits at about 1.7:1
     * here, so demanding more would mean failing the icon this one is modelled on. What is being
     * caught is the ring disappearing, not the ring being quiet.
     */
    @Test
    public void thewhiteRingIsStillVisibleAgainstTheInnerDisc() {
        for (final boolean night : new boolean[] {false, true}) {
            final Bitmap drawn = drawn(themed(night), aThirdPartyTag());

            final int ring = drawn.getPixel(48, 48 + at(3.5));
            final int inner = drawn.getPixel(48, 48 + at(8));
            final double ratio = contrast(ring, inner);

            assertTrue("the white ring sits at " + String.format("%.2f", ratio) + ":1 against the"
                            + " inner disc in " + (night ? "dark" : "light") + " mode",
                    ratio > 1.5);
        }
    }

    /**
     * <b>The recycling case.</b>
     *
     * <p>The device list is a RecyclerView, so a row that showed a Find My accessory is handed
     * straight to the next tag. A version of this that only cleared the tint would leave that row
     * painting an Apple logo with none - and it would only happen to whichever rows were
     * recycled, which reads as a scrolling bug rather than as a missing else.
     */
    @Test
    public void arecycledRowGetsItsTintBack() {
        final Context context = themed(false);
        final ImageView view = new ImageView(context);

        getInstrumentation().runOnMainSync(() -> {
            BeaconIcon.applyTo(view, aThirdPartyTag());
            assertNull("the Find My mark must not be tinted", view.getImageTintList());

            BeaconIcon.applyTo(view, anAppleTag());
        });

        assertNotNull("a recycled row must get its tint back for a flat icon",
                view.getImageTintList());
    }

    /**
     * <b>It fills its box like the other icons do.</b>
     *
     * <p>The artwork runs from 8 to 40 in a 48-unit viewport, so drawn as-authored it covers two
     * thirds of its width while {@code apple.xml} covers about ninety per cent of its own. Beside
     * each other in one list that does not read as two different icons - it reads as the same
     * icon at the wrong size, which is a thing people notice and cannot name.
     *
     * <p>Measured against the Apple mark rather than against a number, because the number that
     * matters is the comparison: whatever either icon is changed to, they have to keep looking
     * like they belong to one set.
     */
    @Test
    public void itfillsItsBoxAsMuchAsTheAppleMarkDoes() {
        final Context context = themed(false);

        final double findMy = coveredFractionOf(drawn(context, aThirdPartyTag()));
        final double apple = coveredFractionOf(drawn(context, anAppleTag()));

        assertTrue("the Find My mark covers " + Math.round(findMy * 100) + "% of its box against"
                        + " the Apple mark's " + Math.round(apple * 100) + "%; it will read as"
                        + " the same icon at the wrong size",
                findMy >= apple * 0.8);
    }

    /** How much of the box has something opaque drawn on it. */
    private static double coveredFractionOf(final Bitmap bitmap) {
        int covered = 0;
        int total = 0;

        for (int x = 0; x < bitmap.getWidth(); x++) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                total++;
                if (Color.alpha(bitmap.getPixel(x, y)) > 200) {
                    covered++;
                }
            }
        }

        return (double) covered / total;
    }

    /** Both modes, for a person to look at. Not an assertion - everything above is. */
    @Test
    public void picturesOfIt() throws IOException {
        write(drawn(themed(false), aThirdPartyTag()), "findmy_icon-light.png");
        write(drawn(themed(true), aThirdPartyTag()), "findmy_icon-dark.png");
        write(drawn(themed(false), anAppleTag()), "apple_icon-light.png");
        write(drawn(themed(true), anAppleTag()), "apple_icon-dark.png");
    }

    private static void write(final Bitmap bitmap, final String name) throws IOException {
        final String directory = androidx.test.platform.app.InstrumentationRegistry
                .getArguments().getString("additionalTestOutputDir");
        if (directory == null) {
            return;
        }

        try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
