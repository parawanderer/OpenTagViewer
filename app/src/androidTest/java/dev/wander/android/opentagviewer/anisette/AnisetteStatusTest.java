package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Classifying what local Anisette reports.
 *
 * <p>One distinction here does real work: "Apple shipped a new build" against every other
 * failure. It is the only one where supplying an Apple Music APK by hand would help, so it is
 * the only one that offers to. Getting it wrong in either direction is bad in a quiet way -
 * either people are told to go and find an APK when their problem is that the wifi is off, or
 * the one situation the escape hatch exists for never offers it.
 */
@RunWith(AndroidJUnit4.class)
public class AnisetteStatusTest {

    @Test
    public void aWorkingSourceIsReadyAndNeedsNothing() {
        final AnisetteStatus status = AnisetteStatus.of(FakeAnisetteSource.ready());

        assertEquals(AnisetteStatus.State.READY, status.state());
        assertFalse("a working source should not offer the APK escape hatch",
                status.needsOwnApk());
        assertNull("there is no failure to describe", status.detail());
    }

    @Test
    public void anOrdinaryFailureDoesNotAskAnybodyToGoAndFindAnApk() {
        final AnisetteStatus status = AnisetteStatus.of(
                FakeAnisetteSource.unavailable("Unable to resolve host apps.mzstatic.com"));

        assertEquals(AnisetteStatus.State.UNAVAILABLE, status.state());
        assertFalse("no network is not fixed by supplying a file", status.needsOwnApk());
        assertTrue("the reason should survive to the UI, which shows it",
                status.detail().contains("apps.mzstatic.com"));
    }

    /**
     * The state that cannot be produced any other way. Apple last shipped a build in April
     * 2025, so without a fake this branch would go untested until the day it mattered.
     */
    @Test
    public void aManifestMismatchIsRecognisedAsAppleHavingChangedTheLibraries() {
        final AnisetteStatus status = AnisetteStatus.of(
                FakeAnisetteSource.appleChangedTheLibraries("4.9.6.1447"));

        assertEquals(AnisetteStatus.State.APPLE_CHANGED, status.state());
        assertTrue("this is the one case where supplying an APK helps", status.needsOwnApk());
        assertTrue("the expected version should reach the user, who has to go and find it",
                status.detail().contains("4.9.6.1447"));
    }

    /**
     * A session established locally, now falling back. Still an ordinary failure as far as the
     * status goes - the machine-identity consequence is reported separately, and must not turn
     * this into an offer to supply an APK.
     */
    @Test
    public void fallingBackAfterALocalSessionIsStillJustUnavailable() {
        final AnisetteStatus status = AnisetteStatus.of(
                FakeAnisetteSource.unavailableAfterLocalSession("no network"));

        assertEquals(AnisetteStatus.State.UNAVAILABLE, status.state());
        assertFalse(status.needsOwnApk());
    }

    /** No source at all is "nothing has happened yet", not "something went wrong". */
    @Test
    public void noSourceIsPendingRatherThanBroken() {
        final AnisetteStatus status = AnisetteStatus.of(null);

        assertEquals(AnisetteStatus.State.PENDING, status.state());
        assertFalse(status.needsOwnApk());
    }

    /** Asking for the status must not repeatedly redo the expensive part. */
    @Test
    public void theSourceIsAskedOncePerStatus() {
        final FakeAnisetteSource source = FakeAnisetteSource.ready();

        AnisetteStatus.of(source);

        assertEquals("one status should cost one readiness check",
                1, source.ensureReadyCalls());
    }
}
