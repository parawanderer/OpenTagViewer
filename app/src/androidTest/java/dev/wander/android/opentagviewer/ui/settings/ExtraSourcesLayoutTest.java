package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.databinding.ActivityExtraSourcesBinding;

/**
 * The Google Find Hub settings screen, inflated in the theme its activity uses, in both modes.
 *
 * <p>Every view the activity reads has to resolve, and the screen has to measure to something:
 * a missing attribute or a renamed id fails only at runtime, on a screen few people open.
 */
@RunWith(AndroidJUnit4.class)
public class ExtraSourcesLayoutTest {

    private static final int[] VIEWS_THE_ACTIVITY_READS = {
            R.id.extra_sources_google_url, R.id.extra_sources_google_url_layout,
            R.id.extra_sources_google_token, R.id.extra_sources_google_user,
            R.id.extra_sources_google_password, R.id.extra_sources_test,
            R.id.extra_sources_test_progress, R.id.extra_sources_test_result,
    };

    @Test
    public void itInflatesInLightMode() {
        this.assertItInflates(Configuration.UI_MODE_NIGHT_NO);
    }

    /** Half of what breaks only breaks in one mode. */
    @Test
    public void itInflatesInDarkMode() {
        this.assertItInflates(Configuration.UI_MODE_NIGHT_YES);
    }

    private void assertItInflates(final int nightMode) {
        final Context target = getInstrumentation().getTargetContext();
        final Configuration config = new Configuration(target.getResources().getConfiguration());
        config.uiMode = nightMode | Configuration.UI_MODE_TYPE_NORMAL;
        final Context themed = new ContextThemeWrapper(target.createConfigurationContext(config),
                R.style.Theme_OpenTagViewer_GenericGreyishActivity);

        final View[] root = new View[1];
        // On the main thread: Material text fields start animators, which need a Looper.
        getInstrumentation().runOnMainSync(() -> {
            root[0] = ActivityExtraSourcesBinding.inflate(LayoutInflater.from(themed)).getRoot();
            root[0].measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        });

        for (final int id : VIEWS_THE_ACTIVITY_READS) {
            assertNotNull("a view the activity reads is missing: "
                    + target.getResources().getResourceEntryName(id), root[0].findViewById(id));
        }
        assertTrue("the screen measured to nothing", root[0].getMeasuredHeight() > 0);
    }
}
