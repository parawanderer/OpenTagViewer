package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.wander.android.opentagviewer.R;

/**
 * Layout tests for the tag cards along the bottom of the map.
 * <br>
 * These cover the regressions that can only be seen, and that have each come back more than
 * once: cards of different heights sitting next to each other, and a card whose shadow is
 * clipped by its container. Nothing throws when they break - the app runs, the data is right,
 * it just looks wrong - so the only previous detector was somebody noticing on a phone.
 * <br>
 * Deliberately narrow. This inflates the real card layout into a container built the same way
 * {@code activity_maps.xml} builds the scroll row, and measures it. No map, no network, no
 * database and no Apple account, so it is quick and cannot flake on anything external.
 * <br>
 * Inflation and measurement run on the main thread - the card contains a Material
 * CircularProgressIndicator, whose drawable starts an animator and throws "Animators may only
 * be run on Looper threads" anywhere else. Assertions stay on the test thread, so a failure is
 * reported as a failure rather than as a crash on the main looper.
 * <br>
 * Run with {@code ./gradlew :app:testEmulatorDebugAndroidTest}.
 */
@RunWith(AndroidJUnit4.class)
public class TagCardLayoutTest {

    /** Roughly a card's width on a phone, after MapsActivity's 80px inset. */
    private static final int CARD_WIDTH_PX = 1000;
    private static final int SCREEN_WIDTH_PX = 1080;

    private static final String SHORT_ADDRESS = "Amsterdam, Netherlands";
    private static final String LONG_ADDRESS =
            "Nieuwezijds Voorburgwal 147, 1012 RJ Amsterdam, Noord-Holland, Netherlands";

    private Context context;

    @Before
    public void setUp() {
        // The card uses Material components, which throw without a Material theme.
        this.context = new ContextThemeWrapper(
                getInstrumentation().getTargetContext(), R.style.Theme_OpenTagViewer);
    }

    /** One card's content, and the height MapsActivity would give it. */
    private static final class CardSpec {
        final String name;
        final String address;
        final int height;

        CardSpec(final String name, final String address) {
            this(name, address, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        CardSpec(final String name, final String address, final int height) {
            this.name = name;
            this.address = address;
            this.height = height;
        }
    }

    /**
     * Builds the row the way {@code MapsActivity.updateBeaconCards} does, measures it, and
     * returns each card's measured height.
     */
    private List<Integer> measureHeights(final CardSpec... specs) {
        final List<Integer> heights = new ArrayList<>();

        getInstrumentation().runOnMainSync(() -> {
            // Matches tags_scroll_container: a wrap_content horizontal LinearLayout.
            final LinearLayout row = new LinearLayout(this.context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (final CardSpec spec : specs) {
                final FrameLayout card = (FrameLayout) LayoutInflater.from(this.context)
                        .inflate(R.layout.maps_tag_card, null);
                row.addView(card);

                final ViewGroup.LayoutParams params = card.getLayoutParams();
                params.width = CARD_WIDTH_PX;
                params.height = spec.height;
                card.setLayoutParams(params);

                ((TextView) card.findViewById(R.id.device_name)).setText(spec.name);
                ((TextView) card.findViewById(R.id.device_location)).setText(spec.address);
                ((TextView) card.findViewById(R.id.device_last_update))
                        .setText("Last Updated: 2 minutes ago");
            }

            row.measure(
                    View.MeasureSpec.makeMeasureSpec(SCREEN_WIDTH_PX, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());

            for (int i = 0; i < row.getChildCount(); i++) {
                heights.add(row.getChildAt(i).getMeasuredHeight());
            }
        });

        return heights;
    }

    // --- uniform height -------------------------------------------------------------------

    @Test
    public void everyCardIsTheSameHeightWhateverItsContent() {
        // A one-line address next to a two-line one is what made the row ragged.
        final List<Integer> heights = this.measureHeights(
                new CardSpec("Keys", SHORT_ADDRESS),
                new CardSpec("Shane's Wallet", LONG_ADDRESS),
                new CardSpec("Backpack", "Groningen"));

        final int tallest = heights.stream().mapToInt(Integer::intValue).max().orElse(0);
        assertTrue("cards should have a real height", tallest > 0);
        for (int i = 0; i < heights.size(); i++) {
            assertEquals("card " + i + " differs from the tallest", tallest, (int) heights.get(i));
        }
    }

    @Test
    public void aLongDeviceNameDoesNotMakeItsCardTaller() {
        final List<Integer> heights = this.measureHeights(
                new CardSpec("Bag", "Amsterdam"),
                new CardSpec("An extremely long tag name that will certainly wrap onto more lines",
                        "Amsterdam"));

        assertEquals(heights.get(0), heights.get(1));
    }

    /**
     * Why the height has to be set at all.
     * <br>
     * MapsActivity inflates with a null root, which drops the layout's own height, and the
     * container hands out WRAP_CONTENT by default. MATCH_PARENT inside a wrap_content
     * horizontal LinearLayout is what triggers forceUniformHeight, which re-measures every
     * child to the tallest. This pins that reasoning so nobody "tidies" the assignment away.
     */
    @Test
    public void wrapContentIsWhatMadeTheRowRagged() {
        final List<Integer> heights = this.measureHeights(
                new CardSpec("Keys", SHORT_ADDRESS, ViewGroup.LayoutParams.WRAP_CONTENT),
                new CardSpec("Shane's Wallet", LONG_ADDRESS, ViewGroup.LayoutParams.WRAP_CONTENT));

        assertNotEquals(
                "with WRAP_CONTENT the cards are expected to differ - if this now passes, "
                        + "forceUniformHeight is no longer what keeps the row even, and "
                        + "everyCardIsTheSameHeightWhateverItsContent is passing for some other reason",
                heights.get(0), heights.get(1));
    }

    @Test
    public void aSingleCardStillHasAHeight() {
        assertTrue(this.measureHeights(new CardSpec("Keys", "Amsterdam")).get(0) > 0);
    }

    // --- the shadow -----------------------------------------------------------------------

    /**
     * The cards are elevated, so their shadow is drawn outside their own bounds. With the
     * scroll row clipping, the shadow is sliced off along the edges - which reads as a card
     * with a hard border rather than as a bug.
     */
    @Test
    public void theScrollRowDoesNotClipTheCardShadows() {
        final AtomicBoolean rowClipsChildren = new AtomicBoolean(true);
        final AtomicBoolean rowClipsToPadding = new AtomicBoolean(true);
        final AtomicBoolean areaClipsChildren = new AtomicBoolean(true);
        final AtomicBoolean areaClipsToPadding = new AtomicBoolean(true);

        getInstrumentation().runOnMainSync(() -> {
            final View activityMaps = LayoutInflater.from(this.context)
                    .inflate(R.layout.activity_maps, null);

            final ViewGroup row = activityMaps.findViewById(R.id.tags_scroll_container);
            rowClipsChildren.set(row.getClipChildren());
            rowClipsToPadding.set(row.getClipToPadding());

            final ViewGroup area = activityMaps.findViewById(R.id.tags_scrollable_area);
            areaClipsChildren.set(area.getClipChildren());
            areaClipsToPadding.set(area.getClipToPadding());
        });

        assertFalse("tags_scroll_container must not clip its children", rowClipsChildren.get());
        assertFalse("tags_scroll_container must not clip to padding", rowClipsToPadding.get());
        assertFalse("tags_scrollable_area must not clip its children", areaClipsChildren.get());
        assertFalse("tags_scrollable_area must not clip to padding", areaClipsToPadding.get());
    }
}
