package dev.wander.android.opentagviewer.source;

import static dev.wander.android.opentagviewer.AppKeyStoreConstants.KEYSTORE_ALIAS_EXTRA_SOURCES;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Where the extra location source lives: a Google Find Hub service (googlefind) for Google
 * trackers.
 *
 * <p>SharedPreferences rather than the app's DataStore: these are read synchronously from the
 * fetch path, which runs on Rx worker threads and has no reason to wait on a Flowable.
 *
 * <p>The API token and the HTTP password are encrypted with a keystore key, like the Apple
 * session: the token reads every location the service holds.
 */
public final class ExtraSourcesSettings {
    private static final String TAG = ExtraSourcesSettings.class.getSimpleName();

    public static final String GOOGLE_URL = "google_url";
    public static final String GOOGLE_TOKEN = "google_token";
    public static final String GOOGLE_USER = "google_user";
    public static final String GOOGLE_PASSWORD = "google_password";

    private static final Set<String> SECRETS = Set.of(GOOGLE_TOKEN, GOOGLE_PASSWORD);
    private static final String ENCRYPTED_SUFFIX = "_encrypted";

    private final SharedPreferences prefs;
    private final AppCryptographyUtil crypto = new AppCryptographyUtil();

    public ExtraSourcesSettings(final Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences("extra_sources", Context.MODE_PRIVATE);
    }

    public String get(final String key) {
        if (!SECRETS.contains(key)) {
            return this.prefs.getString(key, "");
        }
        final String plain = this.prefs.getString(key, null);
        if (plain != null) {
            // Written in plain text by a build before encryption: move it over
            this.put(key, plain);
            return plain.trim();
        }
        final String stored = this.prefs.getString(key + ENCRYPTED_SUFFIX, null);
        if (stored == null) {
            return "";
        }
        try {
            final byte[] decrypted = this.crypto.decrypt(AppCryptographyUtil.AppEncryptedData.fromFlattened(
                    Base64.decode(stored, Base64.NO_WRAP)), KEYSTORE_ALIAS_EXTRA_SOURCES);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (final RuntimeException e) {
            Log.w(TAG, "Could not decrypt the stored " + key + "; it has to be entered again", e);
            return "";
        }
    }

    public void put(final String key, final String value) {
        final String trimmed = value == null ? "" : value.trim();
        if (!SECRETS.contains(key)) {
            this.prefs.edit().putString(key, trimmed).apply();
            return;
        }
        final SharedPreferences.Editor edit = this.prefs.edit().remove(key);
        if (trimmed.isEmpty()) {
            edit.remove(key + ENCRYPTED_SUFFIX);
        } else {
            final byte[] flattened = this.crypto.encrypt(
                    trimmed.getBytes(StandardCharsets.UTF_8), KEYSTORE_ALIAS_EXTRA_SOURCES).flatten();
            edit.putString(key + ENCRYPTED_SUFFIX, Base64.encodeToString(flattened, Base64.NO_WRAP));
        }
        edit.apply();
    }

    public boolean isGoogleConfigured() {
        return !this.get(GOOGLE_URL).isEmpty();
    }
}
