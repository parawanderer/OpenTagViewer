package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.wander.android.opentagviewer.db.repo.model.UserSettings;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * {@link EmulatedMacAnisette}'s state machine, with the emulator, Apple and the disk image faked.
 *
 * <p>What it pins, because each one costs the user a device-list entry or a working session when it
 * goes wrong (AGENTS.md rule 21): a fresh install provisions once and keeps that machine; a store
 * that lost its provisioning registers a new machine on a sign-in and <b>never</b> on a restore; a
 * failure anywhere falls back to a remote server; {@code adi.pb} never reaches disk; and an
 * emulator that died in its process is not walked into again.
 */
public class EmulatedMacAnisetteTest {

    private static final String ATTEMPT = "1/macadi/build";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File directory;
    private File library;
    private FakeMacAdi.Opener opener;
    private MemoryProvenance provenanceFile;
    private MemoryDeaths deaths;
    private List<MacIdentity> provisioned;
    private Exception provisioningFails;

    /** {@link NativeLoadGuard.Store} in memory. */
    private static final class MemoryDeaths implements NativeLoadGuard.Store {
        String value;

        @Override
        public String get() {
            return this.value;
        }

        @Override
        public void set(final String attempt) {
            this.value = attempt;
        }

        @Override
        public void clear() {
            this.value = null;
        }
    }

    @Before
    public void anInstall() throws Exception {
        this.directory = this.folder.newFolder("anisette", "mac");
        this.library = new File(this.directory, "CoreADI-4.19-x86_64");
        this.opener = new FakeMacAdi.Opener();
        this.provenanceFile = new MemoryProvenance();
        this.deaths = new MemoryDeaths();
        this.provisioned = new ArrayList<>();
    }

    private static UserSettings local() {
        return UserSettings.builder().anisetteMode(UserSettings.ANISETTE_LOCAL).build();
    }

    /** A new process: nothing shared with any earlier one but the disk. */
    private EmulatedMacAnisette inANewProcess(final EmulatedMacAnisette.Purpose purpose) {
        return in(new EmulatedMacAnisette.Shared(), purpose, local());
    }

    private EmulatedMacAnisette in(final EmulatedMacAnisette.Shared shared,
                                   final EmulatedMacAnisette.Purpose purpose,
                                   final UserSettings settings) {
        return new EmulatedMacAnisette(this.directory, settings, purpose,
                this.provenanceFile.provenance(), this.opener, () -> this.library,
                (identity, adi) -> {
                    if (this.provisioningFails != null) {
                        throw this.provisioningFails;
                    }
                    this.provisioned.add(identity);
                    adi.provisioningFinish(MacAdi.ANONYMOUS_DS_ID, 1, FakeMacAdi.bytes("ptm"),
                            FakeMacAdi.bytes("tk"), 17106176L);
                    return 17106176L;
                },
                this.deaths, ATTEMPT, TestPlists.BASE64, shared);
    }

    private File store() {
        return new File(this.directory, "store");
    }

    private List<String> storedFiles() {
        final String[] names = store().list();
        final List<String> out = names == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(names));
        out.sort(null);
        return out;
    }

    private MacIdentity identity() throws IOException {
        return MacIdentity.load(this.directory);
    }

    // ----- first sign-in -----------------------------------------------------------------------

    @Test
    public void aFreshInstallCreatesProvisionsAndKeepsOneMachine() throws Exception {
        final EmulatedMacAnisette mac = inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN);

        assertTrue(mac.unavailableReason(), mac.ensureReady());

        assertEquals(1, this.provisioned.size());
        final MacIdentity saved = identity();
        assertEquals(this.provisioned.get(0).deviceId(), saved.deviceId());
        assertTrue(saved.provisionedAtMillis() > 0);
        assertEquals(17106176L, saved.routing());
        assertEquals(List.of(FakeMacAdi.PROVISIONED_FILE), storedFiles());
        assertArrayEquals(saved.hostUuidBytes(), this.opener.last().hostUuid);
        assertEquals(List.of(this.library), this.opener.libraries);
    }

    @Test
    public void askingAgainInTheSameProcessDoesNothingAgain() {
        final EmulatedMacAnisette.Shared shared = new EmulatedMacAnisette.Shared();
        assertTrue(in(shared, EmulatedMacAnisette.Purpose.SIGN_IN, local()).ensureReady());
        assertTrue(in(shared, EmulatedMacAnisette.Purpose.SIGN_IN, local()).ensureReady());

        assertEquals(1, this.opener.opened.size());
        assertEquals(1, this.provisioned.size());
    }

    @Test
    public void theNextLaunchIsTheSameMachineAndProvisionsNothing() throws Exception {
        assertTrue(inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN).ensureReady());
        final String deviceId = identity().deviceId();

        final EmulatedMacAnisette later = inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN);
        assertTrue(later.unavailableReason(), later.ensureReady());

        assertEquals(1, this.provisioned.size());
        assertEquals(deviceId, identity().deviceId());
        assertTrue(this.opener.last().calls.contains("storePut " + FakeMacAdi.PROVISIONED_FILE));
        assertEquals(deviceId, new JSONObject(later.deviceIdsJson()).getString("devid"));
    }

    @Test
    public void adiPbNeverReachesTheDisk() {
        final EmulatedMacAnisette mac = inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN);
        assertTrue(mac.ensureReady());
        assertFalse(mac.otp().isEmpty());

        assertTrue("the fake did write it", this.opener.last().store.containsKey("adi.pb"));
        assertFalse(storedFiles().contains("adi.pb"));
    }

    @Test
    public void theHeadersAreTheLibrarysAndTheStoreIsSavedAfterThem() {
        final EmulatedMacAnisette mac = inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN);

        assertEquals(Base64.getEncoder().encodeToString(FakeMacAdi.bytes("otp")), mac.otp());
        assertEquals(Base64.getEncoder().encodeToString(FakeMacAdi.bytes("mid")), mac.machine());
        assertEquals("17106176", mac.router());
        // RequestOTP advanced the store, and what is on disk is the advanced one.
        assertEquals("provisioned+", new String(
                readQuietly(new File(store(), FakeMacAdi.PROVISIONED_FILE))));
    }

    // ----- a store that lost its provisioning ---------------------------------------------------

    private void loseTheStore() {
        for (final File file : store().listFiles()) {
            assertTrue(file.delete());
        }
    }

    @Test
    public void aLostStoreOnASignInRegistersANewMachine() throws Exception {
        assertTrue(inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN).ensureReady());
        final String first = identity().deviceId();
        loseTheStore();

        assertTrue(inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN).ensureReady());

        assertEquals(2, this.provisioned.size());
        assertNotEquals(first, identity().deviceId());
        assertEquals(identity().deviceId(), this.provisioned.get(1).deviceId());
        assertTrue("the old emulator is let go", this.opener.opened.get(1).closed);
    }

    @Test
    public void aLostStoreOnARestoreRegistersNothing() throws Exception {
        assertTrue(inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN).ensureReady());
        this.provenanceFile.provenance().recordEmulatedMacSession(true);
        final String deviceId = identity().deviceId();
        loseTheStore();

        final EmulatedMacAnisette restore = inANewProcess(EmulatedMacAnisette.Purpose.RESTORE);

        assertFalse(restore.ensureReady());
        assertTrue(restore.unavailableReason(), restore.unavailableReason().contains("sign in again"));
        assertEquals(1, this.provisioned.size());
        assertEquals(deviceId, identity().deviceId());
        assertTrue("so the machine change is reported", restore.isChangingMachineIdentity());
    }

    // ----- restores -----------------------------------------------------------------------------

    @Test
    public void aRestoreOfTheMacsSessionUsesTheSameMac() throws Exception {
        assertTrue(inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN).ensureReady());
        this.provenanceFile.provenance().recordEmulatedMacSession(true);

        final EmulatedMacAnisette restore = inANewProcess(EmulatedMacAnisette.Purpose.RESTORE);

        assertTrue(restore.unavailableReason(), restore.ensureReady());
        assertEquals(1, this.provisioned.size());
        assertFalse(restore.isChangingMachineIdentity());
    }

    @Test
    public void aRestoreOnAnInstallWithNoMacCreatesNone() throws Exception {
        this.provenanceFile.provenance().recordEmulatedMacSession(true);

        final EmulatedMacAnisette restore = inANewProcess(EmulatedMacAnisette.Purpose.RESTORE);

        assertFalse(restore.ensureReady());
        assertNull(identity());
        assertTrue(this.provisioned.isEmpty());
        assertTrue(this.opener.opened.isEmpty());
    }

    @Test
    public void aRestoreOfAnUnprovisionedMacProvisionsNothing() throws Exception {
        MacIdentity.generate().save(this.directory);
        this.provenanceFile.provenance().recordEmulatedMacSession(true);

        final EmulatedMacAnisette restore = inANewProcess(EmulatedMacAnisette.Purpose.RESTORE);

        assertFalse(restore.ensureReady());
        assertTrue(this.provisioned.isEmpty());
        assertTrue(this.opener.last().closed);
    }

    @Test
    public void aSessionTheMacFellBackToAServerForStaysOnTheServer() throws Exception {
        assertTrue(inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN).ensureReady());
        this.provenanceFile.provenance().recordEmulatedMacSession(false);
        final int opened = this.opener.opened.size();

        final EmulatedMacAnisette restore = inANewProcess(EmulatedMacAnisette.Purpose.RESTORE);

        assertFalse(restore.ensureReady());
        assertEquals(opened, this.opener.opened.size());
        assertFalse("it was never local, so nothing changed", restore.isChangingMachineIdentity());
    }

    // ----- falling back -------------------------------------------------------------------------

    @Test
    public void aRemoteServerChosenInSettingsTouchesNothing() {
        final EmulatedMacAnisette mac = in(new EmulatedMacAnisette.Shared(),
                EmulatedMacAnisette.Purpose.SIGN_IN,
                UserSettings.builder().anisetteMode(UserSettings.ANISETTE_REMOTE).build());

        assertFalse(mac.ensureReady());
        assertTrue(mac.unavailableReason().contains("remote"));
        assertTrue(this.opener.opened.isEmpty());
        assertEquals("", mac.otp());
    }

    @Test
    public void anEmulatorThatWillNotStartFallsBack() {
        this.opener.failWith = new IOException("could not start the emulated Mac's process");
        final EmulatedMacAnisette mac = inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN);

        assertFalse(mac.ensureReady());
        assertEquals("could not start the emulated Mac's process", mac.unavailableReason());
        assertEquals("", mac.machine());
    }

    @Test
    public void aRefusedProvisioningFallsBackAndLeavesTheMachineUnprovisioned() throws Exception {
        this.provisioningFails = new AdiProvisioning.ProvisioningException("Apple returned HTTP 433");
        final EmulatedMacAnisette mac = inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN);

        assertFalse(mac.ensureReady());
        assertEquals("Apple returned HTTP 433", mac.unavailableReason());
        assertEquals(0, identity().provisionedAtMillis());
        assertTrue(this.opener.last().closed);
        assertTrue(storedFiles().isEmpty());
    }

    @Test
    public void aFailureIsRetriedOnTheNextAskAndNotCached() {
        this.provisioningFails = new AdiProvisioning.ProvisioningException("offline");
        final EmulatedMacAnisette.Shared shared = new EmulatedMacAnisette.Shared();
        assertFalse(in(shared, EmulatedMacAnisette.Purpose.SIGN_IN, local()).ensureReady());

        this.provisioningFails = null;
        assertTrue(in(shared, EmulatedMacAnisette.Purpose.SIGN_IN, local()).ensureReady());
    }

    // ----- the emulator's process dying ---------------------------------------------------------

    @Test
    public void anEmulatorThatDiesIsRecordedAndNotTriedAgain() {
        assertTrue(inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN).ensureReady());
        // The next launch asks the library whether it is provisioned, and the process dies.
        this.opener.configure = adi -> adi.failHeadersWith = new MacAdi.EmulatorDiedException(
                "the emulated Mac's process died during a call", null);
        final EmulatedMacAnisette next = inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN);

        assertFalse(next.ensureReady());
        assertEquals(EmulatedMacAnisette.CRASHES_HERE_REASON, next.unavailableReason());
        assertEquals(ATTEMPT, this.deaths.value);

        final int opened = this.opener.opened.size();
        final EmulatedMacAnisette after = inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN);
        assertFalse(after.ensureReady());
        assertEquals(EmulatedMacAnisette.CRASHES_HERE_REASON, after.unavailableReason());
        assertEquals("not opened again", opened, this.opener.opened.size());
    }

    @Test
    public void aDeathMidSessionIsRecordedAndTheHeadersAreEmpty() {
        final EmulatedMacAnisette mac = inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN);
        assertTrue(mac.ensureReady());
        this.opener.last().failHeadersWith = new MacAdi.EmulatorDiedException("died", null);

        assertEquals("", mac.otp());
        assertEquals(ATTEMPT, this.deaths.value);
        assertFalse(mac.ensureReady());
    }

    @Test
    public void aRecordFromAnotherVersionIsClearedAndTriedAgain() {
        this.deaths.value = "0/macadi/older-build";

        assertTrue(inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN).ensureReady());
        assertNull(this.deaths.value);
    }

    @Test
    public void aProcessThatWentAwayBetweenCallsIsReopenedNotWrittenOff() {
        final EmulatedMacAnisette.Shared shared = new EmulatedMacAnisette.Shared();
        assertTrue(in(shared, EmulatedMacAnisette.Purpose.SIGN_IN, local()).ensureReady());
        this.opener.last().gone = true;

        final EmulatedMacAnisette again = in(shared, EmulatedMacAnisette.Purpose.SIGN_IN, local());
        assertTrue(again.unavailableReason(), again.ensureReady());

        assertEquals(2, this.opener.opened.size());
        assertEquals(1, this.provisioned.size());
        assertNull(this.deaths.value);
    }

    // ----- provenance ---------------------------------------------------------------------------

    @Test
    public void aSignInIsRecordedAsTheMacsInLocalAnisettesFile() {
        final EmulatedMacAnisette mac = inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN);

        mac.recordSessionProvenance(true);

        assertTrue(mac.wasSessionEstablishedLocally());
        assertEquals("emulatedMac", this.provenanceFile.values.get("sessionMachine"));
        assertEquals(true, this.provenanceFile.values.get("sessionEstablishedLocally"));
    }

    @Test
    public void theProvenanceLocalAnisetteWroteIsReadNotLost() {
        // An install from before this change: only LocalAnisette's key.
        this.provenanceFile.values.put("sessionEstablishedLocally", true);
        this.opener.failWith = new IOException("offline");

        final EmulatedMacAnisette mac = inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN);

        assertTrue(mac.wasSessionEstablishedLocally());
        assertTrue("so the machine change is reported", mac.isChangingMachineIdentity());
    }

    @Test
    public void theIdsAnswerBeforeTheMacIsReadyAndAreTheOnesItProvisionsWith() throws Exception {
        final EmulatedMacAnisette mac = inANewProcess(EmulatedMacAnisette.Purpose.SIGN_IN);
        final JSONObject ids = new JSONObject(mac.deviceIdsJson());
        final String serial = mac.serial();

        assertTrue(mac.ensureReady());

        assertEquals(ids.getString("devid"), this.provisioned.get(0).deviceId());
        assertEquals(ids.getString("uid"), this.provisioned.get(0).localUserUuid());
        assertEquals(serial, this.provisioned.get(0).serial());
        assertNotNull(mac.cloudKitDeviceUuid());
        assertTrue(mac.cloudKitDeviceUuid().matches(
                "[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}"));
    }

    private static byte[] readQuietly(final File file) {
        try {
            return MacCoreAdiFetcher.readFile(file);
        } catch (final IOException e) {
            throw new AssertionError(e);
        }
    }
}
