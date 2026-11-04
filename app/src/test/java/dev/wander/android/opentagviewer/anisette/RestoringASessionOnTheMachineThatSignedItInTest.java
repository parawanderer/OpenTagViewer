package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which Anisette each caller gets: a fresh sign-in the emulated Mac, a restore the machine that
 * signed the session in.
 *
 * <p>The case this exists for is the upgrade. A session {@link LocalAnisette} established before
 * the emulated Mac existed still fetches reports, its token is bound to the Android machine (#248),
 * and its install has nothing but {@code sessionEstablishedLocally} in
 * {@code LocalAnisette.PREFERENCES}. It must stay on {@link LocalAnisette}, and nothing may be
 * provisioned to restore it.
 */
public class RestoringASessionOnTheMachineThatSignedItInTest {

    private static AnisetteForSession.Choice restoring(final MemoryProvenance file) {
        return AnisetteForSession.choose(true, file.provenance());
    }

    @Test
    public void aSessionLocalAnisetteSignedInBeforeTheUpgradeKeepsLocalAnisette() {
        // Exactly what an older install has on disk: LocalAnisette's key, and nothing beside it.
        final MemoryProvenance olderInstall = new MemoryProvenance();
        olderInstall.values.put("sessionEstablishedLocally", true);

        assertEquals(AnisetteForSession.Choice.ANDROID, restoring(olderInstall));
        assertTrue("the provenance LocalAnisette wrote must still read as local",
                olderInstall.provenance().establishedLocally());
    }

    @Test
    public void aSessionSignedInThroughAServerBeforeTheUpgradeKeepsLocalAnisettesHandling() {
        final MemoryProvenance olderInstall = new MemoryProvenance();
        olderInstall.values.put("sessionEstablishedLocally", false);

        assertEquals(AnisetteForSession.Choice.ANDROID, restoring(olderInstall));
    }

    @Test
    public void anInstallThatNeverRecordedAnythingKeepsLocalAnisette() {
        assertEquals(AnisetteForSession.Choice.ANDROID, restoring(new MemoryProvenance()));
    }

    @Test
    public void aSessionTheEmulatedMacSignedInIsRestoredOntoIt() {
        final MemoryProvenance file = new MemoryProvenance();
        file.provenance().recordEmulatedMacSession(true);

        assertEquals(AnisetteForSession.Choice.EMULATED_MAC_FOR_RESTORE, restoring(file));
    }

    @Test
    public void aFreshSignInGetsTheEmulatedMacWhateverCameBefore() {
        final MemoryProvenance olderInstall = new MemoryProvenance();
        olderInstall.values.put("sessionEstablishedLocally", true);

        assertEquals(AnisetteForSession.Choice.EMULATED_MAC_FOR_SIGN_IN,
                AnisetteForSession.choose(false, olderInstall.provenance()));
    }

    @Test
    public void theMacRecordsInTheFileLocalAnisetteAlwaysUsed() {
        final MemoryProvenance file = new MemoryProvenance();
        file.provenance().recordEmulatedMacSession(true);

        assertEquals(true, file.values.get("sessionEstablishedLocally"));
        assertEquals("emulatedMac", file.values.get("sessionMachine"));
    }

    @Test
    public void signingInAgainThroughLocalAnisetteClearsTheMac() {
        final MemoryProvenance file = new MemoryProvenance();
        file.provenance().recordEmulatedMacSession(true);
        file.provenance().recordAndroidSession(true);

        assertNull(file.values.get("sessionMachine"));
        assertFalse(file.provenance().signedInAsEmulatedMac());
        assertEquals(AnisetteForSession.Choice.ANDROID, restoring(file));
    }
}
