package dev.wander.android.opentagviewer.source;

import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The accessory JSON of the two kinds of device the extra sources add.
 *
 * <ul>
 *   <li>OpenHaystack keys are FindMy.py's own {@code custom_rolling_key_accessory}, so everything
 *   that already works for self-generated tags (BLE matching, play sound, history) works for them
 *   unchanged. It takes every key of a tag that rotates through several (Everytag does), and
 *   searches all of them on each fetch.</li>
 *   <li>A Google Find Hub tracker is {@code google_find_hub}: it has no Apple keys at all, and
 *   the Apple paths must never be handed one (FindMy.py raises on an unknown type).</li>
 * </ul>
 */
public final class ExternalAccessory {
    public static final String TYPE_OPENHAYSTACK = "custom_rolling_key_accessory";
    public static final String TYPE_GOOGLE = "google_find_hub";

    private ExternalAccessory() {
    }

    @Nullable
    private static JSONObject parse(@Nullable final String accessoryJson) {
        if (accessoryJson == null) return null;
        try {
            return new JSONObject(accessoryJson);
        } catch (final JSONException e) {
            return null;
        }
    }

    public static boolean isGoogle(@Nullable final String accessoryJson) {
        final JSONObject o = parse(accessoryJson);
        return o != null && TYPE_GOOGLE.equals(o.optString("type"));
    }

    public static boolean isOpenHaystack(@Nullable final String accessoryJson) {
        final JSONObject o = parse(accessoryJson);
        return o != null && TYPE_OPENHAYSTACK.equals(o.optString("type"));
    }

    /** The canonic id of a Google tracker. */
    @Nullable
    public static String googleId(@Nullable final String accessoryJson) {
        final JSONObject o = parse(accessoryJson);
        return o == null ? null : o.optString("google_id", null);
    }

    /**
     * Reads the private keys out of a Macless-Haystack / OpenHaystack {@code .keys} file
     * ("Private key: &lt;base64&gt;", once per key), as hex, in file order and without repeats.
     *
     * @throws IllegalArgumentException when there is none, or one is not a 28-byte key
     */
    public static List<String> privateKeysFromKeysFile(final String text) {
        return privateKeysFromKeysFile(text, b64 -> Base64.decode(b64, Base64.DEFAULT));
    }

    /** As above, with the Base64 decoder passed in: {@code android.util.Base64} is a stub on the JVM. */
    static List<String> privateKeysFromKeysFile(final String text, final Function<String, byte[]> base64) {
        final List<String> keys = new ArrayList<>();
        for (final String line : text.split("\n")) {
            final int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Private key")) {
                final byte[] key = base64.apply(line.substring(colon + 1).trim());
                if (key.length != 28) throw new IllegalArgumentException("A private key is not 28 bytes long");
                final String hex = toHex(key);
                if (!keys.contains(hex)) keys.add(hex);
            }
        }
        if (keys.isEmpty()) throw new IllegalArgumentException("No \"Private key\" line in this file");
        return keys;
    }

    static String toHex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    public static String newOpenHaystack(final String id, final String name, final List<String> privateKeysHex) {
        try {
            return new JSONObject()
                    .put("type", TYPE_OPENHAYSTACK)
                    .put("identifier", id)
                    .put("name", name)
                    .put("private_keys", new JSONArray(privateKeysHex))
                    .toString();
        } catch (final JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String newGoogle(final String googleId, final String name) {
        try {
            return new JSONObject()
                    .put("type", TYPE_GOOGLE)
                    .put("identifier", "google-" + googleId)
                    .put("name", name)
                    .put("google_id", googleId)
                    .toString();
        } catch (final JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
