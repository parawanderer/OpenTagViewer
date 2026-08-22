package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.swipeLeft;
import static androidx.test.espresso.action.ViewActions.swipeRight;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.R;

/**
 * However the tag row is dragged, it ends up centred on exactly one card.
 *
 * <p><b>The property, not the mechanism.</b> {@code TagListSwiperHelper} decides where to settle
 * from a scroll offset, a velocity threshold and the three cards nearest the middle - and every
 * one of those has been wrong at some point. What a user notices is none of that: it is a row
 * resting halfway between two cards, with neither readable and no indication which one the
 * buttons underneath belong to. That is the thing worth pinning, and it survives the mechanism
 * being rewritten.
 *
 * <p><b>It needs a real window.</b> The helper positions cards with {@code getLocationOnScreen}
 * and settles on {@code ACTION_UP}, so nothing about it can be observed from a detached
 * inflation - which is why this is an activity test where the layout tests next door are not.
 *
 * <p><b>Off-centre is measured in pixels, not asserted as an id.</b> "Which card is primary"
 * has an answer even when the row is resting between two, because the helper picks the nearest;
 * asking only that question would pass with the row visibly half-way. The distance from the
 * row's middle to the nearest card's middle is the number that tells the truth.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class TheTagCarouselAlwaysSettlesOnOneCardTest {

    /**
     * How far off centre still counts as settled.
     *
     * <p>Generous on purpose. The row is around a thousand pixels wide and a card fills nearly
     * all of it, so resting between two cards puts the nearest one hundreds of pixels out. This
     * is not a pin on the exact resting position - it is the difference between "on a card" and
     * "between cards", and a threshold tight enough to argue about would flake on a scroller
     * that settles by animation.
     */
    private static final int SETTLED_WITHIN_PX = 60;

    private final AMapWithTagsOnIt theMap = new AMapWithTagsOnIt();

    @Before
    public void openTheMapWithThreeTagsOnIt() {
        this.theMap.seed("Bike", "Keys", "Backpack").open();

        Eventually.check(() -> assertTrue("the map never became ready",
                this.theMap.map().isReady()));
        Eventually.check(() -> assertTrue("the cards were never built",
                this.theMap.cards().size() >= 3));
    }

    @After
    public void putEverythingBack() {
        this.theMap.putItBack();
    }

    /** <b>It starts centred, before anybody touches it.</b> */
    @Test
    public void itstartsRestingOnACard() {
        Eventually.check(() -> assertTrue(
                "the row opened resting " + this.theMap.howFarOffCentreTheNearestCardIs()
                        + "px off centre, so no card is squarely in view",
                this.theMap.howFarOffCentreTheNearestCardIs() <= SETTLED_WITHIN_PX));
    }

    /** <b>A swipe moves it on, and it settles on the next card rather than between two.</b> */
    @Test
    public void aswipeSettlesOnTheNextCard() {
        final int firstCardLeft = this.leftEdgeOfTheFirstCard();

        onView(withId(R.id.tags_scrollable_area)).perform(swipeLeft());

        Eventually.check(() -> assertTrue(
                "after a swipe the row rested " + this.theMap.howFarOffCentreTheNearestCardIs()
                        + "px off centre, so it stopped between two cards",
                this.theMap.howFarOffCentreTheNearestCardIs() <= SETTLED_WITHIN_PX));

        assertNotEquals("the swipe did not move the row at all",
                firstCardLeft, this.leftEdgeOfTheFirstCard());
    }

    /** And swiping back settles too, rather than only working in the direction of travel. */
    @Test
    public void swipingBackSettlesAsWell() {
        onView(withId(R.id.tags_scrollable_area)).perform(swipeLeft());
        Eventually.check(() -> assertTrue(
                this.theMap.howFarOffCentreTheNearestCardIs() <= SETTLED_WITHIN_PX));

        onView(withId(R.id.tags_scrollable_area)).perform(swipeRight());

        Eventually.check(() -> assertTrue(
                "swiping back rested " + this.theMap.howFarOffCentreTheNearestCardIs()
                        + "px off centre",
                this.theMap.howFarOffCentreTheNearestCardIs() <= SETTLED_WITHIN_PX));
    }

    /**
     * <b>And it still settles after being thrown about.</b>
     *
     * <p>Several gestures in a row, including one that runs into the end of the row. The end is
     * where this has gone wrong before: there is no card beyond the last one to settle onto, so
     * a rule written in terms of "the next card" has nothing to pick and the row is left
     * wherever the fling stopped.
     */
    @Test
    public void itsettlesEvenAfterBeingThrownAtTheEndOfTheRow() {
        for (int i = 0; i < 4; i++) {
            onView(withId(R.id.tags_scrollable_area)).perform(swipeLeft());
        }

        Eventually.check(() -> assertTrue(
                "swiped past the last card, the row rested "
                        + this.theMap.howFarOffCentreTheNearestCardIs() + "px off centre",
                this.theMap.howFarOffCentreTheNearestCardIs() <= SETTLED_WITHIN_PX));

        for (int i = 0; i < 6; i++) {
            onView(withId(R.id.tags_scrollable_area)).perform(swipeRight());
        }

        Eventually.check(() -> assertTrue(
                "swiped back past the first card, the row rested "
                        + this.theMap.howFarOffCentreTheNearestCardIs() + "px off centre",
                this.theMap.howFarOffCentreTheNearestCardIs() <= SETTLED_WITHIN_PX));
    }

    /** Where the first card sits, as a cheap way to tell whether the row moved at all. */
    private int leftEdgeOfTheFirstCard() {
        final List<android.view.View> cards = this.theMap.cards();
        if (cards.isEmpty()) {
            return Integer.MIN_VALUE;
        }

        final int[] at = new int[2];
        final int[] found = {Integer.MIN_VALUE};
        this.theMap.scenario().onActivity(activity -> {
            cards.get(0).getLocationOnScreen(at);
            found[0] = at[0];
        });
        return found[0];
    }
}
