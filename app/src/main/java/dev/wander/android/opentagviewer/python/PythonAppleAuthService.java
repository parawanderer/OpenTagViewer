package dev.wander.android.opentagviewer.python;

import com.chaquo.python.PyObject;

import java.util.List;

import dev.wander.android.opentagviewer.anisette.AnisetteSource;
import dev.wander.android.opentagviewer.python.PythonAuthService.AuthMethod;
import dev.wander.android.opentagviewer.python.PythonAuthService.PythonAuthResponse;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;

/**
 * The real thing: {@link AppleAuthService} backed by FindMy.py through Chaquopy.
 *
 * <p>A thin delegation to {@link PythonAuthService}'s static methods rather than a move of the
 * code, so that this change adds a seam and alters no behaviour. The statics stay because
 * restoring an account is called from elsewhere and is not part of this interface.
 */
public final class PythonAppleAuthService implements AppleAuthService {

    @Override
    public Observable<PythonAuthResponse> login(
            final String emailOrPhone, final String password, final String anisetteServerUrl,
            final AnisetteSource localAnisette) {
        return PythonAuthService.pythonLogin(
                emailOrPhone, password, anisetteServerUrl, localAnisette);
    }

    @Override
    public Completable requestCode(final AuthMethod selectedAuthMethod) {
        return PythonAuthService.requestCode(selectedAuthMethod);
    }

    @Override
    public Completable submitCode(final AuthMethod selectedAuthMethod, final String authCode) {
        return PythonAuthService.submitCode(selectedAuthMethod, authCode);
    }

    @Override
    public Observable<byte[]> retrieveAuthData(final PythonAuthResponse authResponse) {
        return PythonAuthService.retrieveAuthData(authResponse);
    }

    @Override
    public Observable<List<TermsDocument>> pendingTerms(final PyObject account) {
        return PythonAuthService.pendingTerms(account);
    }

    @Override
    public Observable<TermsAcceptance> acceptTerms(final PyObject account, final String pageId) {
        return PythonAuthService.acceptTerms(account, pageId);
    }
}
