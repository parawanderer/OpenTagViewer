package dev.wander.android.opentagviewer.ui.settings;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.anisette.AnisetteStatus;
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

    private void apply(final AnisetteStatus status) {
        apply(status, false);
    }

    private void apply(final AnisetteStatus status, final boolean remoteWasChosen) {
        getInstrumentation().runOnMainSync(() ->
                SharedMainSettingsManager.applyLoginAnisetteFallback(
                        this.screen, status, remoteWasChosen));
    }

    private View view(final int id) {
        final View found = this.screen.findViewById(id);
        assertNotNull("the sign-in settings layout has no view with this id", found);
        return found;
    }
}
