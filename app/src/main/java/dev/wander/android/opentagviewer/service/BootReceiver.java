package dev.wander.android.opentagviewer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Brings the background scan back after the phone restarts.
 *
 * <p><b>Without this the setting quietly stops meaning anything.</b> A service does not survive
 * a reboot, and nothing else starts this app on its own - so somebody who turned background
 * scanning on would get it until their next restart, and then silence until they happened to
 * open the app again. That is the failure mode this whole feature exists to avoid: it is meant
 * to be listening precisely when nobody is looking at the app.
 *
 * <p><b>Starting a foreground service from here is allowed</b>, which is not true of most
 * background starts on Android 12 and later: {@code BOOT_COMPLETED} is one of the named
 * exemptions.
 *
 * <p>Reads the setting first and starts nothing for anybody who has not asked. The setting is
 * the state; the service is only its consequence.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = BootReceiver.class.getSimpleName();

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        final Context appContext = context.getApplicationContext();

        // A receiver's onReceive runs on the main thread and is expected to return promptly,
        // and the setting lives in a DataStore. goAsync would keep the process alive for the
        // read; starting the service is itself the thing that keeps it alive, so a plain
        // scheduler hop is enough here.
        Schedulers.io().scheduleDirect(() -> {
            try {
                final UserSettings settings =
                        new UserSettingsRepository(UserSettingsDataStore.getInstance(appContext))
                                .getUserSettings();

                if (settings.shouldScanInBackground()) {
                    Log.i(TAG, "Background scanning is on; starting the service after boot");
                    NearbyScanService.start(appContext);
                }
            } catch (final Exception e) {
                Log.w(TAG, "Could not read whether to scan in the background after boot", e);
            }
        });
    }
}
