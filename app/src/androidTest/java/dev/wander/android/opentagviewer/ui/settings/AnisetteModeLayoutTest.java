package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.anisette.AnisetteStatus;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;

/**
 * The Anisette section of Settings, driven directly.
 *
 * <p>What this protects is a decision that is invisible when it goes wrong: which controls a
 * given mode shows. Getting it backwards leaves somebody editing a server URL that nothing
 * reads, or looking at a local status while their sign-ins go to a server - and in both cases
 * the app runs, nothing throws, and the screen looks plausible.
 *
 * <p>It inflates the real dialog layout and calls the real code, with no activity, no account
 * and no Anisette of any kind. The states here are the ones the mode picker distinguishes;
 * {@link dev.wander.android.opentagviewer.anisette.FakeAnisetteSource} covers the ones that
 * depend on whether local Anisette actually works, for the tests that follow it.
 */
@RunWith(AndroidJUnit4.class)
public class AnisetteModeLayoutTest {

    private View dialog;

    @Before
    public void inflateTheDialog() throws Throwable {
        final Context context = new ContextThemeWrapper(
                getInstrumentation().getTargetContext(),
                com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar);

        // On the main thread: the layout contains Material components whose drawables start
        // animators, which throw "Animators may only be run on Looper threads" anywhere else.
        getInstrumentation().runOnMainSync(() -> this.dialog = LayoutInflater.from(context)
                .inflate(R.layout.anisette_server_url_input_dialog, null, false));
    }

    @Test
    public void localModeHidesEverythingAboutServers() {
        apply(UserSettings.ANISETTE_LOCAL);

        assertEquals("the local status should be shown when Anisette is produced here",
                View.VISIBLE, section(R.id.anisetteLocalStatusContainer).getVisibility());

        // Hidden rather than disabled: a server URL and a test button have nothing to say when
        // nothing is being asked of a server.
        assertEquals("the server section should be gone, not merely disabled",
                View.GONE, section(R.id.anisetteRemoteSection).getVisibility());
    }

    @Test
    public void remoteModeShowsTheServerAndHidesTheLocalStatus() {
        apply(UserSettings.ANISETTE_REMOTE);

        assertEquals("the server section should be shown",
                View.VISIBLE, section(R.id.anisetteRemoteSection).getVisibility());
        assertEquals("the local status means nothing when a server is in use",
                View.GONE, section(R.id.anisetteLocalStatusContainer).getVisibility());
    }

    /**
     * Anything other than an explicit "remote" is local.
     *
     * <p>This is the upgrade path in disguise. Somebody who has never chosen has null stored,
     * and null must not read as remote here - the mode they get is decided by
     * {@link UserSettings#resolveAnisetteMode(boolean)}, which knows whether there is a session
     * to preserve. This only has to render whatever it is handed.
     */
    @Test
    public void anythingThatIsNotRemoteRendersAsLocal() {
        apply(null);

        assertEquals(View.VISIBLE, section(R.id.anisetteLocalStatusContainer).getVisibility());
        assertEquals(View.GONE, section(R.id.anisetteRemoteSection).getVisibility());
    }

    /** Switching back and forth has to be symmetrical - a one-way toggle is a real bug. */
    @Test
    public void switchingBackAndForthEndsWhereItStarted() {
        apply(UserSettings.ANISETTE_REMOTE);
        apply(UserSettings.ANISETTE_LOCAL);
        apply(UserSettings.ANISETTE_REMOTE);

        assertEquals(View.VISIBLE, section(R.id.anisetteRemoteSection).getVisibility());
        assertEquals(View.GONE, section(R.id.anisetteLocalStatusContainer).getVisibility());
    }

    /**
     * The supply-your-own-APK controls start hidden.
     *
     * <p>They are only for the case where Apple has replaced the file. Offering them to
     * somebody whose sign-in works would be an invitation to break it.
     */
    @Test
    public void theOwnApkControlsAreHiddenUntilSomethingHasGoneWrong() {
        apply(UserSettings.ANISETTE_LOCAL);

        assertEquals("supplying your own APK should not be offered by default",
                View.GONE, section(R.id.anisetteOwnApkContainer).getVisibility());
    }

    /**
     * A working local Anisette says so, and offers nothing else.
     */
    @Test
    public void aReadyStatusOffersNoEscapeHatch() {
        applyStatus(AnisetteStatus.of(FakeAnisetteSource.ready()));

        assertEquals(View.VISIBLE, section(R.id.anisetteLocalOkIcon).getVisibility());
        assertEquals(View.GONE, section(R.id.anisetteLocalErrorIcon).getVisibility());
        assertEquals("nothing has gone wrong, so do not invite anybody to change it",
                View.GONE, section(R.id.anisetteOwnApkContainer).getVisibility());
    }

    /**
     * An ordinary failure shows the reason but still does not offer the APK controls -
     * supplying a file does not fix a missing network, and suggesting it would send people
     * off to do something pointless and risky.
     */
    @Test
    public void anOrdinaryFailureShowsTheReasonWithoutOfferingTheApkControls() {
        applyStatus(AnisetteStatus.of(
                FakeAnisetteSource.unavailable("Unable to resolve host apps.mzstatic.com")));

        assertEquals(View.VISIBLE, section(R.id.anisetteLocalErrorIcon).getVisibility());
        assertEquals(View.GONE, section(R.id.anisetteLocalOkIcon).getVisibility());
        assertEquals(View.GONE, section(R.id.anisetteOwnApkContainer).getVisibility());

        final TextView status = (TextView) section(R.id.anisetteLocalStatus);
        assertTrue("the reason should be shown, not swallowed",
                status.getText().toString().contains("apps.mzstatic.com"));
    }

    /**
     * The one state that reveals the APK controls, and the reason the fake exists: it depends
     * on Apple shipping a new build, which they last did in April 2025.
     */
    @Test
    public void appleChangingTheLibrariesRevealsTheApkControls() {
        applyStatus(AnisetteStatus.of(
                FakeAnisetteSource.appleChangedTheLibraries("4.9.6.1447")));

        assertEquals("this is the only state where supplying a file helps",
                View.VISIBLE, section(R.id.anisetteOwnApkContainer).getVisibility());

        final TextView explanation = (TextView) section(R.id.anisetteOwnApkExplanation);
        assertTrue("somebody has to know which version to go and find",
                explanation.getText().toString().contains("4.9.6.1447"));
    }

    /** "Go back to Apple's copy" only makes sense once a file has actually been supplied. */
    @Test
    public void revertingToApplesCopyIsOfferedOnlyWhenAFileWasSupplied() {
        final AnisetteStatus changed = AnisetteStatus.of(
                FakeAnisetteSource.appleChangedTheLibraries("4.9.6.1447"));

        applyStatus(changed, false);
        assertEquals(View.GONE, section(R.id.anisetteClearApkButton).getVisibility());

        applyStatus(changed, true);
        assertEquals(View.VISIBLE, section(R.id.anisetteClearApkButton).getVisibility());
    }

    private void applyStatus(final AnisetteStatus status) {
        applyStatus(status, false);
    }

    private void applyStatus(final AnisetteStatus status, final boolean hasOwnApk) {
        getInstrumentation().runOnMainSync(() ->
                SharedMainSettingsManager.applyLocalAnisetteStatus(
                        this.dialog, status, "Apple Music 4.9.6.1447", hasOwnApk));
    }

    /**
     * Applies the mode on the main thread.
     *
     * <p>Setting the dropdown's text runs through TextInputLayout, which animates its label and
     * throws "Animators may only be run on Looper threads" off the main thread. Assertions stay
     * on the test thread so a failure is reported as a failure rather than as a crash on the
     * main looper.
     */
    private void apply(final String mode) {
        getInstrumentation().runOnMainSync(
                () -> SharedMainSettingsManager.applyAnisetteMode(this.dialog, mode));
    }

    private View section(final int id) {
        final View found = this.dialog.findViewById(id);
        assertNotNull("the dialog layout has no view with this id", found);
        return found;
    }
}
