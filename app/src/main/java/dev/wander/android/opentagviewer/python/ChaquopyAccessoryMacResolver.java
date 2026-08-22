package dev.wander.android.opentagviewer.python;

import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The real resolver: calls {@code main.py:currentMacAddresses}, which delegates to
 * {@code RollingKeyPairSource.current_mac_addresses} in FindMy.py.
 *
 * <p>The Python runtime is resolved lazily per call rather than held as a field, so constructing
 * this does not require Chaquopy to have started - same reasoning as
 * {@link ChaquopyHardwareDescriber}.
 *
 * <p><b>Blocking, and starts an interpreter.</b> Never call this on the main thread; the {@code
 * ble} package that uses it does so on an Rx scheduler.
 */
public class ChaquopyAccessoryMacResolver implements AccessoryMacResolver {
    private static final String TAG = ChaquopyAccessoryMacResolver.class.getSimpleName();
    private static final String MODULE_MAIN = "main";

    @Override
    public List<String> currentMacAddresses(final String accessoryJson) {
        if (accessoryJson == null || accessoryJson.isEmpty()) {
            // Not yet backfilled from the legacy plist - see OwnedBeacon.accessoryJson. A real
            // state, not a failure, so this reports it the same way Python does: nothing found.
            return Collections.emptyList();
        }

        try {
            final var module = Python.getInstance().getModule(MODULE_MAIN);
            final PyObject returned = module.callAttr("currentMacAddresses", accessoryJson);

            if (returned == null) {
                Log.w(TAG, "currentMacAddresses returned None (check python logs for details)");
                return Collections.emptyList();
            }

            final List<String> macs = new ArrayList<>();
            for (final PyObject mac : returned.asList()) {
                macs.add(mac.toString());
            }
            return macs;
        } catch (final Exception e) {
            // Either Python has not started, or the accessory JSON could not be read. Neither
            // is worth failing the caller over: it reads as "nothing to match against yet".
            Log.w(TAG, "currentMacAddresses failed", e);
            return Collections.emptyList();
        }
    }
}
