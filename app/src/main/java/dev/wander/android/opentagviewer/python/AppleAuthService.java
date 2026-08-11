package dev.wander.android.opentagviewer.python;

import dev.wander.android.opentagviewer.anisette.AnisetteSource;
import dev.wander.android.opentagviewer.python.PythonAuthService.AuthMethod;
import dev.wander.android.opentagviewer.python.PythonAuthService.PythonAuthResponse;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;

/**
 * Signing in to Apple, as far as the sign-in screen is concerned.
 *
 * <p>This exists to be substituted. Every step behind it runs Python that talks to Apple with
 * real credentials, so the screen driving it could only ever be exercised by hand, against a
 * real account, receiving a real code on a real phone. That is why the flow it drives is the
 * least-tested part of the app and the easiest to break: four pages, transitions fired from
 * three different places, and state that survives across them.
 *
 * <p>Deliberately only what the sign-in screen uses. Restoring a stored account is not here,
 * because nothing on that screen restores one.
 */
public interface AppleAuthService {

    /**
     * @param localAnisette produces Anisette on this device instead of relaying the sign-in
     *                      through a public server. May be null, and may decline at runtime -
     *                      either way the sign-in falls back to {@code anisetteServerUrl}.
     */
    Observable<PythonAuthResponse> login(
            String emailOrPhone, String password, String anisetteServerUrl,
            AnisetteSource localAnisette);

    /** Ask Apple to send a code by the chosen method. */
    Completable requestCode(AuthMethod selectedAuthMethod);

    /** Hand a received code back to Apple. Fails if it is wrong. */
    Completable submitCode(AuthMethod selectedAuthMethod, String authCode);

    /** The session to store, once signing in has succeeded. */
    Observable<byte[]> retrieveAuthData(PythonAuthResponse authResponse);
}
