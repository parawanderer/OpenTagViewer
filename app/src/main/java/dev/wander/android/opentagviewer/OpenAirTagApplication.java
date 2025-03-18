package dev.wander.android.opentagviewer;

import android.content.res.Configuration;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import com.chaquo.python.android.PyApplication;

import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;

public class OpenAirTagApplication extends PyApplication {
    private static final String TAG = OpenAirTagApplication.class.getSimpleName();

    @Override
    public void onCreate() {
        super.onCreate();

        this.setupTheme();
    }

    public void setupTheme() {
        final int currentNightMode = this.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;

        var userSettingsRepo = new UserSettingsRepository(
                UserSettingsDataStore.getInstance(this.getApplicationContext()));

        var userSettings = userSettingsRepo.getUserSettings();
        final Boolean useDarkTheme = userSettings.getUseDarkTheme();


        if (currentNightMode == Configuration.UI_MODE_NIGHT_NO && useDarkTheme == Boolean.TRUE) {
            Log.i(TAG, "Updating to app dark theme choice");
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else if (currentNightMode == Configuration.UI_MODE_NIGHT_YES && useDarkTheme == Boolean.FALSE) {
            Log.i(TAG, "Updating to app light theme choice");
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }
}
