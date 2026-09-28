package dev.wander.android.opentagviewer.source;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Where the extra location source lives: a Google Find Hub service (googlefind) for Google
 * trackers.
 *
 * <p>SharedPreferences rather than the app's DataStore: these are read synchronously from the
 * fetch path, which runs on Rx worker threads and has no reason to wait on a Flowable.
 */
public final class ExtraSourcesSettings {
    public static final String GOOGLE_URL = "google_url";
    public static final String GOOGLE_TOKEN = "google_token";
    public static final String GOOGLE_USER = "google_user";
    public static final String GOOGLE_PASSWORD = "google_password";

    private final SharedPreferences prefs;

    public ExtraSourcesSettings(final Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences("extra_sources", Context.MODE_PRIVATE);
    }

    public String get(final String key) {
        return this.prefs.getString(key, "");
    }

    public void put(final String key, final String value) {
        this.prefs.edit().putString(key, value == null ? "" : value.trim()).apply();
    }

    public boolean isGoogleConfigured() {
        return !this.get(GOOGLE_URL).isEmpty();
    }
}
