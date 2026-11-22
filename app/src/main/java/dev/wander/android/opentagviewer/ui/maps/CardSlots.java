package dev.wander.android.opentagviewer.ui.maps;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Putting a tag card at its place in the map's carousel.
 *
 * <p><b>{@code removeView} does not always detach.</b> A child that is animating, or that its
 * parent is holding in a view transition, stays in the parent's "disappearing" list with its
 * parent still set until that ends, and the {@code addView} straight after it throws
 * "The specified child already has a parent". That was logged on 2026-11-21 just after the map
 * screen was rebuilt, from the reorder step, and the exception ended that refresh. So a card is
 * detached the long way first: animation cleared, removed, and any transition on it ended.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CardSlots {

    /** Put {@code card} at {@code index} in {@code container}, unless it is already there. */
    public static void placeAt(final ViewGroup container, final View card, final int index) {
        if (card.getParent() == container && container.indexOfChild(card) == index) {
            return;
        }
        detach(card);
        container.addView(card, Math.min(index, container.getChildCount()));
    }

    /** Take {@code card} out of whatever holds it, completely. */
    public static void detach(final View card) {
        final ViewParent parent = card.getParent();
        if (!(parent instanceof ViewGroup)) {
            return;
        }
        final ViewGroup group = (ViewGroup) parent;
        // Before removeView: a view with an Animation is kept as a disappearing child. Not
        // covered by CardSlotsTest: that only happens to a view in a window, and the test's
        // rows are not in one. The transition half below is, and fails without its line.
        card.clearAnimation();
        group.removeView(card);
        // After it: this is what lets go of a child kept for a view transition.
        group.endViewTransition(card);
    }
}
