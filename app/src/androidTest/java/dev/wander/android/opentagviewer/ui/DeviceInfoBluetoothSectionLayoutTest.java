package dev.wander.android.opentagviewer.ui;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.R;

/**
 * The "Over Bluetooth" section on the device screen: what this phone's own radio heard from the
 * tag, as opposed to everything above it, which is the accessory record Apple keeps.
 *
 * <p><b>Hidden is its resting state, and that is the part worth pinning.</b> The section is only
 * filled in once the tag has actually been heard, and it deliberately never falls back to the
 * iCloud battery value - the whole reason it sits outside the debug panel is that it says where
 * its numbers came from. A stray edit making it visible by default would put an empty section on
 * every device screen, reading as "nothing detected", which is a claim nobody here can make.
 *
 * <p>Inflation only: no activity, no account, no Bluetooth. Run with
 * {@code ./gradlew :app:testEmulatorDebugAndroidTest}.
 */
@RunWith(AndroidJUnit4.class)
public class DeviceInfoBluetoothSectionLayoutTest {

    private static final int SCREEN_WIDTH_PX = 1080;

    /**
     * Every piece the section is made of, since they are shown and hidden together.
     *
     * <p>The divider and the heading are in here on purpose: leaving either behind when the rows
     * go would put a titled, empty section on the screen, which is the failure this whole class
     * is about.
     */
    private static final int[] SECTION_VIEWS = {
            R.id.device_ble_divider,
            R.id.device_ble_header,
            R.id.device_settings_ble_last_seen,
            R.id.device_settings_ble_signal,
            R.id.device_settings_ble_battery,
    };

    private Context context;

    @Before
    public void setUp() {
        this.context = new ContextThemeWrapper(
                getInstrumentation().getTargetContext(), R.style.Theme_OpenTagViewer);
    }

    private View inflateDeviceInfo() {
        final View[] root = new View[1];
        getInstrumentation().runOnMainSync(() ->
                root[0] = LayoutInflater.from(this.context)
                        .inflate(R.layout.activity_device_info, null));
        return root[0];
    }

    @Test
    public void theScreenStillInflates() {
        assertNotNull(this.inflateDeviceInfo());
    }

    /**
     * Every id {@code DeviceInfoActivity.showBluetoothSection} looks up has to resolve, or that
     * piece is simply never shown and nothing fails.
     */
    @Test
    public void theWholeSectionExists() {
        final View screen = this.inflateDeviceInfo();

        for (final int id : SECTION_VIEWS) {
            assertNotNull("missing view in the Over Bluetooth section", screen.findViewById(id));
        }
    }

    @Test
    public void theSectionIsHiddenUntilTheTagIsHeard() {
        final View screen = this.inflateDeviceInfo();

        for (final int id : SECTION_VIEWS) {
            assertEquals("a tag this phone has never heard must show no section at all,"
                            + " not an empty one",
                    View.GONE, screen.findViewById(id).getVisibility());
        }
    }

    /** Shown, each row has to occupy real space rather than measuring to nothing. */
    @Test
    public void theRowsHaveRealSizeOnceShown() {
        final int[][] size = new int[SECTION_VIEWS.length][2];

        getInstrumentation().runOnMainSync(() -> {
            final View screen = LayoutInflater.from(this.context)
                    .inflate(R.layout.activity_device_info, null);

            for (final int id : SECTION_VIEWS) {
                screen.findViewById(id).setVisibility(View.VISIBLE);
            }

            screen.measure(
                    View.MeasureSpec.makeMeasureSpec(SCREEN_WIDTH_PX, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
            screen.layout(0, 0, SCREEN_WIDTH_PX, 2400);

            for (int i = 0; i < SECTION_VIEWS.length; i++) {
                final View view = screen.findViewById(SECTION_VIEWS[i]);
                size[i][0] = view.getMeasuredWidth();
                size[i][1] = view.getMeasuredHeight();
            }
        });

        for (int i = 0; i < SECTION_VIEWS.length; i++) {
            assertTrue("view " + i + " measured " + size[i][0] + "x" + size[i][1],
                    size[i][0] > 0 && size[i][1] > 0);
        }
    }

    /**
     * <b>It sits outside the debug panel, which is the whole point.</b> Inside it, these readings
     * would be invisible to everyone who has not turned debug data on, and the section exists
     * because for somebody with no Apple device this is the only battery figure there is.
     */
    @Test
    public void theSectionIsNotInsideTheDebugPanel() {
        final View screen = this.inflateDeviceInfo();
        final ViewGroup debugPanel = screen.findViewById(R.id.device_debug_info);

        assertNotNull(debugPanel);
        for (final int id : SECTION_VIEWS) {
            assertTrue("what the radio heard must not be gated behind the debug switch",
                    debugPanel.findViewById(id) == null);
        }
    }

    /** Half of what breaks only breaks in one mode. */
    @Test
    public void theSectionSurvivesDarkMode() {
        final Configuration night = new Configuration(
                this.context.getResources().getConfiguration());
        night.uiMode = Configuration.UI_MODE_NIGHT_YES | Configuration.UI_MODE_TYPE_NORMAL;

        final Context darkContext = new ContextThemeWrapper(
                this.context.createConfigurationContext(night), R.style.Theme_OpenTagViewer);

        final View[] screen = new View[1];
        getInstrumentation().runOnMainSync(() -> screen[0] = LayoutInflater.from(darkContext)
                .inflate(R.layout.activity_device_info, null));

        for (final int id : SECTION_VIEWS) {
            final View view = screen[0].findViewById(id);
            assertNotNull(view);
            assertEquals(View.GONE, view.getVisibility());
        }
    }

    /**
     * The short battery words are what this section and the tag card show. The debug panel's own
     * strings spell out percentage ranges and a caveat, which is right there and far too long for
     * a one-line row - so this pins that they stayed short.
     */
    @Test
    public void theShortBatteryWordsStayShortEnoughForARow() {
        for (final int id : new int[] {
                R.string.battery_short_full,
                R.string.battery_short_medium,
                R.string.battery_short_low,
                R.string.battery_short_very_low,
        }) {
            final String word = this.context.getString(id);
            assertTrue("\"" + word + "\" is too long for a tag card line", word.length() <= 20);
        }
    }

    /** The card line reads e.g. "Nearby · Battery full", so the format has to take the word. */
    @Test
    public void theNearbyLineFormatsWithABatteryWord() {
        final String line = this.context.getString(R.string.nearby_now_with_battery,
                this.context.getString(R.string.battery_short_low));

        assertTrue("the battery word should appear in the line: " + line,
                line.contains(this.context.getString(R.string.battery_short_low)));
    }
}
