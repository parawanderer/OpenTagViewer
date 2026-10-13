package dev.wander.android.opentagviewer.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

public class KeysFileTest {
    private static final Function<String, byte[]> DECODE = s -> Base64.getDecoder().decode(s);

    private static String key(final int fill) {
        final byte[] k = new byte[28];
        Arrays.fill(k, (byte) fill);
        return Base64.getEncoder().encodeToString(k);
    }

    private static String hex(final int fill) {
        return String.format("%02x", fill).repeat(28);
    }

    private static String entry(final int fill) {
        return "Private key: " + key(fill) + "\nAdvertisement key: x\nHashed adv key: y\n";
    }

    @Test
    public void aSingleKeyFileGivesOneKey() {
        assertEquals(List.of(hex(1)), ExternalAccessory.privateKeysFromKeysFile(entry(1), DECODE));
    }

    @Test
    public void aRotatingTagKeepsEveryKeyInFileOrder() {
        final String text = entry(3) + entry(1) + entry(2);
        assertEquals(List.of(hex(3), hex(1), hex(2)), ExternalAccessory.privateKeysFromKeysFile(text, DECODE));
    }

    @Test
    public void repeatsAreDropped() {
        assertEquals(List.of(hex(1), hex(2)),
                ExternalAccessory.privateKeysFromKeysFile(entry(1) + entry(2) + entry(1), DECODE));
    }

    @Test
    public void windowsLineEndingsAreFine() {
        assertEquals(List.of(hex(1), hex(2)),
                ExternalAccessory.privateKeysFromKeysFile((entry(1) + entry(2)).replace("\n", "\r\n"), DECODE));
    }

    @Test
    public void aFileWithoutKeysIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalAccessory.privateKeysFromKeysFile("Advertisement key: x\n", DECODE));
    }

    @Test
    public void aKeyOfTheWrongLengthIsRefusedRatherThanSkipped() {
        final String shortKey = "Private key: " + Base64.getEncoder().encodeToString(new byte[20]) + "\n";
        assertThrows(IllegalArgumentException.class,
                () -> ExternalAccessory.privateKeysFromKeysFile(entry(1) + shortKey, DECODE));
    }
}
