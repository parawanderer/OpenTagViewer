package dev.wander.android.opentagviewer.onboarding;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Whether this install has been through the steps after sign-in.
 *
 * <p><b>Once per install, not per sign-in.</b> An expired session sends people back through
 * sign-in, and replaying "get your tags" and the permission explanations at them then is a
 * prompt they learn to tap through. Sign-out does not clear this file.
 *
 * <p>Installs that were signed in before onboarding existed never sign in again, so they never
 * see it: the map's iCloud offer stays for them, and the update switch is in Settings.
 */
public class OnboardingRecord {
    static final String FILE = "onboarding";
    private static final String DONE = "done";

    private final SharedPreferences prefs;

    public OnboardingRecord(final Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean done() {
        return this.prefs.getBoolean(DONE, false);
    }

    /**
     * Marked when the steps open, not when they finish: somebody who backs out half way has
     * still been shown them, and must not get them again at the next sign-in.
     */
    public void markShown() {
        this.prefs.edit().putBoolean(DONE, true).apply();
    }

    /** For tests. */
    public void forget() {
        this.prefs.edit().remove(DONE).apply();
    }
}
