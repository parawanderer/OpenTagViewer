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
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.R;

/**
 * The live battery row on the device screen: the one fed by hearing the tag itself over
 * Bluetooth, rather than by the iCloud record.
 *
 * <p><b>Hidden is its resting state, and that is the part worth pinning.</b> It is only filled
 * in once the tag has actually been heard, and it deliberately never falls back to the iCloud
 * value - the whole reason it sits outside the debug panel is that it cannot be stale. A stray
 * edit making it visible by default would put an empty row on every device screen; one wiring it
 * to {@code batteryLevel} would silently reintroduce the staleness it exists to avoid.
 *
 * <p>Inflation only: no activity, no account, no Bluetooth. Run with
 * {@code ./gradlew :app:testEmulatorDebugAndroidTest}.
 */
@RunWith(AndroidJUnit4.class)
public class DeviceInfoLiveBatteryLayoutTest {

    private static final int SCREEN_WIDTH_PX = 1080;

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

    /** The id {@code DeviceInfoActivity.showLiveBattery} looks up has to resolve, or the row is
     * simply never shown and nothing fails. */
    @Test
    public void theLiveBatteryRowExists() {
        assertNotNull(this.inflateDeviceInfo().findViewById(R.id.device_settings_live_battery));
    }

    @Test
    public void theLiveBatteryRowIsHiddenUntilTheTagIsHeard() {
        final View row = this.inflateDeviceInfo().findViewById(R.id.device_settings_live_battery);
        assertEquals("an unheard tag must show no battery row at all, not an empty one",
                View.GONE, row.getVisibility());
    }

    /** Shown, it has to occupy real space rather than measuring to nothing. */
    @Test
    public void theLiveBatteryRowHasRealSizeOnceShown() {
        final int[] size = new int[2];

        getInstrumentation().runOnMainSync(() -> {
            final View screen = LayoutInflater.from(this.context)
                    .inflate(R.layout.activity_device_info, null);
            final View row = screen.findViewById(R.id.device_settings_live_battery);
            row.setVisibility(View.VISIBLE);

            screen.measure(
                    View.MeasureSpec.makeMeasureSpec(SCREEN_WIDTH_PX, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
            screen.layout(0, 0, SCREEN_WIDTH_PX, 2400);

            size[0] = row.getMeasuredWidth();
            size[1] = row.getMeasuredHeight();
        });

        assertTrue("row measured " + size[0] + "x" + size[1], size[0] > 0 && size[1] > 0);
    }

    /**
     * <b>It sits outside the debug panel, which is the whole point.</b> Inside it, the reading
     * would be invisible to everyone who has not turned debug data on, and this row exists
     * because a live reading is worth showing to everybody.
     */
    @Test
    public void theLiveBatteryRowIsNotInsideTheDebugPanel() {
        final View screen = this.inflateDeviceInfo();
        final View debugPanel = screen.findViewById(R.id.device_debug_info);
        final View row = screen.findViewById(R.id.device_settings_live_battery);

        assertNotNull(debugPanel);
        assertTrue("the live reading must not be gated behind the debug switch",
                ((ViewGroup) debugPanel).findViewById(R.id.device_settings_live_battery) == null);
        assertNotNull(row);
    }

    /** Half of what breaks only breaks in one mode. */
    @Test
    public void theRowSurvivesDarkMode() {
        final Configuration night = new Configuration(
                this.context.getResources().getConfiguration());
        night.uiMode = Configuration.UI_MODE_NIGHT_YES | Configuration.UI_MODE_TYPE_NORMAL;

        final Context darkContext = new ContextThemeWrapper(
                this.context.createConfigurationContext(night), R.style.Theme_OpenTagViewer);

        final View[] row = new View[1];
        getInstrumentation().runOnMainSync(() -> row[0] = LayoutInflater.from(darkContext)
                .inflate(R.layout.activity_device_info, null)
                .findViewById(R.id.device_settings_live_battery));

        assertNotNull(row[0]);
        assertEquals(View.GONE, row[0].getVisibility());
    }

    /**
     * The short battery words are what the row and the tag card show. The debug panel's own
     * strings spell out percentage ranges and a caveat, which is right there and far too long
     * for a one-line row - so this pins that they stayed short.
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
