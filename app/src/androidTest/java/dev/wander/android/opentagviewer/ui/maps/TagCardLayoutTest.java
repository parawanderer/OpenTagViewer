package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
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

    /**
     * A real place, and about as long as a geocoded address gets.
     *
     * <p>Llanfairpwllgwyngyll's full name is the usual example and it is not a joke input: the
     * address on a card comes from a geocoder, so its length is decided by where the tag is
     * rather than by anything this app or its user chose. Somebody's card looks like this.
     */
    private static final String A_RIDICULOUS_ADDRESS =
            "Llanfairpwllgwyngyllgogerychwyrndrobwllllantysiliogogogoch, "
                    + "Ynys Môn, Wales, LL61 5UJ, United Kingdom";

    /** And a name the user typed themselves, with no length limit on the field. */
    private static final String A_RIDICULOUS_NAME =
            "My absolutely enormous and unreasonably descriptive spare bicycle key tag";

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
     * {@code MapsActivity.showNearbyStatusOn}'s longest realistic line - full signal and the
     * longest battery word - must not wrap to a second line and grow the row. Built directly
     * rather than through {@link #measureHeights}, which always writes a fixed string to this
     * field.
     */
    @Test
    public void theLongestNearbyStatusLineDoesNotMakeItsCardTaller() {
        final int[] heights = new int[2];

        getInstrumentation().runOnMainSync(() -> {
            final FrameLayout baseline = (FrameLayout) LayoutInflater.from(this.context)
                    .inflate(R.layout.maps_tag_card, null);
            final FrameLayout withNearbyStatus = (FrameLayout) LayoutInflater.from(this.context)
                    .inflate(R.layout.maps_tag_card, null);

            for (final FrameLayout card : new FrameLayout[]{baseline, withNearbyStatus}) {
                ((TextView) card.findViewById(R.id.device_name)).setText("Keys");
                ((TextView) card.findViewById(R.id.device_location)).setText(SHORT_ADDRESS);
                // Inflated with a null root, so there is no parent-given LayoutParams to read
                // back - unlike measureHeights, which sets these after row.addView(card).
                card.setLayoutParams(new ViewGroup.LayoutParams(
                        CARD_WIDTH_PX, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            ((TextView) baseline.findViewById(R.id.device_last_update))
                    .setText("Last Updated: 2 minutes ago");
            // "critical" (English) / "kritisch" (German) is the longest battery word; five
            // filled dots is the longest signal reading; three digits covers up to the 30
            // second freshness window in NearbyTagSightings with room to spare.
            ((TextView) withNearbyStatus.findViewById(R.id.device_last_update))
                    .setText("Nearby (●●●●●) · Battery critical");

            for (final FrameLayout card : new FrameLayout[]{baseline, withNearbyStatus}) {
                card.measure(
                        View.MeasureSpec.makeMeasureSpec(CARD_WIDTH_PX, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            }
            heights[0] = baseline.getMeasuredHeight();
            heights[1] = withNearbyStatus.getMeasuredHeight();
        });

        assertEquals("the nearby status line wrapped and grew the card", heights[0], heights[1]);
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

    /**
     * <b>The card is elevated, so something has to make room for its shadow.</b>
     *
     * <p>Only half the story is checkable here, and it is worth being explicit about which half.
     * The elevation is in the layout; the room below it is <b>not</b> - {@code MapsActivity}
     * calls {@code WindowPaddingUtil.insertUIBottomPadding} at runtime and the space comes from
     * the navigation-bar inset. A freshly inflated layout therefore has zero bottom padding and
     * always will, so asserting on it here fails for a reason that has nothing to do with the
     * app. This test says the elevation exists and leaves the measurement to a test that has an
     * activity to measure.
     */
    @Test
    public void theCardIsElevatedSoItsShadowNeedsRoomBelow() {
        final int[] elevation = {0};

        getInstrumentation().runOnMainSync(() -> {
            final FrameLayout card = (FrameLayout) LayoutInflater.from(this.context)
                    .inflate(R.layout.maps_tag_card, null);
            elevation[0] = Math.round(card.findViewById(R.id.tag_item_container).getElevation());
        });

        assertTrue("the card is no longer elevated, so the row's bottom inset and its"
                + " clipToPadding settings are now protecting nothing", elevation[0] > 0);
    }

    // --- the width of a card, and the one behind it ----------------------------------------

    /** The width {@code MapsActivity.updateBeaconCards} gives every card, in pixels. */
    private static int cardWidthFor(final int screenWidth) {
        return screenWidth - 80;
    }

    /**
     * Lay out a row of cards the size the app makes them, and return each card's left edge.
     *
     * <p>Sized by the app's own rule rather than by a number chosen here, so this measures
     * {@code updateBeaconCards} and not the test's opinion of it.
     */
    private List<Integer> measureLeftEdges(final CardSpec... specs) {
        final List<Integer> edges = new ArrayList<>();

        getInstrumentation().runOnMainSync(() -> {
            final View activityMaps = LayoutInflater.from(this.context)
                    .inflate(R.layout.activity_maps, null);
            final ViewGroup row = activityMaps.findViewById(R.id.tags_scroll_container);

            for (final CardSpec spec : specs) {
                final FrameLayout card = (FrameLayout) LayoutInflater.from(this.context)
                        .inflate(R.layout.maps_tag_card, null);
                row.addView(card);

                final ViewGroup.LayoutParams params = card.getLayoutParams();
                params.width = cardWidthFor(SCREEN_WIDTH_PX);
                params.height = spec.height;
                card.setLayoutParams(params);

                ((TextView) card.findViewById(R.id.device_name)).setText(spec.name);
                ((TextView) card.findViewById(R.id.device_location)).setText(spec.address);
                ((TextView) card.findViewById(R.id.device_last_update))
                        .setText("Last Updated: 2 minutes ago");
            }

            activityMaps.measure(
                    View.MeasureSpec.makeMeasureSpec(SCREEN_WIDTH_PX, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
            activityMaps.layout(0, 0, SCREEN_WIDTH_PX, 2400);

            // **Asked of the view, not reconstructed from offsets.** Adding the row's own
            // padding to a child's getLeft() double-counts it - layout has already applied it -
            // which put the second card 42px further right than it is and turned a passing
            // layout into a failing assertion about the app.
            final int[] atScreen = new int[2];
            for (int i = 0; i < row.getChildCount(); i++) {
                row.getChildAt(i).getLocationInWindow(atScreen);
                edges.add(atScreen[0]);
            }
        });

        return edges;
    }

    /**
     * <b>The next card peeks, which is the only sign the row scrolls at all.</b>
     *
     * <p>There is no scrollbar and no arrow - {@code scrollbars="none"} - so a sliver of the
     * card behind is the entire affordance. A card sized to the full width hides it, and a user
     * with four tags sees one and no reason to think there are more.
     */
    @Test
    public void thenextCardPeeksSoTheRowLooksScrollable() {
        final List<Integer> edges = this.measureLeftEdges(
                new CardSpec("Keys", SHORT_ADDRESS),
                new CardSpec("Bike", SHORT_ADDRESS));

        final int secondCardStartsAt = edges.get(1);
        final int visible = SCREEN_WIDTH_PX - secondCardStartsAt;

        assertTrue("the second card starts at " + secondCardStartsAt + " on a "
                        + SCREEN_WIDTH_PX + "px screen, so nothing of it is visible and the row"
                        + " gives no sign that it scrolls",
                visible > 0);
    }

    /**
     * <b>And it takes most of the width, so it is a card and not a column.</b>
     *
     * <p>Bounds either side. Too narrow and the address wraps to four lines; too wide and the
     * peek above disappears. The numbers are loose on purpose - this is a guard against a
     * change of an order of magnitude, not a pin on the current value.
     */
    @Test
    public void thecardTakesMostOfTheScreenButNotAllOfIt() {
        final int width = cardWidthFor(SCREEN_WIDTH_PX);

        assertTrue("a card " + width + "px wide on a " + SCREEN_WIDTH_PX + "px screen is too"
                + " narrow to read an address on", width > SCREEN_WIDTH_PX * 0.6);
        assertTrue("a card " + width + "px wide leaves nothing of the next one showing",
                width < SCREEN_WIDTH_PX);
    }

    /**
     * <b>A very long street name does not change the card's shape.</b>
     *
     * <p>The case that keeps breaking: the address is the one field with no bound on its length,
     * and it is filled in by a geocoder rather than by anything this app controls. If it can
     * push the card wider, the peek goes and the row stops looking scrollable - and it happens
     * only to whoever lives on a long street.
     */
    @Test
    public void averyLongStreetNameDoesNotChangeTheCardsShape() {
        final List<Integer> edges = this.measureLeftEdges(
                new CardSpec("Keys", A_RIDICULOUS_ADDRESS),
                new CardSpec("Bike", SHORT_ADDRESS));

        final List<Integer> heights = this.measureHeights(
                new CardSpec("Keys", A_RIDICULOUS_ADDRESS),
                new CardSpec("Bike", SHORT_ADDRESS));

        assertEquals("a long address made its card a different height", heights.get(0), heights.get(1));
        assertTrue("a long address pushed the next card off the screen",
                SCREEN_WIDTH_PX - edges.get(1) > 0);
    }

    /** The same for a name nobody sensible would use, which is to say the one somebody used. */
    @Test
    public void averyLongTagNameDoesNotChangeTheCardsShape() {
        final List<Integer> edges = this.measureLeftEdges(
                new CardSpec(A_RIDICULOUS_NAME, SHORT_ADDRESS),
                new CardSpec("Bike", SHORT_ADDRESS));

        assertTrue("a long tag name pushed the next card off the screen",
                SCREEN_WIDTH_PX - edges.get(1) > 0);
    }

    // --- the two kinds of tag icon ---------------------------------------------------------

    /**
     * <b>An emoji tag and an icon tag are the same shape.</b>
     *
     * <p>Two different views in the same slot - a {@code TextView} at 36sp and an
     * {@code ImageView} - swapped by visibility, and only one of them is ever on screen at a
     * time. So a difference between them is invisible until somebody sets an emoji on one tag
     * and not another, at which point the row goes ragged for that person only.
     *
     * <p><b>The emoji one is also the one that follows the font scale.</b> 36sp grows with the
     * system text size and 53dp does not, so this is measured at the largest accessibility
     * setting rather than the default - which is where they come apart, if they do.
     */
    @Test
    public void anemojiTagAndAnIconTagAreTheSameShape() {
        final List<Integer> heights = this.measureIconVariantHeights(1.0f);

        assertEquals("an emoji tag and an icon tag should be the same height",
                heights.get(0), heights.get(1));
    }

    /** And they stay the same shape when the system text is turned all the way up. */
    @Test
    public void theystayTheSameShapeAtTheLargestTextSize() {
        final List<Integer> heights = this.measureIconVariantHeights(2.0f);

        assertEquals("at 2x text scale the emoji tag and the icon tag came apart",
                heights.get(0), heights.get(1));
    }

    /**
     * Card heights for [emoji tag, icon tag], at a given system font scale.
     *
     * <p>Each card is measured on its own rather than side by side: in a row they are forced to
     * a uniform height, which is exactly the mechanism that would hide a difference between
     * them.
     */
    private List<Integer> measureIconVariantHeights(final float fontScale) {
        final List<Integer> heights = new ArrayList<>();

        getInstrumentation().runOnMainSync(() -> {
            final Configuration scaled = new Configuration(
                    this.context.getResources().getConfiguration());
            scaled.fontScale = fontScale;

            final Context scaledContext = new ContextThemeWrapper(
                    this.context.createConfigurationContext(scaled), R.style.Theme_OpenTagViewer);

            for (final boolean emoji : new boolean[] {true, false}) {
                final FrameLayout card = (FrameLayout) LayoutInflater.from(scaledContext)
                        .inflate(R.layout.maps_tag_card, null);

                card.findViewById(R.id.device_icon_emoji)
                        .setVisibility(emoji ? View.VISIBLE : View.GONE);
                card.findViewById(R.id.device_icon_img)
                        .setVisibility(emoji ? View.GONE : View.VISIBLE);

                if (emoji) {
                    ((TextView) card.findViewById(R.id.device_icon_emoji)).setText("🚲");
                }

                ((TextView) card.findViewById(R.id.device_name)).setText("Bike");
                ((TextView) card.findViewById(R.id.device_location)).setText(SHORT_ADDRESS);
                ((TextView) card.findViewById(R.id.device_last_update))
                        .setText("Last Updated: 2 minutes ago");

                card.measure(
                        View.MeasureSpec.makeMeasureSpec(
                                cardWidthFor(SCREEN_WIDTH_PX), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

                heights.add(card.getMeasuredHeight());
            }
        });

        return heights;
    }

    // --- the buttons along the bottom -------------------------------------------------------

    /**
     * <b>Every action that ships still has room, whatever the card is holding.</b>
     *
     * <p>History, Refresh and More share the bottom row, each carrying a label under an icon.
     * They are what a longer address squeezes, because it sits directly above them and the
     * card's height is fixed by its shortest neighbour - so the failure is a row of icons with
     * the words clipped away, on one card out of four.
     *
     * <p>Ring is included alongside the other three - see
     * {@code dev.wander.android.opentagviewer.ble} for what is behind it now.
     */
    /**
     * <b>The four actions are evenly spaced across the row.</b>
     *
     * <p>They are laid out with {@code layout_weight="1"} apiece, which makes them equal in
     * <i>width</i> and says nothing about where they sit: the margins between them are what puts
     * them on an even pitch, and one container missing a pair of them shifts every gap around it
     * without changing any width. Ring shipped without its 8dp margins, which pushed the first
     * gap 8dp wider than the other two - visible on a phone as Location History sitting too far
     * from Refresh, and invisible to
     * {@link #everyActionOnTheCardStillHasRoomWithTheWorstContent}, which asks about sizes.
     *
     * <p>Measured between the centres of the icons rather than the containers, because the icon
     * is the thing a person's eye lines up.
     *
     * <p><b>The tolerance is in dp, and it is the difference between a test and a nuisance.</b>
     * Four weighted columns rarely divide a card width exactly, so neighbouring gaps land a
     * pixel or two apart - measured at 243 and 245 here - and a 1px tolerance fails on that
     * while proving nothing. A missing margin is 8dp, which is four times this threshold at any
     * density, so the gap between "rounding" and "the bug" is wide and this sits in it.
     */
    @Test
    public void theFourActionsAreEvenlySpacedAcrossTheRow() {
        final int[] centres = new int[4];
        final int[] iconIds = {
                R.id.history_icon,
                R.id.refresh_icon,
                R.id.perform_ring_icon,
                R.id.tag_more_icon,
        };

        getInstrumentation().runOnMainSync(() -> {
            final FrameLayout card = (FrameLayout) LayoutInflater.from(this.context)
                    .inflate(R.layout.maps_tag_card, null);

            final int width = cardWidthFor(SCREEN_WIDTH_PX);
            card.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            card.layout(0, 0, width, card.getMeasuredHeight());

            for (int i = 0; i < iconIds.length; i++) {
                final View icon = card.findViewById(iconIds[i]);

                // **Offsets within the card, not window coordinates.** This card is inflated
                // with no parent and never attached, so getLocationInWindow has no window to
                // answer about and reports positions that are all but identical - which came
                // out as negative gaps rather than as an obvious "this measured nothing".
                final Rect bounds = new Rect(0, 0, icon.getWidth(), icon.getHeight());
                card.offsetDescendantRectToMyCoords(icon, bounds);
                centres[i] = bounds.centerX();
            }
        });

        final float density = this.context.getResources().getDisplayMetrics().density;
        final int roundingSlackPx = Math.round(2 * density);

        final int firstGap = centres[1] - centres[0];
        for (int i = 1; i < centres.length - 1; i++) {
            final int gap = centres[i + 1] - centres[i];
            assertTrue("the gap between action " + i + " and " + (i + 1) + " is " + gap
                            + "px, but the first gap is " + firstGap + "px - the row is not on an"
                            + " even pitch, which usually means one container is missing the 8dp"
                            + " margins the others have",
                    Math.abs(gap - firstGap) <= roundingSlackPx);
        }
    }

    @Test
    public void everyActionOnTheCardStillHasRoomWithTheWorstContent() {
        final int[][] sizes = new int[4][2];
        final int[] buttonIds = {
                R.id.device_history_button_container,
                R.id.device_refresh_button_container,
                R.id.device_ring_button_container,
                R.id.device_more_button_container,
        };

        getInstrumentation().runOnMainSync(() -> {
            final FrameLayout card = (FrameLayout) LayoutInflater.from(this.context)
                    .inflate(R.layout.maps_tag_card, null);

            ((TextView) card.findViewById(R.id.device_name)).setText(A_RIDICULOUS_NAME);
            ((TextView) card.findViewById(R.id.device_location)).setText(A_RIDICULOUS_ADDRESS);
            ((TextView) card.findViewById(R.id.device_last_update))
                    .setText("Last Updated: 2 minutes ago");

            final int width = cardWidthFor(SCREEN_WIDTH_PX);
            card.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            card.layout(0, 0, width, card.getMeasuredHeight());

            for (int i = 0; i < buttonIds.length; i++) {
                final View button = card.findViewById(buttonIds[i]);
                sizes[i][0] = button.getMeasuredWidth();
                sizes[i][1] = button.getMeasuredHeight();
            }
        });

        for (int i = 0; i < buttonIds.length; i++) {
            assertTrue("button " + i + " measured " + sizes[i][0] + "x" + sizes[i][1]
                    + ", so it is not on the card", sizes[i][0] > 0 && sizes[i][1] > 0);
        }

        // They share the row, so a card that has run out of width shows up as one of them being
        // visibly smaller than the rest rather than as anything failing.
        int widest = 0;
        for (final int[] size : sizes) {
            widest = Math.max(widest, size[0]);
        }
        for (int i = 0; i < buttonIds.length; i++) {
            assertTrue("button " + i + " is " + sizes[i][0] + "px against a widest of " + widest
                            + ", so the row is no longer sharing the width evenly",
                    sizes[i][0] > widest / 2);
        }
    }

    /**
     * <b>Ring is shown by default, at rest.</b>
     *
     * <p>It used to be {@code visibility="gone"} because nothing implemented it. Now
     * {@code MapsActivity#onClickRing} does (continuous ping over BLE, see
     * {@code dev.wander.android.opentagviewer.ble}), so this is the opposite pin from before: a
     * stray edit hiding it again ships a card silently missing an action, rather than a card
     * offering one that does nothing.
     */
    @Test
    public void theRingButtonIsShownAtRestByDefault() {
        final int[] visibility = {View.GONE};

        getInstrumentation().runOnMainSync(() -> {
            final FrameLayout card = (FrameLayout) LayoutInflater.from(this.context)
                    .inflate(R.layout.maps_tag_card, null);
            visibility[0] = card.findViewById(R.id.device_ring_button_container).getVisibility();
        });

        assertEquals("Ring is hidden, but MapsActivity#onClickRing now implements it",
                View.VISIBLE, visibility[0]);
    }

    /** The label at rest, before anyone has tapped it - see {@link TagCardHelper#toggleRingActive}. */
    @Test
    public void theRingButtonStartsLabelledRing() {
        final String[] text = {null};

        getInstrumentation().runOnMainSync(() -> {
            final FrameLayout card = (FrameLayout) LayoutInflater.from(this.context)
                    .inflate(R.layout.maps_tag_card, null);
            text[0] = ((TextView) card.findViewById(R.id.ringText)).getText().toString();
        });

        assertEquals(this.context.getString(R.string.do_ring), text[0]);
    }

    /**
     * <b>{@link TagCardHelper#toggleRingActive} is what MapsActivity calls on tap, and on stop.</b>
     *
     * <p>Round-tripped in one test rather than two, because the failure that matters is the
     * button getting stuck in one state - which only shows up by going there and back.
     */
    @Test
    public void toggleRingActiveSwapsTheLabelBothWays() {
        final String[] activeText = {null};
        final String[] inactiveAgainText = {null};

        getInstrumentation().runOnMainSync(() -> {
            final FrameLayout card = (FrameLayout) LayoutInflater.from(this.context)
                    .inflate(R.layout.maps_tag_card, null);

            TagCardHelper.toggleRingActive(card, true);
            activeText[0] = ((TextView) card.findViewById(R.id.ringText)).getText().toString();

            TagCardHelper.toggleRingActive(card, false);
            inactiveAgainText[0] = ((TextView) card.findViewById(R.id.ringText)).getText().toString();
        });

        assertEquals(this.context.getString(R.string.stop_ringing), activeText[0]);
        assertEquals(this.context.getString(R.string.do_ring), inactiveAgainText[0]);
    }

    /**
     * <b>{@link TagCardHelper#setRingLabel} is what continuous ping updates between taps</b> -
     * "Scanning...", "Connecting...", "Sending..." - without touching the icon or tint
     * {@link TagCardHelper#toggleRingActive} owns. See {@code MapsActivity#handleContinuousPingUpdate}.
     */
    @Test
    public void setRingLabelChangesOnlyTheText() {
        final String[] text = {null};

        getInstrumentation().runOnMainSync(() -> {
            final FrameLayout card = (FrameLayout) LayoutInflater.from(this.context)
                    .inflate(R.layout.maps_tag_card, null);

            TagCardHelper.setRingLabel(card, "Scanning…");
            text[0] = ((TextView) card.findViewById(R.id.ringText)).getText().toString();
        });

        assertEquals("Scanning…", text[0]);
    }
}
