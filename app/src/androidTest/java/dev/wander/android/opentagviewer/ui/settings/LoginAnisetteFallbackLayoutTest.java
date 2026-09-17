package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;
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
import dev.wander.android.opentagviewer.anisette.AppleLibraryCrashReport;
import dev.wander.android.opentagviewer.anisette.LocalAnisette;
import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;

/**
 * The escape hatch on the sign-in screen.
 *
 * <p>Signing in no longer needs an Anisette server, so the field for one is hidden - which
 * creates the failure this protects against. Settings is behind a login, so somebody whose
 * device cannot set up local Anisette on a first run has <em>no other route</em> to the one
 * setting that would let them in. Hiding it unconditionally would strand exactly those people,
 * and it would look fine on every device where local Anisette works, which is nearly all of
 * them.
 *
 * <p>This inflates the real sign-in settings layout and calls the real code, with no activity,
 * no account, and no Anisette of any kind - the failing states come from
 * {@link FakeAnisetteSource}, since one of them requires Apple to ship a new build.
 */
@RunWith(AndroidJUnit4.class)
public class LoginAnisetteFallbackLayoutTest {

    private View screen;

    @Before
    public void inflateTheLoginSettings() {
        final Context context = new ContextThemeWrapper(
                getInstrumentation().getTargetContext(),
                com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar);

        // On the main thread: the layout contains Material components whose drawables start
        // animators, which throw "Animators may only be run on Looper threads" anywhere else.
        getInstrumentation().runOnMainSync(() -> this.screen = LayoutInflater.from(context)
                .inflate(R.layout.main_app_settings, null, false));
    }

    /** The normal case, and the reason any of this was done: no server, nothing to configure. */
    @Test
    public void aWorkingDeviceIsNeverAskedAboutServers() {
        apply(AnisetteStatus.of(FakeAnisetteSource.ready()));

        assertEquals("nothing needs a server, so nothing should ask about one",
                View.GONE, view(R.id.anisetteRemoteSection).getVisibility());
        assertEquals(View.GONE, view(R.id.anisetteLoginFallbackReason).getVisibility());
    }

    /**
     * The case this exists for. No network, no local Anisette, and no way into Settings -
     * so the server field has to come back on its own.
     */
    @Test
    public void aFailedSetupBringsTheServerFieldBack() {
        apply(AnisetteStatus.of(FakeAnisetteSource.unavailable("Unable to resolve host")));

        assertEquals("this is the only way left to sign in",
                View.VISIBLE, view(R.id.anisetteRemoteSection).getVisibility());
        assertEquals("a field appearing unbidden needs to say why",
                View.VISIBLE, view(R.id.anisetteLoginFallbackReason).getVisibility());
    }

    /** Including when the reason is that Apple replaced the libraries. */
    @Test
    public void appleChangingTheLibrariesAlsoBringsItBack() {
        apply(AnisetteStatus.of(FakeAnisetteSource.appleChangedTheLibraries("4.9.6.1447")));

        assertEquals(View.VISIBLE, view(R.id.anisetteRemoteSection).getVisibility());
    }

    /**
     * Somebody who chose a server gets the field without being told their device failed,
     * because it did not - they asked for this.
     */
    @Test
    public void choosingAServerShowsTheFieldWithoutBlamingTheDevice() {
        apply(AnisetteStatus.of(
                FakeAnisetteSource.unavailable("a remote Anisette server is selected in Settings")),
                true);

        assertEquals(View.VISIBLE, view(R.id.anisetteRemoteSection).getVisibility());
        assertEquals("they chose this, so there is nothing to explain",
                View.GONE, view(R.id.anisetteLoginFallbackReason).getVisibility());
    }

    /**
     * While the answer is still coming, nothing appears.
     *
     * <p>Otherwise the field would flash onto the sign-in screen and vanish again on every
     * launch, which reads as a fault rather than as a check completing.
     */
    @Test
    public void nothingAppearsWhileTheAnswerIsStillComing() {
        apply(AnisetteStatus.checking());

        assertEquals(View.GONE, view(R.id.anisetteRemoteSection).getVisibility());
        assertEquals(View.GONE, view(R.id.anisetteLoginFallbackReason).getVisibility());
    }

    /**
     * Before anything has been tried, the field is shown but unexplained.
     *
     * <p>Erring towards showing: an unnecessary field costs a moment's confusion, while a
     * missing one leaves somebody on a screen they cannot get off. There is no explanation to
     * give, because nothing has failed yet.
     */
    @Test
    public void anUntriedSetupShowsTheFieldWithoutAnExcuse() {
        apply(AnisetteStatus.pending());

        assertEquals(View.VISIBLE, view(R.id.anisetteRemoteSection).getVisibility());
        assertEquals(View.GONE, view(R.id.anisetteLoginFallbackReason).getVisibility());
    }

    /** Recovering has to hide it again, or the explanation outlives the problem. */
    @Test
    public void recoveringHidesItAgain() {
        apply(AnisetteStatus.of(FakeAnisetteSource.unavailable("Unable to resolve host")));
        apply(AnisetteStatus.of(FakeAnisetteSource.ready()));

        assertEquals(View.GONE, view(R.id.anisetteRemoteSection).getVisibility());
        assertEquals(View.GONE, view(R.id.anisetteLoginFallbackReason).getVisibility());
    }

    /**
     * Forced on even though local Anisette works, and without blaming the device.
     *
     * <p>The case a refused sign-in reaches: local produced fine, Apple still said no, and the
     * user asked to try a server. READY hides the field, so this is the only thing that brings it
     * back - and it must not say the device failed, because it did not.
     */
    @Test
    public void aRefusedSignInCanForceTheFieldOnAReadyDevice() {
        apply(AnisetteStatus.of(FakeAnisetteSource.ready()), false, true);

        assertEquals("the user asked for the server after Apple refused them",
                View.VISIBLE, view(R.id.anisetteRemoteSection).getVisibility());
        assertEquals("the device did not fail, so there is nothing to explain",
                View.GONE, view(R.id.anisetteLoginFallbackReason).getVisibility());
    }

    /**
     * <b>A phone Apple's library crashes on (#232) says so, and asks for a report.</b> The general
     * sentence says the device "could not set up sign-in", which reads as worth retrying; nothing
     * the user does changes this, and which phone it is is what would help.
     */
    @Test
    public void aPhoneAppleCrashesOnIsToldSoAndOfferedAReport() {
        apply(AnisetteStatus.of(FakeAnisetteSource.unavailable(LocalAnisette.CRASHES_HERE_REASON)));

        assertEquals("they still need the server to sign in",
                View.VISIBLE, view(R.id.anisetteRemoteSection).getVisibility());
        assertEquals(this.screen.getContext().getString(R.string.anisette_login_crashes_here),
                ((TextView) view(R.id.anisetteLoginFallbackReason)).getText().toString());
        assertEquals(View.VISIBLE, view(R.id.anisetteReportCrashButton).getVisibility());
    }

    /** Any other failure keeps the general sentence and asks for nothing. */
    @Test
    public void anOrdinaryFailureAfterACrashGoesBackToTheGeneralSentence() {
        apply(AnisetteStatus.of(FakeAnisetteSource.unavailable(LocalAnisette.CRASHES_HERE_REASON)));
        apply(AnisetteStatus.of(FakeAnisetteSource.unavailable("Unable to resolve host")));

        assertEquals(this.screen.getContext().getString(R.string.anisette_login_needs_server),
                ((TextView) view(R.id.anisetteLoginFallbackReason)).getText().toString());
        assertEquals("a network failure is not worth a bug report",
                View.GONE, view(R.id.anisetteReportCrashButton).getVisibility());
    }

    /** The report is only worth sending if it names the phone. */
    @Test
    public void theReportNamesThePhone() {
        final String report = AppleLibraryCrashReport.describe(
                getInstrumentation().getTargetContext(),
                AnisetteStatus.of(FakeAnisetteSource.unavailable(LocalAnisette.CRASHES_HERE_REASON)));

        assertTrue(report, report.contains(Build.MODEL));
        assertTrue(report, report.contains("API " + Build.VERSION.SDK_INT));
        assertTrue(report, report.contains(Build.SUPPORTED_ABIS[0]));
        assertTrue(report, report.contains("#232"));
    }

    private void apply(final AnisetteStatus status) {
        apply(status, false);
    }

    private void apply(final AnisetteStatus status, final boolean remoteWasChosen) {
        apply(status, remoteWasChosen, false);
    }

    private void apply(final AnisetteStatus status, final boolean remoteWasChosen,
                       final boolean forceShow) {
        getInstrumentation().runOnMainSync(() ->
                SharedMainSettingsManager.applyLoginAnisetteFallback(
                        this.screen, status, remoteWasChosen, forceShow));
    }

    private View view(final int id) {
        final View found = this.screen.findViewById(id);
        assertNotNull("the sign-in settings layout has no view with this id", found);
        return found;
    }
}
