package dev.wander.android.opentagviewer.db.repo;

import static dev.wander.android.opentagviewer.AppKeyStoreConstants.KEYSTORE_ALIAS_ACCOUNT;
import static dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore.APPLE_ACCOUNT;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.rxjava3.RxDataStore;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

import dev.wander.android.opentagviewer.db.AppCryptographyException;
import dev.wander.android.opentagviewer.db.repo.model.AppleUserData;
import dev.wander.android.opentagviewer.db.repo.model.UserAuthData;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.NonNull;

public class UserAuthRepository {
    private static final String TAG = UserAuthRepository.class.getSimpleName();
    private final RxDataStore<Preferences> userAccountDataStore;

    private final AppCryptographyUtil cryptography;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public UserAuthRepository(@NonNull RxDataStore<Preferences> userAccountDataStore, @NonNull AppCryptographyUtil cryptography) {
        this.userAccountDataStore = userAccountDataStore;
        this.cryptography = cryptography;
    }

    public Observable<Optional<AppleUserData>> getUserAuth() {
        return userAccountDataStore.data()
            .map(settings -> {
                byte[] encryptedCombined = settings.get(APPLE_ACCOUNT);
                if (encryptedCombined != null) {
                    var result = this.decrypt(encryptedCombined);
                    var uiFacingProperties = MAPPER.readValue(result, UserAuthData.class);
                    return Optional.of(new AppleUserData(uiFacingProperties, encryptedCombined));
                }
                return Optional.<AppleUserData>empty();
            })
            .toObservable()
            .take(1)
            .subscribeOn(Schedulers.io());
    }

    public Completable clearUser() {
        return userAccountDataStore.updateDataAsync(settings -> {
            MutablePreferences mutablePreferences = settings.toMutablePreferences();
            mutablePreferences.remove(APPLE_ACCOUNT);
            return Single.just(mutablePreferences);
        })
        .subscribeOn(Schedulers.io())
        .ignoreElement();
    }

    public Completable storeUserAuth(byte[] userAuthData) {
        return userAccountDataStore.updateDataAsync(settings -> {
            MutablePreferences mutablePreferences = settings.toMutablePreferences();
            var encrypted = this.cryptography.encrypt(userAuthData, KEYSTORE_ALIAS_ACCOUNT);

            if (encrypted.getIv().length != AppCryptographyUtil.EXPECTED_IV_SIZE)
                throw new AppCryptographyException("Unexpected IV size " + encrypted.getIv().length + " when encrypting data for " + KEYSTORE_ALIAS_ACCOUNT);

            mutablePreferences.set(APPLE_ACCOUNT, encrypted.flatten());

            return Single.just(mutablePreferences);
        }).subscribeOn(Schedulers.io())
        .ignoreElement();
    }

    public byte[] decrypt(final byte[] encryptedAuthData) {
        return decrypt(encryptedAuthData, this.cryptography);
    }

    public static byte[] decrypt(final byte[] encryptedAuthData, AppCryptographyUtil cryptographyUtil) {
        var encrypted = AppCryptographyUtil.AppEncryptedData.fromFlattened(encryptedAuthData);
        return cryptographyUtil.decrypt(encrypted, KEYSTORE_ALIAS_ACCOUNT);
    }
}
