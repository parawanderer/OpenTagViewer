package dev.wander.android.airtagforall.db.repo;

import static android.security.keystore.KeyProperties.BLOCK_MODE_CBC;
import static android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE;
import static android.security.keystore.KeyProperties.ENCRYPTION_PADDING_PKCS7;
import static android.security.keystore.KeyProperties.KEY_ALGORITHM_AES;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

public class UserAuthRepositoryTest {
    // https://developer.android.com/privacy-and-security/cryptography#choose-algorithm

    private static final String TEST_JSON = "{\"My json data\":\"Привет, мир\"}";

    @Test
    public void testEncodeData() throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        byte[] plainText = TEST_JSON.getBytes(StandardCharsets.UTF_8);

        KeyGenerator keygen = KeyGenerator.getInstance(KEY_ALGORITHM_AES);
        keygen.init(256);
        SecretKey key = keygen.generateKey();
        Cipher cipher = Cipher.getInstance(
                KEY_ALGORITHM_AES + "/" + BLOCK_MODE_CBC + "/" + ENCRYPTION_PADDING_PKCS7
        );
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] cypherText = cipher.doFinal(plainText);
        byte[] iv = cipher.getIV();
        System.out.println("done");
    }

    @Test
    public void encodeDecodeData() {

    }

    @Test
    public void testGetKey() {

    }

    @Test
    public void testDecoddData() {

    }
}
