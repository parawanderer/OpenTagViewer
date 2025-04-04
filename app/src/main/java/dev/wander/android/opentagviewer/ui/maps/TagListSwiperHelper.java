package dev.wander.android.opentagviewer.ui.maps;

import static android.view.View.GONE;

import android.util.Log;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

/**
 * Helps handle moving around the tag frames/cards at the bottom of the Map screen.
 * Supports two actions: swipe and regular logical drag centering
 */
public class TagListSwiperHelper {
    private static final String TAG = TagListSwiperHelper.class.getSimpleName();
    private int xScrollStart = 0;
    private long xPosLastTime = 0;

    private static final double VELOCITY_LIMIT_ABS = 0.5E-6;

    private final HorizontalScrollView scrollContainer;
    private final Map<String, FrameLayout> dynamicCardsForTag;
    private final Consumer<String> onScrollToTagCallback;

    public TagListSwiperHelper(
            @NonNull HorizontalScrollView scrollContainer,
            @NonNull final Map<String, FrameLayout> dynamicCardsForTag,
            @NonNull Consumer<String> onScrollToTagCallback
            ) {
        this.scrollContainer = scrollContainer;
        this.dynamicCardsForTag = dynamicCardsForTag;
        this.onScrollToTagCallback = onScrollToTagCallback;
    }

    public void setupTagScrollArea() {
        scrollContainer.setOnTouchListener((v, event) -> {
            final int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                this.xScrollStart = v.getScrollX();
                this.xPosLastTime = System.nanoTime();
            } else if (action == MotionEvent.ACTION_UP) {
                return this.moveMostVisibleTagToCenter(); // returning true blocks (overrides) default scroll behaviour
            }
            return false;
        });
    }

    public String getCurrentPrimaryCard() {
        if (this.dynamicCardsForTag.isEmpty()) {
            return null;
        }

        final int containerWidth = this.scrollContainer.getWidth();
        final int containerMid = containerWidth / 2;

        String targetBeaconId = null;
        Integer minDiff = null;

        int[] locationTopLeftCorner = new int[2];
        for (var beaconId : this.dynamicCardsForTag.keySet()) {
            FrameLayout tag = Objects.requireNonNull(this.dynamicCardsForTag.get(beaconId));

            tag.getLocationOnScreen(locationTopLeftCorner);
            final int leftX = locationTopLeftCorner[0];
            final int rightX = leftX + tag.getWidth();

            if (rightX <= 0) continue;
            if (leftX >= containerWidth) continue;

            final int mid = (leftX + rightX) / 2;
            final int diff = containerMid - mid;
            if (minDiff == null || Math.abs(diff) < Math.abs(minDiff)) {
                minDiff = diff;
                targetBeaconId = beaconId;
            }
        }

        return targetBeaconId;
    }

    public void navigateToCard(final String beaconId) {
        if (!this.dynamicCardsForTag.containsKey(beaconId)) {
            Log.w(TAG, "Tried to navigate to card for beaconId=" + beaconId + ", which did not exist in the beaconId to Card map!");
            return;
        }

        final int containerWidth = this.scrollContainer.getWidth();
        final int containerMid = containerWidth / 2;

        FrameLayout tag = Objects.requireNonNull(this.dynamicCardsForTag.get(beaconId));
        int[] locationTopLeftCorner = new int[2];
        tag.getLocationOnScreen(locationTopLeftCorner);

        final int leftX = locationTopLeftCorner[0];
        final int rightX = leftX + tag.getWidth();

        final int mid = (leftX + rightX) / 2;
        final int diff = containerMid - mid;

        this.scrollContainer.smoothScrollBy(-diff, 0);
    }

    private int getSizeHalfCurrentCard() {
        // all the cards are the same size. We want half of this standard size.
        // it may change at runtime...
        var anyCard = this.dynamicCardsForTag.values().iterator().next();
        return anyCard.getWidth() / 2;
    }

    /**
     * This will try to handle two cases dynamically:
     *
     * The first is when we SWIPE. This is interpreted as a fast motion (high velocity)
     * over a rather short distance. We will interpret this as a "gesture" and will
     * try to move the card list in the direction of the "swipe" (even if the swipe didn't really move the card very far visually).
     * Here, VELOCITY_LIMIT_ABS is a bit arbitrary and is based on what "feels" good in the UI.
     *
     * In the second case, the velocity wasn't high and we are not interpreting this as a swipe.
     * In this case, we will try to find which card is currently the best candidate for being centred
     * by comparing all the cards' center positions to the screen center position.
     * The one which is the best candidate (whose center is closest aligned to the screen center)
     * will be chosen as the next candidate to be centered, and will be scrolled to.
     *
     * The logic deals with cases where the cards are currently missing.
     */
    private boolean moveMostVisibleTagToCenter() {
        Log.d(TAG, "Checking if we can move the most visible tag to the center of the tag scroll bar now!");

        if (this.scrollContainer.getVisibility() == GONE) return false; // not visible -> no scrolling needed
        if (this.dynamicCardsForTag.isEmpty()) return false; // same thing: no items -> no scrolling needed

        final int currentScrollX = scrollContainer.getScrollX();
        // note that nanoTime will "run over" the long size limit at some point and produce a velocity in the opposite
        // direction due to the divisor becoming negative. That is why I take the abs. At that moment the calculation would be invalid.
        // However this case is very unlikely to occur at the exact right moment, so we'll just have to deal with the wrong calculation
        // at that moment producing a smaller than expected velocity
        final double velocity = ((double)currentScrollX - (double)this.xScrollStart) / Math.abs((double)System.nanoTime() - (double)this.xPosLastTime);
        final int dragDistance = Math.abs(currentScrollX - this.xScrollStart);

        if (Math.abs(velocity) >= VELOCITY_LIMIT_ABS && dragDistance <= this.getSizeHalfCurrentCard()) {
            return this.handleSwipeAction(velocity);
        } else {
            return this.handleSlowScrollAction();
        }
    }

    private boolean handleSlowScrollAction() {
        // otherwise find the card for which it is true that most of it is currently shown in the screen's view area.
        // (tie break on two showing exactly the same thing)
        final int containerWidth = this.scrollContainer.getWidth();
        final int containerMid = containerWidth / 2;

        String targetBeaconId = null;
        Integer minDiff = null;

        int[] locationTopLeftCorner = new int[2];
        for (var beaconId : this.dynamicCardsForTag.keySet()) {
            FrameLayout tag = Objects.requireNonNull(this.dynamicCardsForTag.get(beaconId));

            tag.getLocationOnScreen(locationTopLeftCorner);
            final int leftX = locationTopLeftCorner[0];
            final int rightX = leftX + tag.getWidth();

            if (rightX <= 0) continue; // out of view (to the left)
            if (leftX >= containerWidth) continue; // out of view (to the right)

            // contender for most central item.
            // on a normal phone screen this would be just 2 items at worst
            // but let's accommodate more just in case (for wider screens?)
            final int mid = (leftX + rightX) / 2;

            final int diff = containerMid - mid;
            if (minDiff == null || Math.abs(diff) < Math.abs(minDiff)) {
                minDiff = diff;
                targetBeaconId = beaconId;
            }
        }

        // we now know which tag to center on the page.
        // we want to move the scroll view such that this tag gets shown centrally
        // (as much as possible, at least)
        // => we need to move the current offset X by the non-absolute value of minDiff!
        if (minDiff == null) {
            Log.w(TAG, "Unexpected: could not find any item closest to middle!");
            return false;
        }

        this.scrollContainer.smoothScrollBy(-minDiff, 0);
        this.onScrollToTagCallback.accept(targetBeaconId); // raise the callback
        return true;
    }

    private boolean handleSwipeAction(final double velocity) {
        List<CardInfo> sortedByXPos = this.findThreeClosestToMiddle();
        final int size = sortedByXPos.size();

        if (size <= 1) {
            Log.d(TAG, "There was only one item so there's no other item to navigate to 'on swipe'. Ending here.");
            return false;
        }

        // TODO: these if-branches are a bit gross looking... Clean them up or something
        // (note: my initial go at doing so was unsuccessful and made it even harder to follow)

        // current config cases: where | is middle, * is target, [ ] is a card
        if (size == 3) {
            // two types of situations might occur here.
            // Either the middle item is the current one, or the current one is a side item:
            if (Math.abs(sortedByXPos.get(0).getDiff()) < Math.abs(sortedByXPos.get(1).getDiff())) {
                // CASE: [|][ ][ ]
                // only allowed to go to the right: [|][*][ ]
                if (velocity > 0) {
                    return navigateTo(sortedByXPos, 1);
                }
                return false; // we can't go to the left
            } else if (Math.abs(sortedByXPos.get(2).getDiff()) < Math.abs(sortedByXPos.get(1).getDiff())) {
                // CASE: [ ][ ][|]
                // only allowed to go to the left: [ ][*][|]
                if (velocity < 0) {
                    return navigateTo(sortedByXPos, 1);
                }
                return false; // we can't go to the right
            }
            // "average" case: [ ][|][ ]  (note that in reality there could have been any number of items to the left or right of these)
            // we determine which direction we swiped in and navigate to this one:
            //                           go left [*][|][ ]                    go right [ ][|][*]
            if (velocity < 0) {
                // go left [*][|][ ]
                return navigateTo(sortedByXPos, 0);
            }
            // go right [ ][|][*]
            return navigateTo(sortedByXPos, 2);
        }

        // if there's just 2 items, then we might not be able to go to the target pos:
        // we have:                 [ ][|]          OR            [|][ ]
        // we can accommodate only GO LEFT in the first case and GO RIGHT in the second case
        assert size == 2;
        if (Math.abs(sortedByXPos.get(0).getDiff()) < Math.abs(sortedByXPos.get(1).getDiff())) {
            // CASE: [|][ ]
            if (velocity > 0) {
                // only allowed to go right
                return navigateTo(sortedByXPos, 1);
            }
        } else {
            // CASE: [ ][|]
            if (velocity < 0) {
                // only allowed to go left
                return navigateTo(sortedByXPos, 0);
            }
        }
        Log.d(TAG, "Encountered unexpected swipe target case! Doing nothing.");
        return false;
    }

    private List<CardInfo> findThreeClosestToMiddle() {
        // find top 3
        final int containerWidth = this.scrollContainer.getWidth();
        final int containerMid = containerWidth / 2;

        // HEAP of (max) top 3 items with min distance to center.
        // necessarily at the end we will have items: [][][]
        // such that one of them is the most central one, one is to the left of the most central one,
        // one is to the right of the most central one.
        PriorityQueue<CardInfo> maxMinDistanceHeap = new PriorityQueue<>(
                3,
                Comparator.comparingInt(v -> -Math.abs(v.getDiff())) // make it a min heap
        );

        int[] locationTopLeftCorner = new int[2];
        for (var beaconId : this.dynamicCardsForTag.keySet()) {
            FrameLayout tag = Objects.requireNonNull(this.dynamicCardsForTag.get(beaconId));

            tag.getLocationOnScreen(locationTopLeftCorner);
            final int leftX = locationTopLeftCorner[0];
            final int rightX = leftX + tag.getWidth();

            final int mid = (leftX + rightX) / 2;
            final int diff = containerMid - mid;

            if (maxMinDistanceHeap.size() < 3) {
                maxMinDistanceHeap.add(new CardInfo(tag, diff, leftX, beaconId));
            } else if (Math.abs(maxMinDistanceHeap.peek().getDiff()) > Math.abs(diff)) {
                maxMinDistanceHeap.poll();
                maxMinDistanceHeap.add(new CardInfo(tag, diff, leftX, beaconId));
            }
        }

        // triplets of <distance to middle>, <x position top left corner>, <actual container object>
        var sortedByXPos = maxMinDistanceHeap.stream()
                .sorted(Comparator.comparingInt(CardInfo::getLeftXPosition))
                .collect(Collectors.toList());

        return sortedByXPos;
    }

    private boolean navigateTo(List<CardInfo> items, int newPos) {
        CardInfo target = items.get(newPos);
        final int diff = target.getDiff();
        this.scrollContainer.smoothScrollBy(-diff, 0);
        this.onScrollToTagCallback.accept(target.getBeaconId()); // raise the event
        return true;
    }

    @AllArgsConstructor
    @Getter
    private static final class CardInfo {
        private final FrameLayout container;
        private final Integer diff;
        private final Integer leftXPosition;
        private final String beaconId;
    }
}
