package dev.wander.android.opentagviewer;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.BatteryLevel;
import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.State;
import dev.wander.android.opentagviewer.ble.ForeignTagNames;
import dev.wander.android.opentagviewer.ble.NearbyDeviceScanner;
import dev.wander.android.opentagviewer.ble.NearbyDeviceSighting;
import dev.wander.android.opentagviewer.python.AppDependencies;
import io.reactivex.rxjava3.core.Observable;

/**
 * The Nearby devices screen, fed made-up sightings: an emulator has no Bluetooth to hear real
 * ones. Covers what a person sees in each state, and writes screenshots in light and dark mode
 * for looking at the screen without a tag nearby.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class NearbyDevicesScreenTest {

    private static final String NAMED_PREFIX = "c0ffee123456";
    private static final String NAME = "Neighbour's keys";

    private ActivityScenario<NearbyDevicesActivity> scenario;
    private ForeignTagNames names;
    private int nightModeBefore;

    /** Hears whatever it was given, as a real scan would, without a radio. */
    private static final class FakeScanner extends NearbyDeviceScanner {
        private final Observable<NearbyDeviceSighting> heard;

        FakeScanner(final Observable<NearbyDeviceSighting> heard) {
            this.heard = heard;
        }

        @Override
        public Observable<NearbyDeviceSighting> scan(final Context context) {
            return this.heard;
        }
    }

    @Before
    public void setUp() {
        this.names = new ForeignTagNames(getInstrumentation().getTargetContext());
        this.nightModeBefore = AppCompatDelegate.getDefaultNightMode();
    }

    @After
    public void putTheRealOnesBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        AppDependencies.reset();
        this.names.rename(NAMED_PREFIX, null);
        getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setDefaultNightMode(this.nightModeBefore));
    }

    private static NearbyDeviceSighting sighting(final String prefix, final int rssi,
                                                 final BatteryLevel battery, final State state,
                                                 final long agoMs) {
        return NearbyDeviceSighting.builder()
                .mac(("aa:" + prefix.substring(0, 2) + ":" + prefix.substring(2, 4)
                        + ":" + prefix.substring(4, 6) + ":0c:51").toUpperCase())
                .keyPrefix(prefix)
                .rssi(rssi)
                .batteryLevel(battery)
                .statusByte(0x10)
                .state(state)
                .seenAtMs(System.currentTimeMillis() - agoMs)
                .build();
    }

    /** Four devices: close and lost, one with a name, one far away, one that went quiet. */
    private static List<NearbyDeviceSighting> aBusyHallway() {
        return List.of(
                sighting("1a2b3c4d5e6f", -52, BatteryLevel.FULL, State.SEPARATED, 0),
                sighting(NAMED_PREFIX, -68, BatteryLevel.MEDIUM, State.OWNER_NEARBY, 2_000),
                sighting("77e1a0b2c3d4", -91, BatteryLevel.LOW, State.OWNER_NEARBY, 5_000),
                sighting("deadbeef0001", -60, BatteryLevel.FULL, State.OWNER_NEARBY, 45_000));
    }

    private void open(final Observable<NearbyDeviceSighting> heard) {
        AppDependencies.replaceNearbyDeviceScanner(new FakeScanner(heard));
        this.scenario = ActivityScenario.launch(NearbyDevicesActivity.class);
    }

    private int rowCount() {
        final int[] count = {0};
        this.scenario.onActivity(a -> {
            final RecyclerView list = a.findViewById(R.id.nearby_devices_list);
            count[0] = list.getAdapter() == null ? 0 : list.getAdapter().getItemCount();
        });
        return count[0];
    }

    private String rowText(final int position, final int id) {
        final String[] text = {null};
        this.scenario.onActivity(a -> {
            final RecyclerView list = a.findViewById(R.id.nearby_devices_list);
            final RecyclerView.ViewHolder row = list.findViewHolderForAdapterPosition(position);
            text[0] = row == null ? null
                    : ((TextView) row.itemView.findViewById(id)).getText().toString();
        });
        return text[0];
    }

    private void shot(final String name) {
        // Shot draws on the main thread itself and refuses to be called from it.
        final NearbyDevicesActivity[] activity = {null};
        this.scenario.onActivity(a -> activity[0] = a);
        Shot.of(activity[0], name);
    }

    @Test
    public void devicesAreListedNearestFirstWithWhatTheyBroadcast() {
        this.names.rename(NAMED_PREFIX, NAME);
        this.open(Observable.fromIterable(aBusyHallway()));

        Eventually.check(() -> assertEquals(4, this.rowCount()));
        Eventually.check(() -> assertTrue(this.rowText(0, R.id.nearby_item_details) != null));

        // Strongest signal first; the one heard 45 s ago goes last, as "offline".
        assertTrue(this.rowText(0, R.id.nearby_item_device_name).contains("1a2b"));
        assertTrue(this.rowText(0, R.id.nearby_item_details).contains(
                getInstrumentation().getTargetContext().getString(R.string.nearby_device_separated)));
        assertEquals(NAME, this.rowText(1, R.id.nearby_item_device_name));
        assertTrue(this.rowText(3, R.id.nearby_item_details).startsWith(
                getInstrumentation().getTargetContext().getString(R.string.nearby_device_offline)));

        this.shot("nearby_1_devices_light");
    }

    @Test
    public void theSameListInDarkMode() {
        getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
        this.names.rename(NAMED_PREFIX, NAME);
        this.open(Observable.fromIterable(aBusyHallway()));

        Eventually.check(() -> assertEquals(4, this.rowCount()));
        Eventually.check(() -> assertTrue(this.rowText(3, R.id.nearby_item_details) != null));

        this.shot("nearby_2_devices_dark");
    }

    @Test
    public void withNothingInRangeItSaysSo() {
        this.open(Observable.never());

        Eventually.check(() -> onView(withId(R.id.nearby_devices_empty_text))
                .check(matches(isDisplayed())));
        onView(withId(R.id.nearby_devices_empty_text))
                .check(matches(withText(R.string.nearby_devices_empty)));

        this.shot("nearby_3_empty");
    }

    /** A scan that ends at once is how Bluetooth being off arrives. "Nothing in range" would lie. */
    @Test
    public void withBluetoothOffItSaysThatRatherThanNothingInRange() {
        this.open(Observable.empty());

        Eventually.check(() -> onView(withId(R.id.nearby_devices_empty_text))
                .check(matches(withText(R.string.nearby_devices_scan_unavailable))));

        this.shot("nearby_4_bluetooth_off");
    }
}
