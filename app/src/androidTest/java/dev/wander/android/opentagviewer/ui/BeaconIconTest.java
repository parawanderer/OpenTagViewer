package dev.wander.android.opentagviewer.ui;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.data.model.BeaconInformation;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Which icon a tag with no emoji gets, and whether that icon actually draws anything.
 *
 * <p>Both halves matter, and the second is the one that has gone wrong here before. A vector that
 * resolves, measures and reports no error can still paint nothing - the history timeline shipped
 * blank exactly that way, with a screenshot test staying green because it loaded the drawables
 * with a theme and the app did not. So these load them the way the app does, and then look at the
 * pixels.
 */
@RunWith(AndroidJUnit4.class)
public class BeaconIconTest {

    private static final int APPLE_VENDOR_ID = 76;

    /** Where AGP wants rendered images, so they come back to the host after the run. */
    private static File outputDir;

    @BeforeClass
    public static void resolveOutputDir() {
        final String fromAgp = InstrumentationRegistry.getArguments()
                .getString("additionalTestOutputDir");

        outputDir = fromAgp != null
                ? new File(fromAgp)
                : getInstrumentation().getTargetContext().getExternalFilesDir("icon-shots");

        if (outputDir != null && !outputDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outputDir.mkdirs();
        }
    }

    private Context context() {
        return getInstrumentation().getTargetContext();
    }

    private static BeaconInformation beacon(final boolean custom, final int vendorId) {
        return BeaconInformation.builder()
                .beaconId("b-1")
                .customAccessory(custom)
                .vendorId(vendorId)
                .build();
    }

    // ---------------------------------------------------------------- which icon

    /** The case the whole change exists for: it is not Apple's logo any more. */
    @Test
    public void aselfGeneratedTagDoesNotBorrowApplesLogo() {
        assertEquals(R.drawable.tag_self_generated,
                BeaconIcon.forBeacon(beacon(true, 0)));
    }

    @Test
    public void applesOwnHardwareStillGetsApplesLogo() {
        assertEquals(R.drawable.apple,
                BeaconIcon.forBeacon(beacon(false, APPLE_VENDOR_ID)));
    }

    /**
     * <b>An iPad is drawn as a tablet, not as a generic Find My accessory.</b>
     *
     * <p>The decision used to be the vendor id alone, and that does not identify these at all: an
     * AirTag's record carries 76 and an iPad's carries -1, so every iPhone, iPad and Mac on the
     * account fell through to the third-party branch. An account read is mostly those, so the
     * device list came back as a column of identical unknown tags - which is what @parawanderer
     * saw the first time a real account was imported.
     *
     * <p>The model is the direct evidence: an accessory leaves it empty, a device fills it with
     * an Apple model identifier.
     */
    @Test
    public void anappleDeviceIsDrawnAsWhatItIs() {
        assertEquals(R.drawable.tablet_24px, BeaconIcon.forBeacon(withModel("iPad13,18")));
        assertEquals(R.drawable.laptop_24px, BeaconIcon.forBeacon(withModel("MacBookAir10,1")));
        assertEquals(R.drawable.smartphone_24px, BeaconIcon.forBeacon(withModel("iPhone15,2")));
    }

    /** And specifically not the accessory mark, which is the behaviour being replaced. */
    @Test
    public void anappleDeviceIsNotDrawnAsAThirdPartyAccessory() {
        for (final String model : new String[] {"iPad13,18", "MacBookAir10,1", "iPhone15,2"}) {
            assertNotEquals("a " + model + " is one of the owner's own devices, not a tag"
                            + " somebody else made",
                    R.drawable.findmy_accessory, BeaconIcon.forBeacon(withModel(model)));
        }
    }

    /**
     * Apple hardware this has no drawing of gets Apple's logo rather than a guess.
     *
     * <p>A Watch, a Vision Pro, something not shipped yet: "one of your Apple devices" is true,
     * and drawing it as a third-party tag would not be.
     */
    @Test
    public void appleHardwareWithNoPictureOfItsOwnStillLooksApple() {
        assertEquals(R.drawable.apple, BeaconIcon.forBeacon(withModel("Watch6,1")));
    }

    /**
     * <b>An accessory's empty model must not be mistaken for a device.</b>
     *
     * <p>The one that keeps the change honest in the other direction: every AirTag has
     * {@code model} empty, so a prefix check that treated empty as a match would draw the entire
     * account as iPads.
     */
    @Test
    public void anaccessoryWithNoModelIsStillAnAccessory() {
        assertEquals(R.drawable.findmy_accessory, BeaconIcon.forBeacon(withModel("")));
        assertEquals(R.drawable.findmy_accessory, BeaconIcon.forBeacon(withModel(null)));
    }

    private static BeaconInformation withModel(final String model) {
        return BeaconInformation.builder()
                .beaconId("a-tag")
                .originalName("Something")
                .model(model)
                // -1 is what a real device's record carries, and is the whole reason the vendor
                // id cannot be what decides this.
                .vendorId(-1)
                .build();
    }

    /** A Chipolo, a Pebblebee - findable, paired, and not made by Apple. */
    @Test
    public void athirdPartyTagGetsTheFindableIcon() {
        assertEquals(R.drawable.findmy_accessory,
                BeaconIcon.forBeacon(beacon(false, 0x009E)));
    }

    /**
     * An unknown vendor is third-party, not Apple.
     *
     * <p>The honest way round. Claiming Apple for something unidentified is precisely the wrong
     * answer this replaces, and it is the case a plist with no vendor id lands in.
     */
    @Test
    public void anunknownVendorIsNotAssumedToBeApple() {
        assertNotEquals(R.drawable.apple, BeaconIcon.forBeacon(beacon(false, 0)));
    }

    /**
     * A self-generated tag stays self-generated even if something put a vendor id on it.
     *
     * <p>Order matters: it has no Apple provenance whatever its fields say, and the checks are
     * not mutually exclusive by construction.
     */
    @Test
    public void beingSelfGeneratedWinsOverAnyVendorId() {
        assertEquals(R.drawable.tag_self_generated,
                BeaconIcon.forBeacon(beacon(true, APPLE_VENDOR_ID)));
    }

    @Test
    public void thethreeIconsAreActuallyDifferent() {
        assertNotEquals(R.drawable.apple, R.drawable.tag_self_generated);
        assertNotEquals(R.drawable.apple, R.drawable.findmy_accessory);
        assertNotEquals(R.drawable.tag_self_generated, R.drawable.findmy_accessory);
    }

    // ---------------------------------------------------------------- does it draw

    /**
     * Every icon paints something, in both themes.
     *
     * <p>Loaded through {@link AppCompatResources}, which is what the screens use - not
     * {@code ResourcesCompat.getDrawable(res, id, null)}, whose null theme is what rendered the
     * timeline invisible while its test passed.
     */
    @Test
    public void everyIconDrawsSomethingInBothThemes() {
        for (final int mode : new int[]{
                Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
            final Context themed = themedContext(mode);

            for (final int icon : new int[]{
                    R.drawable.apple,
                    R.drawable.tag_self_generated,
                    R.drawable.findmy_accessory}) {
                final Drawable drawable = AppCompatResources.getDrawable(themed, icon);

                assertNotNull("icon " + icon + " did not load", drawable);
                assertTrue("icon " + icon + " has no intrinsic width",
                        drawable.getIntrinsicWidth() > 0);
                assertTrue("icon " + icon + " painted nothing in mode " + mode,
                        paintedPixels(drawable) > 0);
            }
        }
    }

    /**
     * And they are visibly different from one another once drawn.
     *
     * <p>Three ids pointing at three files proves nothing about what a person sees; two vectors
     * could easily be near-identical shapes. Comparing the painted coverage is a cheap way to
     * say they are actually distinguishable rather than merely distinct resources.
     */
    @Test
    public void thenewIconsLookDifferentFromApples() {
        final Context themed = themedContext(Configuration.UI_MODE_NIGHT_NO);

        final int apple = paintedPixels(
                AppCompatResources.getDrawable(themed, R.drawable.apple));
        final int haystack = paintedPixels(
                AppCompatResources.getDrawable(themed, R.drawable.tag_self_generated));
        final int findable = paintedPixels(
                AppCompatResources.getDrawable(themed, R.drawable.findmy_accessory));

        assertNotEquals("the haystack draws the same coverage as Apple's logo", apple, haystack);
        assertNotEquals("the findable icon draws the same coverage as Apple's logo",
                apple, findable);
        assertNotEquals("the two new icons draw the same coverage", haystack, findable);
    }

    /**
     * Draw each icon large, in both themes, so a person can see what they actually look like.
     *
     * <p>**Not an assertion**, and it is not pretending to be one - the tests above are what
     * fails the build. These are hand-authored vector paths, and "covers some pixels" is a long
     * way from "reads as a haystack", which is a judgement only an eye can make.
     */
    @Test
    public void renderTheIconsToLookAt() throws IOException {
        for (final int mode : new int[]{
                Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
            final Context themed = themedContext(mode);
            final String variant = mode == Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";

            write("apple-" + variant,
                    AppCompatResources.getDrawable(themed, R.drawable.apple));
            write("selfgenerated-" + variant,
                    AppCompatResources.getDrawable(themed, R.drawable.tag_self_generated));
            write("thirdparty-" + variant,
                    AppCompatResources.getDrawable(themed, R.drawable.findmy_accessory));
        }
    }

    /** At 8x, because a 24dp vector says nothing about its shape at 24 pixels. */
    private static void write(final String name, final Drawable drawable) throws IOException {
        assertNotNull(drawable);
        if (outputDir == null) {
            return;
        }

        final int size = Math.max(1, drawable.getIntrinsicWidth()) * 8;
        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);

        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);

        try (FileOutputStream out = new FileOutputStream(new File(outputDir, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }

    // ---------------------------------------------------------------- helpers

    private Context themedContext(final int nightMode) {
        final Configuration configuration =
                new Configuration(context().getResources().getConfiguration());
        configuration.uiMode =
                (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightMode;
        return context().createConfigurationContext(configuration);
    }

    /** How many pixels the drawable actually covers, rendered at its natural size. */
    private static int paintedPixels(final Drawable drawable) {
        assertNotNull(drawable);

        final int size = Math.max(1, drawable.getIntrinsicWidth());
        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);

        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);

        int painted = 0;
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    painted++;
                }
            }
        }

        bitmap.recycle();
        return painted;
    }
}
