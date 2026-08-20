package dev.wander.android.opentagviewer.db.repo;

import static dev.wander.android.opentagviewer.AppKeyStoreConstants.KEYSTORE_ALIAS_KEYCHAIN;
import static dev.wander.android.opentagviewer.db.datastore.UserAuthDataStore.KEYCHAIN_MEMBERSHIP;

import android.util.Log;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.rxjava3.RxDataStore;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import dev.wander.android.opentagviewer.python.icloud.KeychainMembership;
import dev.wander.android.opentagviewer.db.AppCryptographyException;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import lombok.NonNull;

/**
 * Where this app's membership of the user's keychain is kept.
 *
 * <p><b>Encrypted, under its own keystore alias.</b> It holds a circle member's private keys -
 * FindMy.py calls serialising them "the most sensitive thing the library writes" and does it in
 * the clear - so this is the same treatment the Apple session gets, and for a stronger reason.
 * Its own alias rather than the account's because they have different lifetimes: signing out
 * clears the session and must not silently strand the peer.
 *
 * <p>It also holds the escrow passcode. That is a secret this app generated and nobody has ever
 * seen, kept only so the peer can be recovered through escrow if this store is destroyed - which
 * is exactly the situation where it will not be available, so it is a fallback for the
 * <i>account</i> outliving the app rather than for the app losing its data.
 */
public class KeychainMembershipRepository {
    private static final String TAG = KeychainMembershipRepository.class.getSimpleName();

    private static final String FIELD_PEER = "peer";
    private static final String FIELD_ENTROPY = "entropy";
    private static final String FIELD_PASSCODE = "escrowPasscode";
    private static final String FIELD_LABEL = "label";
    private static final String FIELD_SHARES = "shares";

    private final RxDataStore<Preferences> store;
    private final AppCryptographyUtil cryptography;

    public KeychainMembershipRepository(
            @NonNull final RxDataStore<Preferences> store,
            @NonNull final AppCryptographyUtil cryptography) {
        this.store = store;
        this.cryptography = cryptography;
    }

    /** The membership, or empty when this app has not joined - which is the ordinary first run. */
    public Observable<Optional<KeychainMembership>> get() {
        return Observable.fromPublisher(this.store.data()).map(preferences -> {
            final byte[] encrypted = preferences.get(KEYCHAIN_MEMBERSHIP);
            if (encrypted == null) {
                return Optional.empty();
            }

            try {
                final byte[] plain = this.cryptography.decrypt(
                        AppCryptographyUtil.AppEncryptedData.fromFlattened(encrypted),
                        KEYSTORE_ALIAS_KEYCHAIN);
                final JSONObject json = new JSONObject(new String(plain, StandardCharsets.UTF_8));

                return Optional.of(new KeychainMembership(
                        json.getString(FIELD_PEER),
                        json.getString(FIELD_ENTROPY),
                        json.getString(FIELD_PASSCODE),
                        json.optString(FIELD_LABEL, ""),
                        json.optInt(FIELD_SHARES, 0)));
            } catch (Exception e) {
                // **Reported as absent rather than thrown.** A membership that cannot be read is
                // a membership this app cannot use, and the recovery is the same as never having
                // joined: ask for a passcode and join again. Throwing here would take down the
                // screen instead, on a path the user cannot do anything about.
                Log.e(TAG, "The stored keychain membership could not be read", e);
                return Optional.empty();
            }
        });
    }

    /**
     * Store a membership, and refuse to report success unless it is really stored.
     *
     * <p><b>This write is the one that must not be lost.</b> By the time it runs, the join has
     * already happened on Apple's side: a peer exists on the user's account whether or not this
     * succeeds, and these keys are the only copy of the means to use it. A silent failure here
     * leaves them with a stranded peer and this app none the wiser.
     */
    public Completable store(@NonNull final KeychainMembership membership) {
        return Single.fromCallable(() -> {
            final JSONObject json = new JSONObject()
                    .put(FIELD_PEER, membership.getPeerJson())
                    .put(FIELD_ENTROPY, membership.getEntropy())
                    .put(FIELD_PASSCODE, membership.getEscrowPasscode())
                    .put(FIELD_LABEL, membership.getLabel())
                    .put(FIELD_SHARES, membership.getShares());

            final var encrypted = this.cryptography.encrypt(
                    json.toString().getBytes(StandardCharsets.UTF_8), KEYSTORE_ALIAS_KEYCHAIN);

            if (encrypted.getIv().length != AppCryptographyUtil.EXPECTED_IV_SIZE) {
                throw new AppCryptographyException(
                        "Unexpected IV size " + encrypted.getIv().length
                                + " when encrypting the keychain membership");
            }

            return encrypted.flatten();
        }).flatMapCompletable(flattened -> Completable.fromSingle(
                this.store.updateDataAsync(preferences -> {
                    final MutablePreferences mutable = preferences.toMutablePreferences();
                    mutable.set(KEYCHAIN_MEMBERSHIP, flattened);
                    return Single.just(mutable);
                })));
    }

    /**
     * Forget the membership.
     *
     * <p><b>This does not leave the circle</b>, and nothing here can. It only makes this app stop
     * using a peer that still exists on the account - which is the right thing when the stored
     * keys have stopped working, and the wrong thing to reach for casually, because afterwards
     * the peer is unreachable from here.
     */
    public Completable forget() {
        return Completable.fromSingle(this.store.updateDataAsync(preferences -> {
            final MutablePreferences mutable = preferences.toMutablePreferences();
            mutable.remove(KEYCHAIN_MEMBERSHIP);
            return Single.just(mutable);
        }));
    }
}
