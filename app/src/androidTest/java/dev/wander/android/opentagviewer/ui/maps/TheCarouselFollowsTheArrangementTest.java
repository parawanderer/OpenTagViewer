package dev.wander.android.opentagviewer.ui.maps;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.R;

/**
 * The map's tag carousel shows the order the user arranged.
 *
 * <p><b>Two separate things can go wrong here, and only one of them is obvious.</b>
 *
 * <p>The first is the drawing: {@code MapsActivity} holds its tags in a {@code ConcurrentHashMap},
 * so left to itself the carousel is in hash order - arbitrary but stable, which is exactly the
 * kind of wrong that looks deliberate. Cards are also created once and reused, so whatever order
 * they were first added in sticks for the life of the screen.
 *
 * <p>The second is <b>when</b>. The map keeps its tags in memory and reads the arrangement off
 * them, so a map that is still alive behind the device list is holding objects from before the
 * drag. Storing the new order and returning to that screen changes nothing on it - the tags only
 * move after the app is restarted, which reads as the app having ignored the drag and then
 * mysteriously agreeing with it later. That was the actual behaviour when this test was written,
 * and it is the half a test of the drawing alone would have missed entirely.
 *
 * <p>Runs against {@link FakeMapProvider}, so no Play Services and no real map - see
 * {@link AMapWithTagsOnIt}.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class TheCarouselFollowsTheArrangementTest {

    private static final String WALLET = "Wallet";
    private static final String KEYS = "Keys";
    private static final String BAG = "Bag";

    private final AMapWithTagsOnIt theMap = new AMapWithTagsOnIt();

    @After
    public void putItBack() {
        this.theMap.putItBack();
    }

    /**
     * <b>A stored arrangement is the order the cards are in.</b>
     *
     * <p>Seeded Wallet, Keys, Bag and arranged Bag, Wallet, Keys - deliberately not a reversal
     * and not the seed order, so neither "it kept the database order" nor "it reversed something"
     * can pass.
     */
    @Test
    public void thecardsAreInTheOrderTheUserArrangedThem() {
        this.theMap.seed(WALLET, KEYS, BAG).arrangedAs(2, 0, 1);
        this.theMap.open();

        Eventually.check(() -> assertEquals(
                "the carousel is not showing the arrangement that was stored",
                List.of(BAG, WALLET, KEYS), this.theMap.cardNames()));
    }

    /**
     * <b>With nothing arranged, the cards are still in a defined order rather than hash order.</b>
     *
     * <p>Pinned because the failure is invisible: hash order is arbitrary but stable, so a broken
     * default looks like a considered decision and nobody ever reports it. All three of these are
     * accessories, so {@code TagOrder} should leave them in the order the database gave.
     */
    @Test
    public void withNoArrangementTheCardsKeepTheOrderTheyCameInAs() {
        this.theMap.seed(WALLET, KEYS, BAG);
        this.theMap.open();

        Eventually.check(() -> assertEquals(
                List.of(WALLET, KEYS, BAG), this.theMap.cardNames()));
    }

    /**
     * <b>Arranging on the device list moves the cards on the map behind it, without a restart.</b>
     *
     * <p>The round trip, and the reason this class exists. Everything can be stored correctly and
     * drawn correctly and this still fail, because the map that is being returned to was built
     * before the arrangement existed.
     *
     * <p>Uses <i>Move to top</i> rather than a drag: the two share the same storing and the same
     * "tell the map" flag, and a menu tap is not a gesture that can miss. The drag itself is
     * covered by {@code ArrangingTheTagListTest}.
     */
    @Test
    public void arrangingOnTheDeviceListMovesTheCardsWhenYouComeBack() {
        this.theMap.seed(WALLET, KEYS, BAG);
        this.theMap.open();

        Eventually.check(() -> assertEquals(
                List.of(WALLET, KEYS, BAG), this.theMap.cardNames()));

        this.openTheDeviceList();

        // Bag to the front.
        onView(withText(BAG)).perform(longClick());
        Eventually.check(() -> onView(withId(R.id.selection_menu_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.selection_menu_button)).perform(click());
        Eventually.check(() -> onView(withText(R.string.move_to_top))
                .inRoot(isPlatformPopup()).check(matches(isDisplayed())));
        onView(withText(R.string.move_to_top)).inRoot(isPlatformPopup()).perform(click());

        this.goBackToTheMap();

        Eventually.check(() -> assertEquals(
                "the map did not pick up the arrangement made while it was in the background;"
                        + " it will only look right after a restart",
                List.of(BAG, WALLET, KEYS), this.theMap.cardNames()));
    }

    // --- getting between the two screens --------------------------------------------------------

    private void openTheDeviceList() {
        Eventually.check(() -> onView(withId(R.id.button_more_settings))
                .check(matches(isDisplayed())));
        onView(withId(R.id.button_more_settings)).perform(click());

        onView(withText(R.string.my_devices)).inRoot(isPlatformPopup()).perform(click());

        Eventually.check(() -> onView(withText(WALLET)).check(matches(isDisplayed())));
    }

    private void goBackToTheMap() {
        androidx.test.espresso.Espresso.pressBack();

        // The map recreates on the way back, so the carousel is rebuilt from scratch - wait for
        // it to exist again before asking what is in it, or an empty list reads as a pass.
        Eventually.check(() -> assertEquals(
                "the carousel never came back after returning to the map",
                3, this.theMap.cardNames().size()));
    }
}
