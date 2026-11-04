package dev.wander.android.opentagviewer.anisette;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Which Anisette established the signed-in session, and so which one a restore must keep using.
 *
 * <p><b>One record, in the file {@link LocalAnisette} has always written it to.</b> Every install
 * signed in before the emulated Mac existed has {@code sessionEstablishedLocally} in
 * {@link LocalAnisette#PREFERENCES} and nothing else, and that has to go on meaning what it meant:
 * "this session belongs to the Android machine {@link LocalAnisette} provisioned" (or to a remote
 * server, when false). The emulated Mac's sessions add one key beside it, {@link #KEY_MACHINE}.
 * Its absence is meaningful in the same way {@link LocalAnisette#KEY_HARDWARE}'s is: a session
 * from before there was a choice.
 *
 * <p><b>Why a restore cares.</b> Per #248 a Find My token only works alongside Anisette from the
 * machine that obtained it. A session the Android machine signed in must stay on the Android
 * machine, and must not have a new Mac provisioned underneath it at app start; a session the Mac
 * signed in must stay on the Mac. See {@link AnisetteForSession}.
 *
 * <p>Plain Java behind {@link Store}, so the decision is tested on the JVM (AGENTS.md rule 13).
 */
public final class SessionProvenance {
    private static final String TAG = "SessionProvenance";

    /** {@link LocalAnisette}'s key, unchanged since before the emulated Mac. */
    static final String KEY_ESTABLISHED_LOCALLY = "sessionEstablishedLocally";

    /**
     * Which local machine the session was signed in as: {@link #EMULATED_MAC}, or absent for
     * {@link LocalAnisette}'s Android machine and for every session from before this key.
     */
    static final String KEY_MACHINE = "sessionMachine";

    /** {@link #KEY_MACHINE} for a sign-in made through {@link EmulatedMacAnisette}. */
    static final String EMULATED_MAC = "emulatedMac";

    /** Where the record lives. {@code commit()}, not {@code apply()}: the next launch reads it. */
    interface Store {
        boolean getBoolean(String key);

        String getString(String key);

        /** Write both, durably, in one edit. A null machine removes the key. */
        void put(boolean establishedLocally, String machine);
    }

    private final Store store;

    SessionProvenance(final Store store) {
        this.store = store;
    }

    /** The record in {@link LocalAnisette#PREFERENCES}, where it has always been. */
    public static SessionProvenance of(final Context context) {
        final SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(LocalAnisette.PREFERENCES, Context.MODE_PRIVATE);
        return new SessionProvenance(new Store() {
            @Override
            public boolean getBoolean(final String key) {
                return preferences.getBoolean(key, false);
            }

            @Override
            public String getString(final String key) {
                return preferences.getString(key, null);
            }

            @Override
            public void put(final boolean establishedLocally, final String machine) {
                final SharedPreferences.Editor edit = preferences.edit()
                        .putBoolean(KEY_ESTABLISHED_LOCALLY, establishedLocally);
                if (machine == null) {
                    edit.remove(KEY_MACHINE);
                } else {
                    edit.putString(KEY_MACHINE, machine);
                }
                if (!edit.commit()) {
                    Log.w(TAG, "could not record which Anisette established this session");
                }
            }
        });
    }

    /**
     * A sign-in made through {@link LocalAnisette}: locally on its Android machine, or through a
     * server when that was unavailable. Clears {@link #KEY_MACHINE}, so the record cannot claim
     * a Mac for a session the Mac never saw.
     */
    void recordAndroidSession(final boolean establishedLocally) {
        this.store.put(establishedLocally, null);
    }

    /**
     * A sign-in made through {@link EmulatedMacAnisette}. Recorded as the Mac's whether or not it
     * fell back to a server, because it presented the Mac's device ids and hardware either way.
     */
    void recordEmulatedMacSession(final boolean establishedLocally) {
        this.store.put(establishedLocally, EMULATED_MAC);
    }

    /** Whether local Anisette, of either kind, produced the session. */
    public boolean establishedLocally() {
        return this.store.getBoolean(KEY_ESTABLISHED_LOCALLY);
    }

    /** Whether the session was signed in through the emulated Mac. False for every older one. */
    public boolean signedInAsEmulatedMac() {
        return EMULATED_MAC.equals(this.store.getString(KEY_MACHINE));
    }
}
