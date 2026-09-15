package dev.wander.android.opentagviewer.ui.maps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.ui.TestHostActivity;

/**
 * Emptying the map container, which is what makes changing map provider take effect.
 *
 * <p><b>The bug this exists for looked like the setting being ignored.</b> Switching provider
 * calls {@code recreate()}; a plain view added by osmdroid or AMap is rebuilt from the layout and
 * so the container comes back empty, but a {@code SupportMapFragment} is <em>restored</em> by the
 * FragmentManager. Switching away from Google therefore left Google's map in the container and
 * added the new provider's view beside it, and only a full restart appeared to apply the change.
 *
 * <p>The asymmetry is the point, and it is why both directions are driven here: the failing
 * direction passes trivially if you only ever test the other one.
 */
@RunWith(AndroidJUnit4.class)
public class MapContainerTest {

    private static final int CONTAINER_ID = View.generateViewId();

    /** A fragment standing in for {@code SupportMapFragment}: restored the same way, no Maps. */
    public static class AFragmentInTheContainer extends Fragment {
        @Override
        public View onCreateView(android.view.LayoutInflater inflater, android.view.ViewGroup c,
                                 android.os.Bundle state) {
            return new TextView(this.requireContext());
        }
    }

    /**
     * A fragment left in the container is gone afterwards.
     *
     * <p>The case that was broken. Nothing here asserts on how it was removed - only that the
     * next provider is handed an empty container.
     */
    @Test
    public void aFragmentIsRemoved() {
        try (ActivityScenario<TestHostActivity> scenario =
                     ActivityScenario.launch(TestHostActivity.class)) {

            scenario.onActivity(activity -> {
                final FrameLayout container = containerIn(activity);

                activity.getSupportFragmentManager().beginTransaction()
                        .replace(CONTAINER_ID, new AFragmentInTheContainer())
                        .commitNow();

                assertEquals("the fragment should be in place before the clear",
                        1, container.getChildCount());

                MapContainer.clear(activity, CONTAINER_ID);

                assertNull("a restored map fragment must not survive into the next provider",
                        activity.getSupportFragmentManager().findFragmentById(CONTAINER_ID));
                assertEquals("the container must be empty", 0, container.getChildCount());
            });
        }
    }

    /** And so is a plain view, which is how osmdroid and AMap attach. */
    @Test
    public void aPlainViewIsRemoved() {
        try (ActivityScenario<TestHostActivity> scenario =
                     ActivityScenario.launch(TestHostActivity.class)) {

            scenario.onActivity(activity -> {
                final FrameLayout container = containerIn(activity);
                container.addView(new TextView(activity));

                MapContainer.clear(activity, CONTAINER_ID);

                assertEquals("a view the previous provider added must not be left behind",
                        0, container.getChildCount());
            });
        }
    }

    /**
     * An empty container is left alone rather than thrown at.
     *
     * <p>Every cold start calls this with nothing to clear, so the no-op case is the common one.
     */
    @Test
    public void anEmptyContainerIsFine() {
        try (ActivityScenario<TestHostActivity> scenario =
                     ActivityScenario.launch(TestHostActivity.class)) {

            scenario.onActivity(activity -> {
                final FrameLayout container = containerIn(activity);

                MapContainer.clear(activity, CONTAINER_ID);

                assertEquals(0, container.getChildCount());
            });
        }
    }

    /** A container id that is not in this activity at all must not throw either. */
    @Test
    public void anUnknownContainerIsFine() {
        try (ActivityScenario<TestHostActivity> scenario =
                     ActivityScenario.launch(TestHostActivity.class)) {
            scenario.onActivity(activity -> MapContainer.clear(activity, View.generateViewId()));
        }
    }

    private static FrameLayout containerIn(final TestHostActivity activity) {
        FrameLayout container = activity.findViewById(CONTAINER_ID);
        if (container == null) {
            container = new FrameLayout(activity);
            container.setId(CONTAINER_ID);
            activity.setContentView(container);
        }
        return container;
    }
}
