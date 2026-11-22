package dev.wander.android.opentagviewer.update;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * The update-check opt-in and what the last check found.
 *
 * <p>Its own preferences file, like {@code ExtraSourcesSettings}, rather than more fields on
 * {@code UserSettings}: nothing else reads these, and sign-out leaves them alone, which is
 * right - the choice is about this install, not the Apple account.
 */
public class UpdateSettings {
    static final String FILE = "app_updates";
    private static final String ENABLED = "enabled";
    private static final String LAST_CHECK_AT = "last_check_at";
    private static final String LATEST_VERSION = "latest_version";
    private static final String LATEST_URL = "latest_url";
    private static final String DISMISSED_VERSION = "dismissed_version";

    private final SharedPreferences prefs;

    public UpdateSettings(final Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** The user's answer: true, false, or null if they were never asked. Null means off. */
    @Nullable
    public Boolean enabled() {
        return this.prefs.contains(ENABLED) ? this.prefs.getBoolean(ENABLED, false) : null;
    }

    public void setEnabled(final boolean enabled) {
        this.prefs.edit().putBoolean(ENABLED, enabled).apply();
    }

    public long lastCheckAt() {
        return this.prefs.getLong(LAST_CHECK_AT, 0);
    }

    /** The newest release GitHub listed at the last check, or null before the first. */
    @Nullable
    public String latestVersion() {
        return this.prefs.getString(LATEST_VERSION, null);
    }

    @Nullable
    public String latestUrl() {
        return this.prefs.getString(LATEST_URL, null);
    }

    /** Record a check's answer. {@code newest} is null when GitHub listed no app release. */
    public void recordCheck(final long at, @Nullable final UpdateCheck.Release newest) {
        final SharedPreferences.Editor edit = this.prefs.edit().putLong(LAST_CHECK_AT, at);
        if (newest == null) {
            edit.remove(LATEST_VERSION).remove(LATEST_URL);
        } else {
            edit.putString(LATEST_VERSION, newest.getVersion())
                    .putString(LATEST_URL, newest.getPageUrl());
        }
        edit.apply();
    }

    @Nullable
    public String dismissedVersion() {
        return this.prefs.getString(DISMISSED_VERSION, null);
    }

    /** "Not now" on the map's offer: that version is not offered there again. */
    public void dismiss(final String version) {
        this.prefs.edit().putString(DISMISSED_VERSION, version).apply();
    }
}
