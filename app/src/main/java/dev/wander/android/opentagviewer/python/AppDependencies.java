package dev.wander.android.opentagviewer.python;

import android.content.Context;

import org.chromium.net.CronetEngine;

import androidx.annotation.VisibleForTesting;

import java.util.function.Function;
import java.util.function.Supplier;

import dev.wander.android.opentagviewer.anisette.AnisetteSource;
import dev.wander.android.opentagviewer.anisette.LocalAnisette;
import dev.wander.android.opentagviewer.python.icloud.ICloudService;
import dev.wander.android.opentagviewer.python.icloud.PythonICloudService;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.service.web.AnisetteServerTesterService;

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
public final class AppDependencies {

    private AppDependencies() {}

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

    /**
     * How to build the thing that asks an Anisette server whether it is alive.
     *
     * <p>Here for the same reason as the rest: the sign-in screen tests a server before it
     * will let anybody past, so a test of the fall-back path would otherwise depend on a
     * stranger's machine being up.
     */
    private static Function<CronetEngine, AnisetteServerTesterService> serverTesterFactory =
            AnisetteServerTesterService::new;

    /**
     * Names an accessory from its plist, through the shared Python heuristic.
     *
     * <p>Here for the same reason as the rest: the real one starts Chaquopy and imports a
     * package, so a screen that used it directly could not be launched in a test. It also makes
     * "an accessory nothing recognises" renderable on demand, rather than needing such a tag.
     */
    private static HardwareDescriber hardwareDescriber = new ChaquopyHardwareDescriber();

    /**
     * Opens a conversation with iCloud on the signed-in account.
     *
     * <p>A supplier rather than an instance because a session is not reusable: it holds a
     * keychain session and a CloudKit client, both with sockets, and it is closed when the
     * screen that opened it goes away.
     *
     * <p>Here for the usual reason, more sharply than most. Every failure this flow has to
     * handle - an account with nothing to recover from, a service having a bad day, a rejected
     * passcode - needs an Apple account in a state nobody can arrange on demand, and the ones
     * that matter most are the ones a real account will never be in.
     */
    private static Supplier<ICloudService> icloudFactory = AppDependencies::openRealICloud;

    private static ICloudService openRealICloud() {
        final PythonAppleService signedIn = PythonAppleService.getInstance();
        if (signedIn == null || signedIn.getAccount() == null) {
            return null;
        }

        return PythonICloudService.openFor(signedIn.getAccount());
    }

    /**
     * A new iCloud session, or null when there is no usable signed-in account.
     *
     * <p>Null is not a crash: the caller reports it as needing a sign-in, which is the same
     * recovery as a session that has expired.
     */
    public static ICloudService icloud() {
        return icloudFactory.get();
    }

    @VisibleForTesting
    public static void replaceICloud(final Supplier<ICloudService> replacement) {
        icloudFactory = replacement;
    }

    public static AppleAuthService authService() {
        return authService;
    }

    public static HardwareDescriber hardwareDescriber() {
        return hardwareDescriber;
    }

    public static AnisetteServerTesterService serverTester(final CronetEngine engine) {
        return serverTesterFactory.apply(engine);
    }

    @VisibleForTesting
    public static void replaceServerTester(final AnisetteServerTesterService replacement) {
        serverTesterFactory = engine -> replacement;
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
    public static void replaceHardwareDescriber(final HardwareDescriber replacement) {
        hardwareDescriber = replacement;
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
        serverTesterFactory = AnisetteServerTesterService::new;
        hardwareDescriber = new ChaquopyHardwareDescriber();
        icloudFactory = AppDependencies::openRealICloud;
    }
}
