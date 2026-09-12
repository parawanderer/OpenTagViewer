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
import dev.wander.android.opentagviewer.db.MissingKeystoreKeyException;
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

    /**
     * What this app holds, told apart from what it can use.
     *
     * <p><b>"Nothing stored" and "stored but unreadable" are different situations with the same
     * shape.</b> Collapsing them into an empty Optional is right for most callers - either way
     * there is no membership to use - but it makes the app behave as though the account was
     * never connected, which is wrong in a way the user can see: they are offered a first-time
     * setup for something they already did, and nothing anywhere says why.
     */
    public enum MembershipState {
        /** Never joined. The ordinary first run. */
        NONE,
        /** Joined, and the keys are usable. */
        HELD,
        /**
         * Joined, and the keystore key that opened it is gone.
         *
         * <p><b>Explainable, and not this app's fault.</b> The keys live in the Android keystore
         * and the ciphertext lives in app data, and those have different lifetimes - an OS
         * upgrade, a wiped keystore, a device-transfer tool that copied app data and could never
         * copy keystore keys. Nothing here can recover it; the remedy is to join again.
         *
         * <p>Reported rather than repaired: deleting the row on a failure would throw away a
         * membership that a momentary keystore problem had made unreadable for a moment.
         */
        KEYS_GONE,
        /**
         * Joined, the key is right there, and it still does not open the data.
         *
         * <p><b>That is not explainable, so it is a bug.</b> The key present and the ciphertext
         * present and the two not matching means something wrote or stored it wrongly, and the
         * user is owed a bug report rather than an apology - see how {@code MapsActivity} routes
         * this one to the report screen while {@link #KEYS_GONE} gets an explanation.
         */
        UNREADABLE,
    }

    /**
     * Which of the three situations this device is in.
     *
     * <p>Prefer {@link #get()} where only a usable membership matters; use this where the
     * difference between "never connected" and "connected but broken" changes what the user is
     * told.
     */
    public Observable<MembershipState> state() {
        return Observable.fromPublisher(this.store.data()).map(preferences -> {
            final byte[] encrypted = preferences.get(KEYCHAIN_MEMBERSHIP);
            if (encrypted == null) {
                return MembershipState.NONE;
            }

            try {
                this.decode(encrypted);
                return MembershipState.HELD;
            } catch (final MissingKeystoreKeyException keyIsGone) {
                Log.w(TAG, "The keystore key for the membership is gone, so it cannot be read",
                        keyIsGone);
                return MembershipState.KEYS_GONE;
            } catch (final Exception unexplained) {
                Log.e(TAG, "The membership is stored and its key is present, and it still does"
                        + " not decrypt", unexplained);
                return MembershipState.UNREADABLE;
            }
        });
    }

    /** The membership, or empty when this app has not joined - which is the ordinary first run. */
    public Observable<Optional<KeychainMembership>> get() {
        return Observable.fromPublisher(this.store.data()).map(this::readFrom);
    }

    /** Decrypt and parse, or throw. {@link #state()} is the caller that wants to know why. */
    private KeychainMembership decode(final byte[] encrypted) throws Exception {
        final byte[] plain = this.cryptography.decrypt(
                AppCryptographyUtil.AppEncryptedData.fromFlattened(encrypted),
                KEYSTORE_ALIAS_KEYCHAIN);
        final JSONObject json = new JSONObject(new String(plain, StandardCharsets.UTF_8));

        return new KeychainMembership(
                json.getString(FIELD_PEER),
                json.getString(FIELD_ENTROPY),
                json.getString(FIELD_PASSCODE),
                json.optString(FIELD_LABEL, ""),
                json.optInt(FIELD_SHARES, 0));
    }

    private Optional<KeychainMembership> readFrom(final Preferences preferences) {
        final byte[] encrypted = preferences.get(KEYCHAIN_MEMBERSHIP);
        if (encrypted == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(this.decode(encrypted));
        } catch (Exception e) {
            // **Reported as absent rather than thrown.** A membership that cannot be read is
            // a membership this app cannot use, and the recovery is the same as never having
            // joined: ask for a passcode and join again. Throwing here would take down the
            // screen instead, on a path the user cannot do anything about.
            Log.e(TAG, "The stored keychain membership could not be read", e);
            return Optional.empty();
        }
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
