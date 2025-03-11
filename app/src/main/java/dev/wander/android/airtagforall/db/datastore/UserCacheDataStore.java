package dev.wander.android.airtagforall.db.datastore;

import android.content.Context;

import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;
import androidx.datastore.rxjava3.RxDataStore;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UserCacheDataStore {
    private static final String SETTINGS_FILE_NAME = "user_cache";

    private static RxDataStore<Preferences> USER_CACHE_DATASTORE = null;

    public static final Preferences.Key<String> ANISETTE_SERVER_LIST = PreferencesKeys.stringKey("anisette_server_list");
    public static final Preferences.Key<Long> ANISETTE_SERVER_LIST_TIMESTAMP = PreferencesKeys.longKey("anisette_server_list_timestamp");

    public static RxDataStore<Preferences> getInstance(Context context) {
        if (USER_CACHE_DATASTORE == null) {
            USER_CACHE_DATASTORE = new RxPreferenceDataStoreBuilder(context, SETTINGS_FILE_NAME)
                    .build();
        }
        return USER_CACHE_DATASTORE;
    }
}
