package dev.wander.android.airtagforall.db.datastore;

import android.content.Context;

import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;
import androidx.datastore.rxjava3.RxDataStore;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UserSettingsDataStore {
    private static final String SETTINGS_FILE_NAME = "user_settings";

    private static RxDataStore<Preferences> PREFERENCES_DATA_STORE = null;

    public static final Preferences.Key<String> ANISETTE_SERVER_URL = PreferencesKeys.stringKey("anisette_server_url");
    public static final Preferences.Key<String> LANGUAGE = PreferencesKeys.stringKey("language");
    public static final Preferences.Key<Boolean> USE_DARK_THEME = PreferencesKeys.booleanKey("use_dark_theme");

    public static RxDataStore<Preferences> getInstance(Context context) {
        if (PREFERENCES_DATA_STORE == null) {
            PREFERENCES_DATA_STORE = new RxPreferenceDataStoreBuilder(context, SETTINGS_FILE_NAME)
                    .build();
        }
        return PREFERENCES_DATA_STORE;
    }
}
