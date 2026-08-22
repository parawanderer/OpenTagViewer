package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withClassName;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.MyDevicesListActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.db.room.entity.UserBeaconOptions;

/**
 * Putting the tag list in the order the user wants it.
 *
 * <p><b>Two ways in, and they exist because one gesture cannot do both jobs.</b> Dragging a row
 * by its handle moves one tag, which is the common case. <i>Move to top</i> in the selection menu
 * moves however many are selected, which is the case dragging is worst at - and it needs no
 * gesture, so it cannot collide with the long press that already means "start selecting".
 *
 * <p><b>What is actually asserted is the database, not the pixels.</b> An arrangement that looks
 * right and is not stored is the failure that matters: it survives until the screen is closed
 * and then silently reverts, which reads as the app forgetting rather than as a bug. So every
 * test here ends by reading {@code ui_order} back out of Room.
 *
 * <p>Seeds the real on-device database for the same reason {@code RemoveAccountTagTest} does -
 * {@link OpenTagViewerDatabase#getInstance} is a plain singleton with no seam - and deletes
 * everything it wrote by id, before and after, so a crashed run cannot poison the next class.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ArrangingTheTagListTest {

    private static final String FIRST = "arrange-test-1";
    private static final String SECOND = "arrange-test-2";
    private static final String THIRD = "arrange-test-3";

    private static final String FIRST_NAME = "Aardvark Tag";
    private static final String SECOND_NAME = "Badger Tag";
    private static final String THIRD_NAME = "Capybara Tag";

    private static final String A_TEST_USER = "arrangingthetaglisttest@example.invalid";

    /** An accessory: empty model, so none of these is read as one of the owner's devices. */
    private static final String A_PLIST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<plist version=\"1.0\"><dict>"
            + "<key>batteryLevel</key><integer>1</integer>"
            + "<key>model</key><string></string>"
            + "<key>pairingDate</key><date>2025-02-27T20:03:32Z</date>"
            + "<key>privateKey</key><dict><key>key</key><dict>"
            + "<key>data</key><data>bm90LWEtcmVhbC1rZXk=</data></dict></dict>"
            + "<key>productId</key><integer>21760</integer>"
            + "<key>stableIdentifier</key><array><string>2001~#0~#A0</string></array>"
            + "<key>systemVersion</key><string>2.0.73</string>"
            + "<key>vendorId</key><integer>76</integer>"
            + "</dict></plist>";

    private OpenTagViewerDatabase db;
    private ActivityScenario<?> scenario;
    private long importId;

    @Before
    public void seedTheDatabase() {
        final Context context = getInstrumentation().getTargetContext();
        this.db = OpenTagViewerDatabase.getInstance(context);

        this.removeEverythingThisTestWrites();

        this.importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2")
                .importedAt(1_700_000_000_000L)
                .exportedAt(1_699_000_000_000L)
                .sourceUser(A_TEST_USER)
                .exportedVia("OpenTagViewer.wizard:test")
                .build());

        this.insert(FIRST, FIRST_NAME);
        this.insert(SECOND, SECOND_NAME);
        this.insert(THIRD, THIRD_NAME);
    }

    @After
    public void putTheDatabaseBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        this.removeEverythingThisTestWrites();
    }

    // --- move to top --------------------------------------------------------------------------

    /**
     * <b>Selecting one tag and choosing Move to top puts it first, and remembers.</b>
     *
     * <p>The read-back is the assertion. Everything before it only gets the app into the state
     * where the write should have happened.
     */
    @Test
    public void movingOneTagToTheTopIsStored() {
        this.openTheList();

        this.chooseMoveToTopAfterSelecting(THIRD_NAME);

        Eventually.check(() -> assertEquals(
                "the tag moved to the top should be stored first", Integer.valueOf(0),
                this.storedOrderOf(THIRD)));

        assertNotNull("every visible tag gets a position, not only the one that moved",
                this.storedOrderOf(FIRST));
        assertNotNull(this.storedOrderOf(SECOND));
    }

    /**
     * <b>Two selected tags come to the front in list order, not tick order.</b>
     *
     * <p>Selected third-then-first; they must land first-then-third, because the list is what
     * the user was looking at when they chose.
     */
    @Test
    public void severalTagsMovedToTheTopKeepTheirListOrder() {
        this.openTheList();

        this.chooseMoveToTopAfterSelecting(THIRD_NAME, FIRST_NAME);

        Eventually.check(() -> assertEquals(Integer.valueOf(0), this.storedOrderOf(FIRST)));

        assertEquals("the two moved tags should keep the order they appeared in",
                Integer.valueOf(1), this.storedOrderOf(THIRD));
        assertEquals("the tag that was not selected should fall in behind",
                Integer.valueOf(2), this.storedOrderOf(SECOND));
    }

    /**
     * <b>The arrangement is what the screen shows next time it opens.</b>
     *
     * <p>Storing the right numbers is only half of it - a stored order nothing reads is the same
     * as no order at all. This closes the screen and opens it again.
     */
    @Test
    public void theArrangementIsWhatTheListShowsWhenItReopens() {
        this.openTheList();
        this.chooseMoveToTopAfterSelecting(THIRD_NAME);
        Eventually.check(() -> assertEquals(Integer.valueOf(0), this.storedOrderOf(THIRD)));

        this.scenario.close();
        this.openTheList();

        Eventually.check(() -> assertEquals(
                "the list did not come back in the order it was arranged into",
                List.of(THIRD_NAME, FIRST_NAME, SECOND_NAME), this.namesOnScreen()));
    }

    /**
     * <b>Move to top is offered but disabled with nothing selected</b>, like the rest of that
     * menu. An item that is present and silently does nothing is the thing this repo already
     * fixed once for Export History and Remove.
     */
    @Test
    public void moveToTopIsDisabledWhenNothingIsSelected() {
        this.openTheList();

        // Into selection mode, then out of the selection itself, so the bar is up with nothing
        // ticked - which is reachable by tapping the one selected row again.
        onView(withText(FIRST_NAME)).perform(longClick());
        onView(withText(FIRST_NAME)).perform(click());

        this.openTheSelectionMenu();

        Eventually.check(() -> menuItem(R.string.move_to_top)
                .check(matches(not(isEnabled()))));
    }

    /**
     * And enabled once something is ticked.
     *
     * <p>Here so the check above cannot pass by accident. A matcher aimed at the wrong view
     * reports "not enabled" for everything, including the cases that are fine - so the negative
     * assertion is only worth anything next to a positive one that uses the same matcher.
     */
    @Test
    public void moveToTopIsOfferedOnceSomethingIsSelected() {
        this.openTheList();

        onView(withText(FIRST_NAME)).perform(longClick());
        this.openTheSelectionMenu();

        Eventually.check(() -> menuItem(R.string.move_to_top).check(matches(isEnabled())));
    }

    // --- the drag handle ----------------------------------------------------------------------

    /**
     * <b>Every row has a handle to grab, and it is not the row itself.</b>
     *
     * <p>The handle exists because long press is taken. If it ever stopped being inflated the
     * list would look identical and simply not reorder, with no error - so this asserts the view
     * is there and displayed rather than merely present.
     */
    @Test
    public void everyRowOffersSomethingToDragBy() {
        this.openTheList();

        Eventually.check(() -> assertEquals("every row should have a drag handle",
                this.rowCount(), this.visibleDragHandles()));
    }

    /**
     * <b>The handle goes away while selecting, and comes back afterwards.</b>
     *
     * <p>Both directions, because this is a RecyclerView: a version that only hid it would leave
     * handleless rows scattered through the list after any pass through selection mode, on
     * whichever rows happened to be recycled. That is the bug this half exists to catch.
     */
    @Test
    public void theHandleStandsAsideForSelectionAndComesBack() {
        this.openTheList();

        onView(withText(FIRST_NAME)).perform(longClick());
        Eventually.check(() -> assertEquals(
                "the drag handle should not share the row with the selection tick",
                0, this.visibleDragHandles()));

        onView(withId(R.id.selection_close_button)).perform(click());
        Eventually.check(() -> assertEquals(
                "the handles should all come back when selection ends",
                this.rowCount(), this.visibleDragHandles()));
    }

    // --- helpers ------------------------------------------------------------------------------

    private void openTheList() {
        this.scenario = ActivityScenario.launch(MyDevicesListActivity.class);
        Eventually.check(() -> onView(withText(FIRST_NAME)).check(matches(isDisplayed())));
    }

    private void openTheSelectionMenu() {
        Eventually.check(() -> onView(withId(R.id.selection_menu_button))
                .check(matches(isDisplayed())));
        onView(withId(R.id.selection_menu_button)).perform(click());
    }

    /**
     * A popup menu row, by its title.
     *
     * <p><b>The row, not the label.</b> {@code withText} finds the {@code TextView} inside the
     * item, and that reports itself enabled whatever the {@code MenuItem} says - so an assertion
     * aimed there passes for a disabled item and proves nothing. The enabled state lives on the
     * {@code ListMenuItemView} that contains it.
     */
    private static androidx.test.espresso.ViewInteraction menuItem(final int title) {
        return onView(allOf(
                withClassName(endsWith("ListMenuItemView")),
                hasDescendant(withText(title))))
                .inRoot(isPlatformPopup());
    }

    /** Long press the first name, tap any others, then pick Move to top. */
    private void chooseMoveToTopAfterSelecting(final String... names) {
        onView(withText(names[0])).perform(longClick());
        for (int i = 1; i < names.length; i++) {
            onView(withText(names[i])).perform(click());
        }

        this.openTheSelectionMenu();

        Eventually.check(() -> onView(withText(R.string.move_to_top))
                .inRoot(isPlatformPopup())
                .check(matches(isDisplayed())));
        onView(withText(R.string.move_to_top)).inRoot(isPlatformPopup()).perform(click());
    }

    /** What {@code ui_order} says, or null if this tag has no options row or no position. */
    private Integer storedOrderOf(final String beaconId) {
        final UserBeaconOptions held = this.db.userBeaconOptionsDao().getById(beaconId);
        return held == null ? null : held.uiOrder;
    }

    private List<String> namesOnScreen() {
        final List<String> names = new ArrayList<>();

        this.scenario.onActivity(activity -> {
            final RecyclerView list = activity.findViewById(R.id.my_devices_list);
            for (int i = 0; i < list.getChildCount(); i++) {
                names.add(((android.widget.TextView) list.getChildAt(i)
                        .findViewById(R.id.list_item_device_name)).getText().toString());
            }
        });

        return names;
    }

    private int rowCount() {
        final int[] count = {0};
        this.scenario.onActivity(activity -> count[0] =
                ((RecyclerView) activity.findViewById(R.id.my_devices_list)).getChildCount());
        return count[0];
    }

    private int visibleDragHandles() {
        final int[] count = {0};

        this.scenario.onActivity(activity -> {
            final RecyclerView list = activity.findViewById(R.id.my_devices_list);
            for (int i = 0; i < list.getChildCount(); i++) {
                final android.view.View handle =
                        list.getChildAt(i).findViewById(R.id.list_item_drag_handle);
                if (handle != null && handle.getVisibility() == android.view.View.VISIBLE) {
                    count[0]++;
                }
            }
        });

        return count[0];
    }

    private void insert(final String id, final String name) {
        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(id)
                .importId(this.importId)
                .content(A_PLIST)
                .version("0.0.2")
                .fromAccount(false)
                .isRemoved(false)
                .build());

        this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                .id(id)
                .importId(this.importId)
                .version("0.0.2")
                .isRemoved(false)
                .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                        + "<key>identifier</key><string>" + id + "</string>"
                        + "<key>name</key><string>" + name + "</string>"
                        + "</dict></plist>")
                .build());
    }

    private void removeEverythingThisTestWrites() {
        // By id, never clearAllTables: this is the real database, and on a developer's own
        // device that would take their imported tags and every location ever fetched for them.
        for (final String id : new String[] {FIRST, SECOND, THIRD}) {
            this.db.userBeaconOptionsDao().deleteById(id);
            this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(id).build());
            this.db.beaconNamingRecordDao().delete(BeaconNamingRecord.builder().id(id).build());
        }

        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
    }
}
