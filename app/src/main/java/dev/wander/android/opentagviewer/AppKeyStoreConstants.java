package dev.wander.android.opentagviewer;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AppKeyStoreConstants {
    public static final String KEYSTORE_ALIAS_ACCOUNT = "user_account";

    /**
     * This app's membership of the user's keychain: a peer id and two private keys.
     *
     * <p>Its own alias rather than sharing the account's, because the two have different
     * lifetimes. Signing out clears the session; it must not take the membership with it, or
     * a peer is left on the account that this app can no longer use or clean up.
     */
    public static final String KEYSTORE_ALIAS_KEYCHAIN = "keychain_membership";
}
