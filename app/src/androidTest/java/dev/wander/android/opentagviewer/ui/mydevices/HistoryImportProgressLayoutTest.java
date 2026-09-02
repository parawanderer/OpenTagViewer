package dev.wander.android.opentagviewer.ui.mydevices;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.R;

/** Cheap inflation coverage for the custom view placed inside the import dialog. */
@RunWith(AndroidJUnit4.class)
public class HistoryImportProgressLayoutTest {

    @Test
    public void itInflatesAndMeasuresInBothThemes() {
        for (final boolean night : new boolean[]{false, true}) {
            final View root = inflate(night);
            final CircularProgressIndicator indicator =
                    root.findViewById(R.id.history_import_progress);

            assertNotNull(indicator);
            assertTrue(indicator.isIndeterminate());

            root.measure(
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

            assertTrue("the progress dialog content measured to nothing",
                    root.getMeasuredHeight() > 0);
            assertTrue("the progress indicator measured to nothing",
                    indicator.getMeasuredWidth() > 0 && indicator.getMeasuredHeight() > 0);
        }
    }

    private static View inflate(final boolean night) {
        final Context base = getInstrumentation().getTargetContext();
        final Configuration configuration = new Configuration(
                base.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        final Context themed = new ContextThemeWrapper(
                base.createConfigurationContext(configuration), R.style.Theme_OpenTagViewer);
        return LayoutInflater.from(themed).inflate(R.layout.history_import_progress, null);
    }
}
