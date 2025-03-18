package dev.wander.android.opentagviewer.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.List;

import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;

public class AppCryptographyUtilTest {
    // https://developer.android.com/privacy-and-security/cryptography#choose-algorithm

    private static final String TEST_JSON = "{\"Some data\":\"Привет, мир\"}";

    public static final String TEST_KEYSTORE_ALIAS = "__testing_alias__";

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

        var aliases = Collections.list(instance.getAliases());
        assertEquals(List.of(TEST_KEYSTORE_ALIAS), aliases);


        byte[] flattened = result.flatten();
        assertNotNull(flattened);

        var extractedResult = AppCryptographyUtil.AppEncryptedData.fromFlattened(flattened);

        assertArrayEquals(result.getIv(), extractedResult.getIv());
        assertArrayEquals(result.getCipherText(), extractedResult.getCipherText());
    }
}
