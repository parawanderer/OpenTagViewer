package dev.wander.android.opentagviewer;

import android.Manifest;

import androidx.test.runner.AndroidJUnitRunner;

import java.util.ArrayList;
import java.util.List;

import dev.wander.android.opentagviewer.ble.BlePermissions;

/**
 * Grants the runtime permissions the app asks for on startup, before any test runs.
 *
 * <p><b>An ungranted permission does not fail a test here - it hangs the suite.</b> The screens
 * ask for what they need the moment they open, and a system permission dialog belongs to the
 * permission controller rather than to this app: it takes focus, pauses the activity, and leaves
 * Espresso with nothing resumed to look at. Its root picker then retries on a thirty-second
 * backoff, and since {@code timeout_msec} is deliberately not set (it cost about three minutes a
 * run), nothing ever stops it. The test does not fail; it never ends, and whatever limit is
 * outermost - 45 minutes on CI - is what finally kills the job.
 *
 * <p><b>Per-class rules could not keep up, which is the actual reason this exists.</b> The map
 * fixture granted location up front and said in its own javadoc that a test which forgot the
 * rule would hit the same wall. Then the nearby-tags work made the map ask for Bluetooth as it
 * opens, and eight classes reach the map without that fixture - one of them with a
 * {@code GrantPermissionRule} listing exactly the permissions that used to be enough. Nothing
 * was wrong with any of them. They were written before the app asked for one more thing, and a
 * rule is per-class, so there is no single place that a new permission can be added.
 *
 * <p>This is that place. Granting before the first test costs nothing and removes the whole
 * class of failure.
 *
 * <p><b>Safe because nothing here tests a refusal.</b> No test in this source set asserts that a
 * permission is requested, rationalised or denied - checked before writing this - so there is no
 * behaviour for a blanket grant to hide. If one is ever added, it needs to revoke what it is
 * about in its own setup, and this comment is the warning that it must.
 */
public final class GrantWhatTheAppAsksForRunner extends AndroidJUnitRunner {

    @Override
    public void onStart() {
        final List<String> needed = new ArrayList<>(List.of(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION));

        // Read from the app rather than named again: which permissions Bluetooth needs depends
        // on the API level, and a second copy is one that can be wrong without anybody noticing.
        needed.addAll(List.of(BlePermissions.required()));

        final String packageName = this.getTargetContext().getPackageName();
        for (final String permission : needed) {
            try {
                this.getUiAutomation().grantRuntimePermission(packageName, permission);
            } catch (final RuntimeException alreadyHeldOrNotGrantable) {
                // Already granted, or not a runtime permission on this API level. Both are fine:
                // the point is only that no dialog appears once the tests start.
            }
        }

        super.onStart();
    }
}
