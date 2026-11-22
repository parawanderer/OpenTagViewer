package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Moving a tag card in the map's carousel, including when {@code removeView} does not let go.
 *
 * <p>"The specified child already has a parent" came out of the reorder step on 2026-11-21,
 * after the map screen was rebuilt. The first test reproduces that exception with the plain
 * removeView/addView pair the map used, so the others are known to be about the real thing.
 */
@RunWith(AndroidJUnit4.class)
public class CardSlotsTest {

    private final Context context = getInstrumentation().getTargetContext();

    private LinearLayout aRowOf(final View... cards) {
        final LinearLayout row = new LinearLayout(this.context);
        for (final View card : cards) {
            row.addView(card);
        }
        return row;
    }

    private View aCard() {
        return new FrameLayout(this.context);
    }

    @Test
    public void aPlainRemoveAndAddThrowsForACardHeldInATransition() {
        final View first = this.aCard();
        final View second = this.aCard();
        final LinearLayout row = this.aRowOf(first, second);

        getInstrumentation().runOnMainSync(() -> {
            row.startViewTransition(second);
            row.removeView(second);
            assertThrows(IllegalStateException.class, () -> row.addView(second, 0));
        });
    }

    @Test
    public void aCardHeldInATransitionStillMoves() {
        final View first = this.aCard();
        final View second = this.aCard();
        final LinearLayout row = this.aRowOf(first, second);

        getInstrumentation().runOnMainSync(() -> {
            row.startViewTransition(second);
            CardSlots.placeAt(row, second, 0);
        });

        assertSame(second, row.getChildAt(0));
        assertSame(first, row.getChildAt(1));
        assertEquals(2, row.getChildCount());
    }

    @Test
    public void aCardInAnotherRowIsTakenOutOfIt() {
        final View card = this.aCard();
        final LinearLayout old = this.aRowOf(card);
        final LinearLayout row = this.aRowOf(this.aCard());

        getInstrumentation().runOnMainSync(() -> CardSlots.placeAt(row, card, 0));

        assertSame(row, card.getParent());
        assertEquals(0, old.getChildCount());
    }

    @Test
    public void aCardAlreadyInPlaceIsLeftAlone() {
        final View first = this.aCard();
        final LinearLayout row = this.aRowOf(first, this.aCard());
        // A layout request is what a remove/add would cause, and what loses the scroll position.
        getInstrumentation().runOnMainSync(() -> {
            row.measure(0, 0);
            row.layout(0, 0, 100, 100);
        });

        getInstrumentation().runOnMainSync(() -> CardSlots.placeAt(row, first, 0));

        assertSame(first, row.getChildAt(0));
        assertEquals("the row was disturbed for a card already in place",
                false, row.isLayoutRequested());
    }

    @Test
    public void aNewCardGoesInAtTheEndWhenTheIndexIsPastIt() {
        final LinearLayout row = this.aRowOf(this.aCard());
        final View card = this.aCard();

        getInstrumentation().runOnMainSync(() -> CardSlots.placeAt(row, card, 5));

        assertSame(card, row.getChildAt(1));
    }
}
