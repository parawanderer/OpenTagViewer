package dev.wander.android.opentagviewer;

import android.content.Context;
import android.util.Log;

import java.util.Optional;

import dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserAuthRepository;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.AppleUserData;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

/**
 * Puts back whatever was on the device before a test ran.
 *
 * <p>These tests need a known starting point - signed out, no server chosen - and the only way
 * to get one is to write over what is there. On the managed device that costs nothing, because
 * it is destroyed afterwards. On a device somebody uses it is destructive in a way that is not
 * obvious from reading the test: it signs them out, forgets their language, theme and map
 * provider, and discards an AMap API key they had to register for by hand.
 *
 * <p>It surfaced exactly that way - a run against a working emulator left it signed in with no
 * Anisette mode chosen and the upgrade prompt re-armed, which looks like an app bug and is not.
 *
 * <p>So: capture first, restore last, and leave the device as it was found.
 */
public final class DeviceStateGuard {
    private static final String TAG = "DeviceStateGuard";

    private final Context context;
    private final UserSettings settings;
    private final byte[] session;

    private DeviceStateGuard(Context context, UserSettings settings, byte[] session) {
        this.context = context;
        this.settings = settings;
        this.session = session;
    }

    /** Take a snapshot. Call this <b>before</b> anything is overwritten. */
    public static DeviceStateGuard capture(final Context context) {
        final UserSettings settings = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(context)).getUserSettings();

        byte[] session = null;
        try {
            final Optional<AppleUserData> stored = authRepository(context)
                    .getUserAuth().blockingFirst();
            if (stored.isPresent()) {
                // Decrypted, because putting it back goes through the encrypting setter.
                session = authRepository(context).decrypt(stored.get().getData());
            }
        } catch (final Exception e) {
            // A session that cannot be read cannot be restored either, and that is survivable
            // - it is the settings, which somebody configured by hand, that matter most here.
            Log.w(TAG, "could not capture the stored session", e);
        }

        return new DeviceStateGuard(context, settings, session);
    }

    /** Put it all back. Safe to call more than once. */
    public void restore() {
        try {
            new UserSettingsRepository(UserSettingsDataStore.getInstance(context))
                    .storeUserSettings(this.settings)
                    .blockingAwait();

            if (this.session != null) {
                authRepository(context).storeUserAuth(this.session).blockingAwait();
            } else {
                authRepository(context).clearUser().blockingAwait();
            }
        } catch (final Exception e) {
            Log.e(TAG, "could not restore the device state - it may be left as the test left it",
                    e);
        }
    }

    private static UserAuthRepository authRepository(final Context context) {
        return new UserAuthRepository(
                UserAuthDataStore.getInstance(context), new AppCryptographyUtil());
    }
}
