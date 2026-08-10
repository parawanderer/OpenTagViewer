package dev.wander.android.opentagviewer.python;

import android.content.Context;

import androidx.annotation.VisibleForTesting;

import java.util.function.Function;

import dev.wander.android.opentagviewer.anisette.AnisetteSource;
import dev.wander.android.opentagviewer.anisette.LocalAnisette;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;

/**
 * What the sign-in screen depends on, in one place a test can replace.
 *
 * <p>The screen builds everything it needs inside {@code onCreate}, which is the ordinary
 * Android shape and fine right up until you want to launch it. Two of those things reach the
 * network before a single view is drawn: signing in runs Python against Apple, and local
 * Anisette downloads Apple's ADI libraries from their CDN. Neither can be arranged in a test,
 * so the whole four-page flow - the part of the app with the most transitions and the least
 * coverage - could only ever be checked by hand with a real account and a real phone.
 *
 * <p><b>A settable global rather than constructor injection</b> because an activity is
 * constructed by the framework, and this app has no DI container to teach otherwise. The
 * alternative shapes all cost more than they are worth here: an Application subclass holding
 * these is the same global with more indirection, and a whole framework is a large change to
 * this codebase for one screen. Production never calls the setters; they are for tests, and
 * {@link #reset()} in a teardown puts the real ones back.
 */
public final class LoginDependencies {

    private LoginDependencies() {}

    private static AppleAuthService authService = new PythonAppleAuthService();

    /**
     * How to build Anisette for a given settings object. A factory rather than an instance
     * because the real one needs a Context and the current settings, and neither exists when
     * this class is loaded.
     */
    private static AnisetteFactory anisetteFactory = LocalAnisette::new;

    /** Builds the Anisette source for a screen, given where it is running and who is signed in. */
    public interface AnisetteFactory {
        AnisetteSource create(Context context, UserSettings settings, boolean hasExistingSession);
    }

    public static AppleAuthService authService() {
        return authService;
    }

    public static AnisetteSource anisette(
            final Context context, final UserSettings settings, final boolean hasExistingSession) {
        return anisetteFactory.create(context, settings, hasExistingSession);
    }

    @VisibleForTesting
    public static void replaceAuthService(final AppleAuthService replacement) {
        authService = replacement;
    }

    @VisibleForTesting
    public static void replaceAnisette(final Function<UserSettings, AnisetteSource> replacement) {
        anisetteFactory = (context, settings, hasSession) -> replacement.apply(settings);
    }

    /** Put the real ones back. Call from a teardown, or the next test inherits a fake. */
    @VisibleForTesting
    public static void reset() {
        authService = new PythonAppleAuthService();
        anisetteFactory = LocalAnisette::new;
    }
}
