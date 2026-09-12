package dev.wander.android.opentagviewer.util.android;

import static android.security.keystore.KeyProperties.BLOCK_MODE_GCM;
import static android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE;
import static android.security.keystore.KeyProperties.KEY_ALGORITHM_AES;
import static android.security.keystore.KeyProperties.PURPOSE_DECRYPT;
import static android.security.keystore.KeyProperties.PURPOSE_ENCRYPT;

import static javax.crypto.Cipher.DECRYPT_MODE;
import static javax.crypto.Cipher.ENCRYPT_MODE;

import android.security.keystore.KeyGenParameterSpec;
import android.util.Log;
import android.util.Pair;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.util.Enumeration;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import dev.wander.android.opentagviewer.db.AppCryptographyException;
import dev.wander.android.opentagviewer.db.MissingKeystoreKeyException;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

public final class AppCryptographyUtil {

    private static final String TAG = AppCryptographyUtil.class.getSimpleName();
    // https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec#example:-aes-key-for-encryptiondecryption-in-gcm-mode
    // https://developer.android.com/privacy-and-security/cryptography
    // https://developer.android.com/reference/android/security/keystore/KeyProtection#example:-aes-key-for-encryptiondecryption-in-gcm-mode
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private static final int KEY_SIZE = 256;

    private static final int AES_GMC_IV_SIZE = 12;
    private static final int AES_GMC_TAG_SIZE = 16;

    private static final String ALGORITHM = KEY_ALGORITHM_AES;
    private static final String BLOCK_MODE = BLOCK_MODE_GCM;
    private static final String PADDING = ENCRYPTION_PADDING_NONE;

    private static final String TRANSFORMATION = ALGORITHM + "/" + BLOCK_MODE + "/" + PADDING;

    public static final int EXPECTED_IV_SIZE = AES_GMC_IV_SIZE;

    public synchronized AppEncryptedData encrypt(final byte[] dataToEncrypt, final String keystoreAlias) {
        try {
            SecretKey key = this.getKeyForAlias(keystoreAlias);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(ENCRYPT_MODE, key);
            byte[] cypherText = cipher.doFinal(dataToEncrypt);
            byte[] iv = cipher.getIV();

            return new AppEncryptedData(
                    cypherText,
                    iv
            );
        } catch (Exception e) {
            throw new AppCryptographyException("Failed to encrypt data for " + keystoreAlias, e);
        }
    }

    /**
     * <b>Decryption never creates a key.</b> A key made now cannot open anything written before,
     * so generating one here can only turn a missing key into a failed decrypt - while putting
     * the alias back, which hides the fact that it was ever gone. Absence is reported as
     * {@link MissingKeystoreKeyException}, which is the one decryption failure that is somebody's
     * device rather than this app's bug.
     */
    public synchronized byte[] decrypt(final byte[] dataToDecrypt, final byte[] iv, final String keystoreAlias) {
        final SecretKey existing = this.existingKeyForAlias(keystoreAlias);
        if (existing == null) {
            throw new MissingKeystoreKeyException(
                    "There is no keystore key under " + keystoreAlias + " any more, so what was"
                            + " encrypted with it cannot be read");
        }

        try {
            SecretKey key = existing;
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(DECRYPT_MODE, key, new GCMParameterSpec(AES_GMC_TAG_SIZE * 8, iv));
            return cipher.doFinal(dataToDecrypt);
        } catch (Exception e) {
            throw new AppCryptographyException("Failed to decrypt data for " + keystoreAlias, e);
        }
    }

    public synchronized byte[] decrypt(@NonNull final AppEncryptedData data, final String keystoreAlias) {
        return decrypt(data.getCipherText(), data.getIv(), keystoreAlias);
    }

    /**
     * The key under this alias, or null if there is not one. Never creates.
     *
     * <p>A keystore that cannot be opened at all is reported as absent too: it is the same
     * situation for a caller, and throwing out of here would take down a screen on a path
     * nobody can act on.
     */
    private synchronized SecretKey existingKeyForAlias(@NonNull final String keystoreAlias) {
        try {
            final KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);

            final Key entry = keyStore.getKey(keystoreAlias, null);
            return entry instanceof SecretKey ? (SecretKey) entry : null;
        } catch (final Exception keystoreUnavailable) {
            Log.w(TAG, "Could not read the keystore looking for " + keystoreAlias,
                    keystoreUnavailable);
            return null;
        }
    }

    private synchronized SecretKey getKeyForAlias(@NonNull final String keystoreAlias) throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException, UnrecoverableEntryException, NoSuchProviderException, InvalidAlgorithmParameterException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);

        Key entry = keyStore.getKey(keystoreAlias, null);
        if (entry instanceof SecretKey) {
            return (SecretKey) entry; // use existing
        }

        // else make a new one
        KeyGenerator gen = KeyGenerator.getInstance(ALGORITHM, ANDROID_KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(
                keystoreAlias, PURPOSE_ENCRYPT | PURPOSE_DECRYPT)
                .setKeySize(KEY_SIZE)
                .setBlockModes(BLOCK_MODE)
                .setEncryptionPaddings(PADDING)
                .build()
        );

        SecretKey key = gen.generateKey();
        return key;
    }

    public synchronized Enumeration<String> getAliases() throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        return keyStore.aliases();
    }

    private static Pair<byte[], byte[]> extract(final byte[] combined, final int breakoffPoint) {
        byte[] first = new byte[breakoffPoint];
        byte[] second = new byte[combined.length - breakoffPoint];

        System.arraycopy(combined, 0, first, 0, first.length);
        System.arraycopy(combined, breakoffPoint, second, 0, second.length);

        return Pair.create(first, second);
    }

    private static byte[] combine(final byte[] arr1, final byte[] arr2) {
        byte[] combined = new byte[arr1.length + arr2.length];
        ByteBuffer buffer = ByteBuffer.wrap(combined);
        buffer.put(arr1);
        buffer.put(arr2);
        return buffer.array();
    }

    @RequiredArgsConstructor
    @Getter
    public static final class AppEncryptedData {
        private final byte[] cipherText;
        private final byte[] iv;

        public byte[] flatten() {
            return combine(Objects.requireNonNull(this.iv), Objects.requireNonNull(this.cipherText));
        }

        public static AppEncryptedData fromFlattened(final byte[] flattened) {
            var result = extract(Objects.requireNonNull(flattened), AES_GMC_IV_SIZE);
            return new AppEncryptedData(
              result.second,
              result.first
            );
        }
    }
}
