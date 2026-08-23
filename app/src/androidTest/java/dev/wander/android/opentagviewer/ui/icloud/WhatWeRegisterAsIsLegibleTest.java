package dev.wander.android.opentagviewer.ui.icloud;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.R;

/**
 * The page telling somebody what this app puts on their Apple account.
 *
 * <p><b>It exists because of what Apple shows next to it.</b> Connecting registers this app as a
 * device, and the account's device list renders that entry beside the words "If you do not
 * recognise this device", with a Remove button. An unexplained row is a row people remove - and
 * removing it breaks a connection that was working, hours later, with nothing linking the two.
 * So the page has to be read, which means it has to be legible.
 *
 * <p><b>Contrast is a number, not an opinion.</b> The tile sits on a tinted surface rather than
 * the page background, so its text is not covered by whatever contrast the rest of the screen
 * has: a body colour that is fine on {@code colorSurface} can fail on
 * {@code colorSurfaceContainerLow}, and can fail in only one of the two themes. Both are checked.
 *
 * <p>What this <b>cannot</b> see is the tile's contents: the model, OS and serial are set by
 * {@code FetchFromICloudActivity} from the identity the app actually registers under, so that
 * they cannot describe a different machine than the one on the account. Asserted there, driving
 * the real screen.
 *
 * <p>Screenshots are written alongside and are <b>not</b> the assertion - they explain what a
 * failure looks like. See {@code .claude/skills/device-screenshots/}.
 */
@RunWith(AndroidJUnit4.class)
public class WhatWeRegisterAsIsLegibleTest {

    private static final String TAG = "RegisteredNoteTest";

    /** WCAG AA for body text. This page is long enough that people have to actually read it. */
    private static final double READABLE = 4.5d;

    private static final int WIDTH_PX = 1000;

    private static File outputDir;

    @BeforeClass
    public static void resolveOutputDir() {
        final String fromAgp = InstrumentationRegistry.getArguments()
                .getString("additionalTestOutputDir");

        outputDir = fromAgp != null
                ? new File(fromAgp)
                : getInstrumentation().getTargetContext().getExternalFilesDir("registered-note");

        if (outputDir != null && !outputDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outputDir.mkdirs();
        }
        Log.i(TAG, "Writing screenshots to " + outputDir);
    }

    /**
     * <b>It inflates, and every part the activity fills in is really there.</b>
     *
     * <p>A renamed id still compiles. The screen would come up with the tile present and blank -
     * on a step most people see once per connect and read once, ever.
     */
    @Test
    public void thepageInflatesWithEveryPartTheActivityFillsIn() {
        final View note = inflateNote(dayTheme());

        for (final int id : new int[]{
                R.id.icloud_registered_lead,
                R.id.icloud_registered_icon,
                R.id.icloud_registered_device_name,
                R.id.icloud_registered_device_model,
                R.id.icloud_registered_device_serial,
                R.id.icloud_registered_body,
                R.id.icloud_registered_link,
        }) {
            assertNotNull("a view the activity looks up is not in the layout: "
                    + note.getResources().getResourceEntryName(id), note.findViewById(id));
        }
    }

    /**
     * <b>It names the serial, and does not claim the entry is called "OpenTagViewer".</b>
     *
     * <p>It is not. Apple synthesises the row's title from the claimed model and ignores the name
     * the app sends, so the entry reads {@code MacBookPro} - confirmed on a real account. The copy
     * said "it appears in your device list as OpenTagViewer" until somebody looked, which would
     * have sent people hunting for a row that does not exist and mistrusting the one that does.
     *
     * <p>Which leaves the serial carrying the whole identification, so it has to be printed.
     */
    @Test
    public void itnamesTheSerialAndNotAnAppNameAppleDiscards() {
        final View note = inflateNote(dayTheme());
        final String body = textOf(note, R.id.icloud_registered_body);

        // **The slot, not the serial.** The serial is dropped in at runtime as a code chip, so
        // what the resource holds is the template. A translation that loses "^1" does not throw
        // and does not fail anything - expandTemplate simply finds nothing to replace, and that
        // locale's copy talks about "the serial" without ever giving it.
        assertTrue("the body has no ^1 slot, so nothing puts the serial into the sentence that is"
                + " about the serial", body.contains("^1"));

        assertFalse("Apple ignores the name this app sends and titles the row from the model, so"
                        + " promising it appears as \"OpenTagViewer\" sends people looking for a"
                        + " row that is not there",
                body.contains("OpenTagViewer"));
    }

    /**
     * <b>The "do not remove it" sentence is actually bold, and bold only where it should be.</b>
     *
     * <p>The emphasis is the point of the sentence, and it lives in the resource as {@code <b>} -
     * markup that survives a great many ways of not surviving. An escaped angle bracket, a
     * translation whose tags were dropped, a {@code setText} that passed {@code toString()}: each
     * renders the words unemphasised, all ten locales at once, with nothing failing anywhere.
     *
     * <p>The second half is what keeps this honest. Asserting only that <i>something</i> is bold
     * would pass just as well if the whole paragraph were - which is the same as none of it being
     * bold, since emphasis is a contrast and not a property.
     */
    @Test
    public void thewarningIsEmphasisedAndNotJustWorded() {
        final View note = inflateNote(dayTheme());
        final CharSequence body =
                ((TextView) note.findViewById(R.id.icloud_registered_body)).getText();

        assertTrue("the body carries no markup at all, so <b> was lost on the way to the screen",
                body instanceof Spanned);

        final Spanned spanned = (Spanned) body;

        int boldTo = -1;
        for (final StyleSpan span : spanned.getSpans(0, spanned.length(), StyleSpan.class)) {
            if ((span.getStyle() & Typeface.BOLD) != 0) {
                boldTo = Math.max(boldTo, spanned.getSpanEnd(span));
            }
        }

        assertTrue("nothing in the body is bold", boldTo > 0);
        assertTrue("the whole body is bold, which emphasises nothing - the warning is the first"
                + " sentence, not the page", boldTo < spanned.length());
    }

    /**
     * <b>And the warning stands alone, with a blank line under it.</b>
     *
     * <p>It is the one sentence on this page that has to be read by somebody skimming, so it gets
     * its own paragraph rather than a bold run at the head of a block of text. A single newline
     * here reads as a wrapped line and puts it back in the paragraph.
     */
    @Test
    public void thewarningHasAParagraphToItself() {
        final View note = inflateNote(dayTheme());
        final String body = textOf(note, R.id.icloud_registered_body);

        final int firstBreak = body.indexOf('\n');
        assertTrue("there is no line break after the warning at all", firstBreak > 0);
        assertTrue("the warning is followed by one newline rather than a blank line, so it reads"
                        + " as part of the paragraph under it",
                body.startsWith("\n\n", firstBreak));
    }

    /**
     * <b>And it arrives as paragraphs, not as one wall of text.</b>
     *
     * <p>This is a resource-file trap rather than a styling preference. A real newline typed into
     * {@code strings.xml} is whitespace, and Android collapses it - only the literal escape
     * {@code \n} survives to the screen. Both look identical in the XML, in the diff and in a
     * translation JSON, so this shipped once as a single nine-line block and nothing said so;
     * it was caught by looking at the render. Ten locales get this wrong or right together, so
     * asserting on the default one is enough.
     */
    @Test
    public void itreadsAsParagraphsRatherThanOneBlock() {
        final View note = inflateNote(dayTheme());

        assertTrue("the body has no line break in it, so the paragraph splits were written as"
                        + " real newlines and Android collapsed them into spaces - they have to be"
                        + " the literal escape to survive",
                textOf(note, R.id.icloud_registered_body).contains("\n"));
    }

    /** Readable on the tinted tile and the page around it, in daylight. */
    @Test
    public void thepageIsReadableInTheDayTheme() {
        assertReadable(dayTheme(), "day");
    }

    /**
     * And at night, which is the half that goes unlooked at.
     *
     * <p>The tile's tint and the text colour both come from the theme, so they move together -
     * and a pair clearing 4.5:1 in one mode can miss in the other.
     */
    @Test
    public void thepageIsReadableInTheNightTheme() {
        assertReadable(nightTheme(), "night");
    }

    // --- the work -------------------------------------------------------------------------------

    private void assertReadable(final Context context, final String label) {
        final View note = inflateNote(context);

        final int tile = resolve(
                context, com.google.android.material.R.attr.colorSurfaceContainerLow);
        final int page = resolve(context, com.google.android.material.R.attr.colorSurface);

        writeShot(note, "registered_note-" + label);

        // On the tile: the serial is the field somebody compares character by character.
        assertContrast(label, note, R.id.icloud_registered_device_name, tile);
        assertContrast(label, note, R.id.icloud_registered_device_model, tile);
        assertContrast(label, note, R.id.icloud_registered_device_serial, tile);

        // On the page behind it.
        assertContrast(label, note, R.id.icloud_registered_lead, page);
        assertContrast(label, note, R.id.icloud_registered_body, page);
        assertContrast(label, note, R.id.icloud_registered_link, page);
    }

    private void assertContrast(
            final String label, final View note, final int id, final int background) {
        final TextView text = note.findViewById(id);
        final double ratio = contrastRatio(text.getCurrentTextColor(), background);

        assertTrue(label + ": " + note.getResources().getResourceEntryName(id) + " is "
                        + round(ratio) + ":1, under the " + READABLE + ":1 needed to read it",
                ratio >= READABLE);
    }

    private static String textOf(final View note, final int id) {
        return ((TextView) note.findViewById(id)).getText().toString();
    }

    /**
     * The page, inflated on its own.
     *
     * <p>The shipping layout, not a copy - {@code activity_fetch_from_icloud} includes this same
     * file, so there is nothing here that can drift from what the user sees. Inflating the whole
     * screen instead is not an option: it holds a {@code CircularProgressIndicator}, which will
     * not inflate outside an activity, and this would have been untestable for a reason having
     * nothing to do with it.
     */
    private View inflateNote(final Context context) {
        final View note = LayoutInflater.from(context)
                .inflate(R.layout.icloud_registered_note, null, false);

        assertNotNull("the layout did not inflate", note);

        note.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        note.layout(0, 0, note.getMeasuredWidth(), note.getMeasuredHeight());

        assertTrue("it measured to nothing, so nobody would see it",
                note.getMeasuredHeight() > 0);

        return note;
    }

    private void writeShot(final View view, final String name) {
        if (outputDir == null) {
            return;
        }
        try {
            final Bitmap bitmap = Bitmap.createBitmap(
                    view.getMeasuredWidth(), view.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));

            try (FileOutputStream out = new FileOutputStream(new File(outputDir, name + ".png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (final Exception e) {
            // A screenshot explains a failure; it is never the reason for one.
            Log.w(TAG, "could not write " + name, e);
        }
    }

    private static double round(final double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static Context dayTheme() {
        return new ContextThemeWrapper(
                getInstrumentation().getTargetContext(), R.style.Theme_OpenTagViewer);
    }

    private static Context nightTheme() {
        final Context base = getInstrumentation().getTargetContext();

        final Configuration night = new Configuration(base.getResources().getConfiguration());
        night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | Configuration.UI_MODE_NIGHT_YES;

        return new ContextThemeWrapper(
                base.createConfigurationContext(night), R.style.Theme_OpenTagViewer);
    }

    private static int resolve(final Context context, final int attr) {
        final TypedValue value = new TypedValue();
        assertTrue("the theme cannot resolve a colour the layout uses",
                context.getTheme().resolveAttribute(attr, value, true));
        return value.data;
    }

    private static double relativeLuminance(final int colour) {
        final double[] channel = {
                ((colour >> 16) & 0xff) / 255d,
                ((colour >> 8) & 0xff) / 255d,
                (colour & 0xff) / 255d,
        };
        for (int i = 0; i < channel.length; i++) {
            channel[i] = channel[i] <= 0.04045d
                    ? channel[i] / 12.92d
                    : Math.pow((channel[i] + 0.055d) / 1.055d, 2.4d);
        }
        return 0.2126d * channel[0] + 0.7152d * channel[1] + 0.0722d * channel[2];
    }

    private static double contrastRatio(final int a, final int b) {
        final double la = relativeLuminance(a);
        final double lb = relativeLuminance(b);
        return (Math.max(la, lb) + 0.05d) / (Math.min(la, lb) + 0.05d);
    }
}
