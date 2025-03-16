package dev.wander.android.airtagforall.db.repo;

import static dev.wander.android.airtagforall.db.datastore.UserSettingsDataStore.ANISETTE_SERVER_URL;
import static dev.wander.android.airtagforall.db.datastore.UserSettingsDataStore.ENABLE_DEBUG_DATA;
import static dev.wander.android.airtagforall.db.datastore.UserSettingsDataStore.LANGUAGE;
import static dev.wander.android.airtagforall.db.datastore.UserSettingsDataStore.USE_DARK_THEME;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.rxjava3.RxDataStore;

import dev.wander.android.airtagforall.db.repo.model.UserSettings;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class UserSettingsRepository {
    private final RxDataStore<Preferences> userSettingsStore;

    public UserSettingsRepository(RxDataStore<Preferences> userSettingsStore) {
        this.userSettingsStore = userSettingsStore;
    }

    public UserSettings getUserSettings() {
        return userSettingsStore.data()
            .map(settings -> {
                String anisetteServerUrl = settings.get(ANISETTE_SERVER_URL);
                String language = settings.get(LANGUAGE);
                Boolean useDarkTheme = settings.get(USE_DARK_THEME);
                Boolean enableDebugData = settings.get(ENABLE_DEBUG_DATA);

                return UserSettings.builder()
                        .anisetteServerUrl(anisetteServerUrl)
                        .language(language)
                        .useDarkTheme(useDarkTheme)
                        .enableDebugData(enableDebugData)
                        .build();

            }).subscribeOn(Schedulers.io())
            .blockingFirst();
    }

    public Completable storeUserSettings(UserSettings userSettings) {
        return userSettingsStore.updateDataAsync(settings -> {
            MutablePreferences mutablePreferences = settings.toMutablePreferences();
            //String a = settings.get(ANISETTE_SERVER_URL);

            mutablePreferences.set(ANISETTE_SERVER_URL, userSettings.getAnisetteServerUrl());
            mutablePreferences.set(LANGUAGE, userSettings.getLanguage());
            mutablePreferences.set(USE_DARK_THEME, userSettings.getUseDarkTheme());
            mutablePreferences.set(ENABLE_DEBUG_DATA, userSettings.getEnableDebugData());

            return Single.just(mutablePreferences);
        }).subscribeOn(Schedulers.io())
        .ignoreElement();
    }
}
