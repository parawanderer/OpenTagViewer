package dev.wander.android.opentagviewer.python;

/**
 * Converts a beacon's stored plist XML into the serialized {@code FindMyAccessory} JSON
 * that FindMy.py 0.9.x expects.
 * <br>
 * This is an interface rather than a static call so the conversion can be substituted in
 * tests: the real implementation reaches into Chaquopy, which needs a started Python
 * runtime and cannot run on the JVM.
 */
public interface PlistToAccessoryJsonConverter {

    /**
     * @param plistXml the raw plist XML as stored in {@code OwnedBeacons.content}
     * @return the serialized accessory JSON, or {@code null} if conversion was not
     *         possible. Callers should treat {@code null} as "not available right now"
     *         and retry later, not as a permanent failure - a conversion can fail simply
     *         because the Python runtime has not started yet.
     */
    String convert(String plistXml);
}
