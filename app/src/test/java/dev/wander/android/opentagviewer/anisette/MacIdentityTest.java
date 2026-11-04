package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

/**
 * The emulated Mac's identity: the shapes Apple is sent, and that a saved one comes back unchanged.
 *
 * <p>Losing or regenerating any of these is a second Mac on the user's Apple account, so the round
 * trip is the thing worth pinning (AGENTS.md rules 11 and 21).
 */
public class MacIdentityTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aFreshMachineHasTheShapesAMacSends() {
        final MacIdentity mac = MacIdentity.generate();

        assertTrue(mac.deviceId(), mac.deviceId().matches("00006000-[0-9A-F]{16}"));
        assertTrue(mac.localUserUuid(), mac.localUserUuid().matches("[0-9A-F]{32}"));
        assertTrue(mac.hostUuid(), mac.hostUuid().matches("[0-9a-f]{32}"));
        assertTrue(mac.serial(), mac.serial().matches("0PENTAGV[0-9A-Z]{4}"));
        assertEquals(0, mac.routing());
        assertEquals(0, mac.provisionedAtMillis());
    }

    @Test
    public void twoFreshMachinesAreTwoMachines() {
        final MacIdentity one = MacIdentity.generate();
        final MacIdentity two = MacIdentity.generate();

        assertNotEquals(one.deviceId(), two.deviceId());
        assertNotEquals(one.localUserUuid(), two.localUserUuid());
        assertNotEquals(one.hostUuid(), two.hostUuid());
    }

    @Test
    public void aSavedMachineLoadsBackExactly() throws Exception {
        final MacIdentity saved = MacIdentity.generate().provisioned(17106176L, 1_790_000_000_000L);
        saved.save(this.folder.getRoot());

        final MacIdentity loaded = MacIdentity.load(this.folder.getRoot());

        assertEquals(saved.deviceId(), loaded.deviceId());
        assertEquals(saved.localUserUuid(), loaded.localUserUuid());
        assertEquals(saved.hostUuid(), loaded.hostUuid());
        assertEquals(saved.serial(), loaded.serial());
        assertEquals(17106176L, loaded.routing());
        assertEquals(1_790_000_000_000L, loaded.provisionedAtMillis());
        assertArrayEquals(saved.hostUuidBytes(), loaded.hostUuidBytes());
    }

    @Test
    public void provisioningKeepsEveryIdAndAddsOnlyTheRouting() {
        final MacIdentity fresh = MacIdentity.generate();
        final MacIdentity done = fresh.provisioned(123L, 456L);

        assertEquals(fresh.deviceId(), done.deviceId());
        assertEquals(fresh.localUserUuid(), done.localUserUuid());
        assertEquals(fresh.hostUuid(), done.hostUuid());
        assertEquals(fresh.serial(), done.serial());
        assertEquals(123L, done.routing());
        assertEquals(456L, done.provisionedAtMillis());
    }

    @Test
    public void savingLeavesNoPartialFileBehind() throws Exception {
        MacIdentity.generate().save(this.folder.getRoot());

        assertTrue(new File(this.folder.getRoot(), "identity.json").isFile());
        assertEquals(1, this.folder.getRoot().listFiles().length);
    }

    @Test
    public void anInstallWithNoneHasNone() throws Exception {
        assertNull(MacIdentity.load(this.folder.getRoot()));
    }

    @Test
    public void anUnreadableOneIsAnErrorAndNotAFreshMachine() throws Exception {
        try (FileOutputStream out = new FileOutputStream(this.folder.newFile("identity.json"))) {
            out.write("{\"deviceId\": ".getBytes(StandardCharsets.UTF_8));
        }

        assertThrows(IOException.class, () -> MacIdentity.load(this.folder.getRoot()));
    }

    @Test
    public void theHostUuidIsSixteenBytesOfTheHex() {
        final MacIdentity mac = MacIdentity.generate();
        final StringBuilder hex = new StringBuilder();
        for (final byte b : mac.hostUuidBytes()) {
            hex.append(String.format("%02x", b));
        }
        assertEquals(mac.hostUuid(), hex.toString());
    }

    @Test
    public void theHardwareFindMyPyIsGivenIsTheMacProvisioningDescribes() throws Exception {
        final JSONObject hardware = new JSONObject(MacIdentity.hardwareJson());

        assertEquals(6, hardware.length());
        assertEquals(MacIdentity.MODEL, hardware.getString("model"));
        assertEquals(MacIdentity.OS_NAME, hardware.getString("os_name"));
        assertEquals(MacIdentity.OS_VERSION, hardware.getString("os_version"));
        assertEquals(MacIdentity.OS_BUILD, hardware.getString("os_build"));
        assertEquals(MacIdentity.CFNETWORK, hardware.getString("cfnetwork"));
        assertEquals(MacIdentity.LOGIN_DARWIN, hardware.getString("darwin"));
        assertEquals("<MacBookPro13,2> <macOS;13.1;22C65> <com.apple.AuthKit/1 (com.apple.akd/1.0)>",
                MacIdentity.CLIENT_INFO);
    }
}
