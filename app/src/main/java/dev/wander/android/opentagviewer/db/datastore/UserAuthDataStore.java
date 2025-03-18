package dev.wander.android.opentagviewer.db.datastore;

import android.content.Context;

import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;
import androidx.datastore.rxjava3.RxDataStore;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UserAuthDataStore {

    private static final String AUTH_FILE_FLENAME = "user_auth";

    private static RxDataStore<Preferences> AUTH_DATA_STORE = null;

    public static final Preferences.Key<byte[]> APPLE_ACCOUNT = PreferencesKeys.byteArrayKey("apple_account");

    public static RxDataStore<Preferences> getInstance(Context context) {
        if (AUTH_DATA_STORE == null) {
            AUTH_DATA_STORE = new RxPreferenceDataStoreBuilder(context, AUTH_FILE_FLENAME)
                    .build();
        }
        return AUTH_DATA_STORE;
    }
}
