package dev.wander.android.opentagviewer.ui.mydevices;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.ui.TestHostActivity;

/**
 * Inflation coverage for the view inside the history-import dialog.
 *
 * <p><b>Through an Activity's own inflater, because that is what the app uses.</b> The first
 * version built a themed context by hand and called {@code LayoutInflater.from(...)} on it - the
 * pattern {@code SystemColorsLayoutTest} uses - and threw {@code InflateException} on the
 * {@code CircularProgressIndicator}, while the dialog worked perfectly well on a device. Nothing
 * in {@code SystemColorsLayoutTest} inflates a Material progress indicator, so that pattern had
 * never been asked to; an {@code AppCompatActivity} installs an inflater factory that a bare
 * {@code ContextThemeWrapper} does not.
 *
 * <p>Rule 12 makes the same point about drawables: load them <i>the way the app loads them</i>. A
 * test that inflates by a route the app never takes can fail for reasons the app will never meet,
 * and can pass while the real path is broken.
 *
 * <p>{@code cloneInContext} is what allows both themes without touching global state: it keeps the
 * Activity's factory and swaps only the configuration, so the dark render is a second inflation
 * rather than a second Activity.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryImportProgressLayoutTest {

    @Test
    public void itInflatesAndMeasuresInBothThemes() {
        try (ActivityScenario<TestHostActivity> scenario =
                     ActivityScenario.launch(TestHostActivity.class)) {
            scenario.onActivity(activity -> {
                checkItInflates(activity.getLayoutInflater());
                checkItInflates(activity.getLayoutInflater().cloneInContext(inNightMode(activity)));
            });
        }
    }

    /** The same layout, the same assertions, whichever configuration the inflater carries. */
    private static void checkItInflates(final LayoutInflater inflater) {
        final View root = inflater.inflate(R.layout.history_import_progress, null);
        final CircularProgressIndicator indicator =
                root.findViewById(R.id.history_import_progress);

        assertNotNull("the id the activity looks up has to resolve", indicator);
        assertTrue("it is indeterminate until the merge phase gives it a total",
                indicator.isIndeterminate());

        root.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

        assertTrue("the dialog content measured to nothing", root.getMeasuredHeight() > 0);
        assertTrue("the progress indicator measured to nothing",
                indicator.getMeasuredWidth() > 0 && indicator.getMeasuredHeight() > 0);
    }

    /** The app's theme, in a configuration that says night, without changing the device. */
    private static Context inNightMode(final Context base) {
        final Configuration night = new Configuration(base.getResources().getConfiguration());
        night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | Configuration.UI_MODE_NIGHT_YES;

        return new ContextThemeWrapper(
                base.createConfigurationContext(night), R.style.Theme_OpenTagViewer);
    }
}
