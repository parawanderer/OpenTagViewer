package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.R;

/**
 * The device list row shows both of its lines.
 *
 * <p><b>The row has two lines and one of them is the one people need.</b> The name is repeated
 * on the map and in the tag carousel; "last updated" is not, and a row that silently drops it
 * still looks like a finished row - which is why this went unnoticed. Rule 12's "measured size
 * is not zero, and does not clip".
 *
 * <p><b>Measured the way the list measures it.</b> {@code my_device_list_item} is a
 * {@code RecyclerView} row (see {@code DeviceListAdaptor}), so its width is the list's and its
 * height is its own: a {@code wrap_content} child of a vertically scrolling list is measured
 * {@code UNSPECIFIED}. Measuring it any other way tests a layout the app never renders.
 *
 * <p>No activity and no data - inflate, set the two texts, measure, and ask where the subtitle
 * ended up.
 */
@RunWith(AndroidJUnit4.class)
public class TheDeviceListRowFitsItsSubtitleTest {

    /** A phone-width list, in pixels. Wide enough that neither line wraps for lack of room. */
    private static final int WIDTH_PX = 1080;

    /**
     * Long enough to need a second line at ordinary widths.
     *
     * <p>A one-word name never exercises this: the row is tall enough for a single line whatever
     * the heights say, so the bug only appears once the text column wants more room than the
     * 40dp icon beside it.
     */
    private static final String A_LONG_NAME =
            "Shane's black leather wallet with the transit card in it";

    private static final String A_SUBTITLE = "Last updated: 14:32, 12 September 2026";

    private View row;

    private View inflateAndMeasure() throws Throwable {
        getInstrumentation().runOnMainSync(() -> {
            final Context themed = new ContextThemeWrapper(
                    getInstrumentation().getTargetContext(), R.style.Theme_OpenTagViewer);

            final View inflated = LayoutInflater.from(themed)
                    .inflate(R.layout.my_device_list_item, null, false);

            ((TextView) inflated.findViewById(R.id.list_item_device_name)).setText(A_LONG_NAME);
            ((TextView) inflated.findViewById(R.id.list_item_last_update)).setText(A_SUBTITLE);

            inflated.measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            inflated.layout(0, 0, inflated.getMeasuredWidth(), inflated.getMeasuredHeight());

            this.row = inflated;
        });

        return this.row;
    }

    /**
     * The subtitle is on screen at all.
     *
     * <p><b>This is the assertion that catches it.</b> Run against the layout before the fix, it
     * reports {@code the subtitle measured 0px} - the line is not trimmed at the edges, it is
     * given no height whatsoever, so the row renders as a single-line row that looks finished.
     * A {@code withText} check passes on it, which is why Espresso coverage would not have
     * noticed.
     *
     * <p>The two below passed against the broken layout and are kept as the weaker statement of
     * the same property: a subtitle of zero height trivially ends inside its row, and a row is
     * trivially tall enough for a name plus nothing. They guard the case where the line comes
     * back at the wrong size rather than at no size.
     */
    @Test
    public void thesubtitleIsNotSqueezedToNothing() throws Throwable {
        final View inflated = inflateAndMeasure();
        final TextView subtitle = inflated.findViewById(R.id.list_item_last_update);

        assertTrue("the subtitle measured " + subtitle.getMeasuredHeight() + "px, so the row"
                + " renders as though it had one line", subtitle.getMeasuredHeight() > 0);
        assertTrue("the subtitle has no lines laid out", subtitle.getLineCount() > 0);
    }

    /**
     * And it is inside the row rather than hanging off the bottom of it.
     *
     * <p>This is the half a screenshot would not settle. A child laid out past its parent's
     * measured height is drawn only as far as the parent goes, so the row looks plausible and
     * the last few pixels of the line are simply missing.
     */
    @Test
    public void thesubtitleEndsInsideTheRow() throws Throwable {
        final View inflated = inflateAndMeasure();
        final TextView subtitle = inflated.findViewById(R.id.list_item_last_update);

        int bottomWithinRow = subtitle.getBottom();
        for (View parent = (View) subtitle.getParent();
                parent != null && parent != inflated;
                parent = (View) parent.getParent()) {
            bottomWithinRow += parent.getTop();
        }

        assertTrue("the subtitle ends at " + bottomWithinRow + "px in a row measured "
                        + inflated.getMeasuredHeight() + "px tall, so its last line is cut off",
                bottomWithinRow <= inflated.getMeasuredHeight());
    }

    /**
     * The row is tall enough to hold what it contains.
     *
     * <p>Stated separately from the two above because it fails differently: a row shorter than
     * its own text column is the cause, and the two assertions above are what the user sees.
     */
    @Test
    public void therowIsAtLeastAsTallAsBothLinesTogether() throws Throwable {
        final View inflated = inflateAndMeasure();
        final TextView name = inflated.findViewById(R.id.list_item_device_name);
        final TextView subtitle = inflated.findViewById(R.id.list_item_last_update);

        final int bothLines = name.getMeasuredHeight() + subtitle.getMeasuredHeight();

        assertTrue("the row measured " + inflated.getMeasuredHeight() + "px for "
                        + bothLines + "px of text", inflated.getMeasuredHeight() >= bothLines);
    }
}
