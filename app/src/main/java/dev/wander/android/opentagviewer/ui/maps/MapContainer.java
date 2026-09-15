package dev.wander.android.opentagviewer.ui.maps;

import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

/**
 * Hands the map container to whichever provider is about to fill it, empty.
 *
 * <p><b>Changing the map provider did nothing until the app was restarted, and this is why.</b>
 * The providers claim {@code R.id.map} in two different ways: Google puts a
 * {@code SupportMapFragment} in it, while osmdroid and AMap call {@code addView} with a plain
 * view. Neither clears what was there first, because on a cold start there is never anything to
 * clear.
 *
 * <p>Switching provider calls {@code recreate()}, and that is where the asymmetry bites: views
 * are rebuilt from the layout and so the container comes back empty, but <b>fragments are
 * restored by the FragmentManager</b>. So a switch away from Google restored Google's map
 * fragment into the container and then added the new provider's view beside it - the old map
 * still drawn, the new one behind or in front of it, and nothing that looked like a change. Only
 * a full restart, which drops the saved fragment state, appeared to apply the setting.
 *
 * <p>It is one-directional, which is what made it look arbitrary: switching <em>to</em> Google
 * worked, because the plain view the previous provider added is not restored.
 *
 * <p>Deliberately not a branch on which provider is leaving or arriving - see rule 7. Emptying
 * the container is the same job whoever asked, and a provider added later gets it without
 * touching this.
 */
public final class MapContainer {

    private MapContainer() {
    }

    /**
     * Remove whatever is occupying the container, as a fragment or as a view.
     *
     * <p>Safe to call when there is nothing there, which is every cold start.
     *
     * @param activity      the activity holding the container
     * @param containerId   the container's view id
     */
    public static void clear(final FragmentActivity activity, final int containerId) {
        if (activity == null) {
            return;
        }

        final FragmentManager fragments = activity.getSupportFragmentManager();
        final Fragment occupying = fragments.findFragmentById(containerId);
        if (occupying != null) {
            // commitNow so the container is genuinely empty by the time the next provider looks
            // at it, rather than at some later frame; allowingStateLoss because this runs from
            // onCreate during a recreate() and there is no state here worth preserving - the
            // provider about to be built replaces all of it.
            fragments.beginTransaction().remove(occupying).commitNowAllowingStateLoss();
        }

        final ViewGroup container = activity.findViewById(containerId);
        if (container != null) {
            // The fragment's own view goes with the fragment above; this is for the providers
            // that added a plain view, and for the fragment's leftover host view if any.
            container.removeAllViews();
        }
    }
}
