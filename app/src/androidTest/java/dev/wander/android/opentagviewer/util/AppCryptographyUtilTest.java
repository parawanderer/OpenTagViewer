package dev.wander.android.opentagviewer.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Collections;

import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

public class AppCryptographyUtilTest {
    // https://developer.android.com/privacy-and-security/cryptography#choose-algorithm

    private static final String TEST_JSON = "{\"Some data\":\"Привет, мир\"}";

    public static final String TEST_KEYSTORE_ALIAS = "__testing_alias__";

    /** Matches AppCryptographyUtil.ANDROID_KEYSTORE, which is private to that class. */
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    /**
     * Keys in the AndroidKeyStore outlive the process, the test run and app upgrades - only
     * uninstalling clears them. Cleaning up before as well as after means a crashed or killed
     * earlier run cannot leave behind a key that this one would silently reuse.
     */
    @Before
    public void removeTestKeyBeforeTest() throws CertificateException, KeyStoreException, IOException, NoSuchAlgorithmException {
        deleteTestKey();
    }

    @After
    public void removeTestKeyAfterTest() throws CertificateException, KeyStoreException, IOException, NoSuchAlgorithmException {
        deleteTestKey();
    }

    private static void deleteTestKey() throws CertificateException, KeyStoreException, IOException, NoSuchAlgorithmException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);

        if (keyStore.containsAlias(TEST_KEYSTORE_ALIAS)) {
            keyStore.deleteEntry(TEST_KEYSTORE_ALIAS);
        }
    }

    @Test
    public void testEncryptDecrypt() throws CertificateException, KeyStoreException, IOException, NoSuchAlgorithmException {
        var instance = new AppCryptographyUtil();

        var result = instance.encrypt(
                TEST_JSON.getBytes(StandardCharsets.UTF_8),
                TEST_KEYSTORE_ALIAS);

        var decrypted = instance.decrypt(
                result,
                TEST_KEYSTORE_ALIAS
        );

        assertEquals(TEST_JSON, new String(decrypted, StandardCharsets.UTF_8));

        // Only that our own alias is there. The AndroidKeyStore is shared by everything
        // running under the app's UID, so asserting on the whole list made this test pass on a
        // clean device and fail on any device that had ever signed in - the login flow leaves
        // AppKeyStoreConstants.KEYSTORE_ALIAS_ACCOUNT behind.
        var aliases = Collections.list(instance.getAliases());
        assertTrue(
                "Expected the keystore to contain " + TEST_KEYSTORE_ALIAS + ", got " + aliases,
                aliases.contains(TEST_KEYSTORE_ALIAS));


        byte[] flattened = result.flatten();
        assertNotNull(flattened);

        var extractedResult = AppCryptographyUtil.AppEncryptedData.fromFlattened(flattened);

        assertArrayEquals(result.getIv(), extractedResult.getIv());
        assertArrayEquals(result.getCipherText(), extractedResult.getCipherText());
    }
}
