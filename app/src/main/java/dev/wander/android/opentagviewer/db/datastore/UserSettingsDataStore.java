package dev.wander.android.opentagviewer.db.datastore;

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
    public static final Preferences.Key<Boolean> USE_SYSTEM_COLORS = PreferencesKeys.booleanKey("use_system_colors");

    public static final Preferences.Key<Boolean> ENABLE_DEBUG_DATA = PreferencesKeys.booleanKey("enable_debug_data");
    public static final Preferences.Key<String> MAP_PROVIDER = PreferencesKeys.stringKey("map_provider");
    public static final Preferences.Key<String> AMAP_API_KEY = PreferencesKeys.stringKey("amap_api_key");
    public static final Preferences.Key<String> ANISETTE_MODE = PreferencesKeys.stringKey("anisette_mode");
    public static final Preferences.Key<String> ANISETTE_APK_URI = PreferencesKeys.stringKey("anisette_apk_uri");
    public static final Preferences.Key<Boolean> ANISETTE_UPGRADE_OFFERED = PreferencesKeys.booleanKey("anisette_upgrade_offered");
    public static final Preferences.Key<Boolean> SHOW_APPLE_DEVICES = PreferencesKeys.booleanKey("show_apple_devices");
    public static final Preferences.Key<Boolean> ICLOUD_OFFER_MADE = PreferencesKeys.booleanKey("icloud_offer_made");
    public static final Preferences.Key<Boolean> SCAN_IN_BACKGROUND = PreferencesKeys.booleanKey("scan_in_background");

    public static RxDataStore<Preferences> getInstance(Context context) {
        if (PREFERENCES_DATA_STORE == null) {
            PREFERENCES_DATA_STORE = new RxPreferenceDataStoreBuilder(context, SETTINGS_FILE_NAME)
                    .build();
        }
        return PREFERENCES_DATA_STORE;
    }
}
