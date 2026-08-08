package dev.wander.android.opentagviewer.python;

import android.util.Log;

import com.chaquo.python.Python;

/**
 * The real converter: calls {@code main.py:convertPlistToJson}, which runs
 * {@code FindMyAccessory.from_plist(...).to_json()}.
 * <br>
 * The Python runtime is resolved lazily per call rather than held as a field, so
 * constructing this does not require Chaquopy to have started.
 */
public class ChaquopyPlistToAccessoryJsonConverter implements PlistToAccessoryJsonConverter {
    private static final String TAG = ChaquopyPlistToAccessoryJsonConverter.class.getSimpleName();
    private static final String MODULE_MAIN = "main";

    @Override
    public String convert(final String plistXml) {
        if (plistXml == null || plistXml.isEmpty()) {
            return null;
        }

        try {
            var module = Python.getInstance().getModule(MODULE_MAIN);
            var converted = module.callAttr("convertPlistToJson", plistXml);
            return converted == null ? null : converted.toString();
        } catch (Exception e) {
            // Either Python has not started yet, or the plist is not one FindMy 0.9.x can
            // parse. Neither should take down the whole fetch, so report and move on.
            Log.w(TAG, "convertPlistToJson failed", e);
            return null;
        }
    }
}
