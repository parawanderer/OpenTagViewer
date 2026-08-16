package dev.wander.android.opentagviewer.db.repo;

import static dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore.AMAP_API_KEY;
import static dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore.ANISETTE_APK_URI;
import static dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore.ANISETTE_MODE;
import static dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore.ANISETTE_UPGRADE_OFFERED;
import static dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore.ANISETTE_SERVER_URL;
import static dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore.ENABLE_DEBUG_DATA;
import static dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore.LANGUAGE;
import static dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore.MAP_PROVIDER;
import static dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore.USE_DARK_THEME;
import static dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore.USE_SYSTEM_COLORS;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.rxjava3.RxDataStore;

import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
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
                Boolean useSystemColors = settings.get(USE_SYSTEM_COLORS);
                Boolean enableDebugData = settings.get(ENABLE_DEBUG_DATA);
                String mapProvider = settings.get(MAP_PROVIDER);
                String amapApiKey = settings.get(AMAP_API_KEY);
                String anisetteMode = settings.get(ANISETTE_MODE);
                String anisetteApkUri = settings.get(ANISETTE_APK_URI);
                Boolean anisetteUpgradeOffered = settings.get(ANISETTE_UPGRADE_OFFERED);

                return UserSettings.builder()
                        .anisetteServerUrl(anisetteServerUrl)
                        .language(language)
                        .useDarkTheme(useDarkTheme)
                        .useSystemColors(useSystemColors)
                        .enableDebugData(enableDebugData)
                        .mapProvider(mapProvider)
                        .amapApiKey(amapApiKey)
                        .anisetteMode(anisetteMode)
                        .anisetteApkUri(anisetteApkUri)
                        .anisetteUpgradeOffered(anisetteUpgradeOffered)
                        .build();

            }).subscribeOn(Schedulers.io())
            .blockingFirst();
    }

    /**
     * Writes only the system-colours choice.
     *
     * <p>Separate from {@link #storeUserSettings} because this is called at application start,
     * to record a decision that was never made explicitly, and writing the whole settings
     * object there would persist whatever else happened to be in memory at the time.
     */
    public Completable storeUseSystemColors(final boolean useSystemColors) {
        return Completable.fromSingle(userSettingsStore.updateDataAsync(settings -> {
            MutablePreferences mutablePreferences = settings.toMutablePreferences();
            mutablePreferences.set(USE_SYSTEM_COLORS, useSystemColors);
            return Single.just(mutablePreferences);
        }));
    }

    public Completable storeUserSettings(UserSettings userSettings) {
        return userSettingsStore.updateDataAsync(settings -> {
            MutablePreferences mutablePreferences = settings.toMutablePreferences();
            //String a = settings.get(ANISETTE_SERVER_URL);

            mutablePreferences.set(ANISETTE_SERVER_URL, userSettings.getAnisetteServerUrl());
            mutablePreferences.set(LANGUAGE, userSettings.getLanguage());
            mutablePreferences.set(USE_DARK_THEME, userSettings.getUseDarkTheme());
            // Null means "never chosen", which reads the same as off - the app's own palette.
            mutablePreferences.set(USE_SYSTEM_COLORS,
                    userSettings.getUseSystemColors() == Boolean.TRUE);
            mutablePreferences.set(ENABLE_DEBUG_DATA, userSettings.getEnableDebugData());
            mutablePreferences.set(MAP_PROVIDER, userSettings.getMapProvider());
            // Null would throw; an empty string reads back as "no key supplied".
            mutablePreferences.set(AMAP_API_KEY,
                    userSettings.getAmapApiKey() == null ? "" : userSettings.getAmapApiKey());
            // An empty string means "not chosen", which is not the same as either mode - see
            // UserSettings.anisetteMode. Writing "local" here for somebody who never chose
            // would move an existing session onto a different machine identity.
            mutablePreferences.set(ANISETTE_MODE,
                    userSettings.getAnisetteMode() == null ? "" : userSettings.getAnisetteMode());
            mutablePreferences.set(ANISETTE_APK_URI,
                    userSettings.getAnisetteApkUri() == null ? "" : userSettings.getAnisetteApkUri());
            mutablePreferences.set(ANISETTE_UPGRADE_OFFERED,
                    userSettings.getAnisetteUpgradeOffered() == Boolean.TRUE);

            return Single.just(mutablePreferences);
        }).subscribeOn(Schedulers.io())
        .ignoreElement();
    }
}
