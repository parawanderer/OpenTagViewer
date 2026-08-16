package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.data.model.BeaconLocationReport;

/**
 * Selecting tags in the My Devices list.
 *
 * <p>What this protects is the bookkeeping, not the pixels. Selection is keyed by beacon id
 * rather than by row position, and the reason is a bug that would otherwise be very hard to
 * see: removing a device shifts every position after it, so a position-keyed selection quietly
 * starts pointing at different tags than the ones with ticks next to them. Somebody then
 * exports or deletes the wrong ones, and nothing anywhere reports an error.
 *
 * <p>Drives the real adapter against the real row layout, with no activity and no database.
 */
@RunWith(AndroidJUnit4.class)
public class DeviceSelectionTest {

    private final List<BeaconInformation> beacons = new ArrayList<>();
    private final Map<String, BeaconLocationReport> locations = new HashMap<>();

    private DeviceListAdaptor adaptor;
    private AtomicInteger reportedCount;
    private AtomicReference<BeaconInformation> opened;

    private static BeaconInformation beacon(final String id, final String name) {
        return BeaconInformation.builder()
                .beaconId(id)
                .originalName(name)
                .build();
    }

    @Before
    public void buildTheList() {
        final Context context = new ContextThemeWrapper(
                getInstrumentation().getTargetContext(), R.style.Theme_OpenTagViewer);

        this.beacons.clear();
        this.beacons.add(beacon("aaa", "Wallet"));
        this.beacons.add(beacon("bbb", "Backpack"));
        this.beacons.add(beacon("ccc", "Keys"));

        this.reportedCount = new AtomicInteger(-1);
        this.opened = new AtomicReference<>(null);

        getInstrumentation().runOnMainSync(() -> this.adaptor = new DeviceListAdaptor(
                context,
                context.getResources(),
                this.beacons,
                this.locations,
                this.opened::set,
                (view, device) -> { },
                this.reportedCount::set));
    }

    @Test
    public void aLongPressSelectsOnlyThePressedRow() {
        this.adaptor.startSelectionWith("bbb");

        assertTrue("a long press should start selection mode", this.adaptor.isSelectionMode());
        assertEquals("only the pressed row should be selected",
                List.of("bbb"), idsOf(this.adaptor.getSelectedBeacons()));
    }

    @Test
    public void tappingRowsWhileSelectingAddsAndRemovesThem() {
        this.adaptor.startSelectionWith("aaa");
        this.adaptor.toggleSelection("ccc");

        assertEquals(List.of("aaa", "ccc"), idsOf(this.adaptor.getSelectedBeacons()));

        this.adaptor.toggleSelection("aaa");
        assertEquals(List.of("ccc"), idsOf(this.adaptor.getSelectedBeacons()));
    }

    @Test
    public void theCountIsReportedSoTheBarCanSayHowManyArePicked() {
        this.adaptor.startSelectionWith("aaa");
        this.adaptor.toggleSelection("bbb");
        assertEquals(2, this.reportedCount.get());

        this.adaptor.toggleSelection("bbb");
        assertEquals(1, this.reportedCount.get());
    }

    /**
     * The reason selection is keyed by id.
     *
     * <p>Remove the first row while the last two are selected. With positions, the survivors
     * shift from 1 and 2 to 0 and 1, and a position-keyed selection would now name the wrong
     * tags while the ticks on screen stayed where they were.
     */
    @Test
    public void removingARowDoesNotRepointTheSelectionAtDifferentTags() {
        this.adaptor.startSelectionWith("bbb");
        this.adaptor.toggleSelection("ccc");

        this.beacons.remove(0);
        this.adaptor.notifyItemRemoved(0);

        assertEquals("the same two tags should still be selected",
                List.of("bbb", "ccc"), idsOf(this.adaptor.getSelectedBeacons()));
    }

    @Test
    public void deselectingEverythingStaysInSelectionMode() {
        // Otherwise the bar vanishes mid-task and the user is back on a screen where a tap
        // opens a tag, which is not what they were doing.
        this.adaptor.startSelectionWith("aaa");
        this.adaptor.toggleSelection("aaa");

        assertTrue("selection mode should survive an empty selection",
                this.adaptor.isSelectionMode());
        assertTrue(this.adaptor.getSelectedBeacons().isEmpty());
    }

    @Test
    public void clearingSelectionLeavesTheModeEntirely() {
        this.adaptor.startSelectionWith("aaa");
        this.adaptor.clearSelection();

        assertFalse(this.adaptor.isSelectionMode());
        assertTrue(this.adaptor.getSelectedBeacons().isEmpty());
    }

    @Test
    public void aTapOpensTheTagWhenNotSelecting() throws Throwable {
        bindRow(0).performClick();

        assertEquals("a tap outside selection mode should open the tag",
                "aaa", this.opened.get().getBeaconId());
    }

    @Test
    public void aTapSelectsRatherThanOpeningWhileSelecting() throws Throwable {
        this.adaptor.startSelectionWith("bbb");

        bindRow(0).performClick();

        assertEquals("no tag should have been opened", null, this.opened.get());
        assertEquals("the tapped row should have joined the selection",
                List.of("aaa", "bbb"), idsOf(this.adaptor.getSelectedBeacons()));
    }

    /** Inflates and binds one row, returning the view its click listener is attached to. */
    private View bindRow(final int position) throws Throwable {
        final AtomicReference<View> row = new AtomicReference<>();

        getInstrumentation().runOnMainSync(() -> {
            final Context context = new ContextThemeWrapper(
                    getInstrumentation().getTargetContext(), R.style.Theme_OpenTagViewer);

            ViewGroup parent = new FrameLayout(context);
            DeviceListAdaptor.ViewHolder holder =
                    this.adaptor.onCreateViewHolder(parent, 0);
            this.adaptor.onBindViewHolder(holder, position);
            row.set(holder.getContainer());
        });

        return row.get();
    }

    private static List<String> idsOf(final List<BeaconInformation> selected) {
        return selected.stream().map(BeaconInformation::getBeaconId).toList();
    }
}
