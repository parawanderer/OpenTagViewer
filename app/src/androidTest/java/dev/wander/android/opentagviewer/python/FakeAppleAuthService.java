package dev.wander.android.opentagviewer.python;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.wander.android.opentagviewer.anisette.AnisetteSource;
import dev.wander.android.opentagviewer.python.PythonAuthService.AuthMethod;
import dev.wander.android.opentagviewer.python.PythonAuthService.AuthMethodPhone;
import dev.wander.android.opentagviewer.python.PythonAuthService.LOGIN_STATE;
import dev.wander.android.opentagviewer.python.PythonAuthService.PythonAuthResponse;
import dev.wander.android.opentagviewer.python.PythonAuthService.TWO_FACTOR_METHOD;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;

/**
 * An Apple that behaves however a test needs it to.
 *
 * <p>The real one runs Python against Apple's servers with real credentials and sends a real
 * code to a real phone, so the sign-in screen it drives has never been exercised by anything
 * but a person. Everything here is synchronous and immediate - the screen's own threading is
 * what is under test, not this.
 *
 * <p>It also records what it was asked, because several of the bugs this class exists to catch
 * are about the screen calling the wrong thing rather than drawing the wrong thing: submitting
 * a code against the method the user did not choose, or asking for a second code every time
 * somebody goes back a page.
 */
public final class FakeAppleAuthService implements AppleAuthService {

    /** A phone number Apple could send a code to. Two, so "the right one" can be got wrong. */
    public static final String PHONE_ONE = "+44 ******1234";
    public static final String PHONE_TWO = "+44 ******9876";

    public static final byte[] SESSION = "{\"fake\":\"session\"}".getBytes();

    private final LOGIN_STATE loginState;
    private final List<AuthMethod> authMethods;

    private RuntimeException loginFailsWith;
    private RuntimeException codeFailsWith;

    /** Every call, in order, so a test can assert on what was asked and how often. */
    private final List<String> calls = new ArrayList<>();

    private String submittedCode;
    private AuthMethod codeSubmittedAgainst;
    private AnisetteSource anisetteUsed;
    private String serverUrlUsed;

    private FakeAppleAuthService(LOGIN_STATE loginState, List<AuthMethod> authMethods) {
        this.loginState = loginState;
        this.authMethods = authMethods;
    }

    /**
     * The ordinary case: correct password, and Apple wants a second factor.
     *
     * <p>Offers a trusted device and two phone numbers, because the screen builds the phone
     * buttons dynamically and one of anything hides the bugs in doing so.
     */
    public static FakeAppleAuthService wantsTwoFactor() {
        return new FakeAppleAuthService(LOGIN_STATE.REQUIRE_2FA, Arrays.asList(
                new AuthMethod(TWO_FACTOR_METHOD.TRUSTED_DEVICE, null),
                new AuthMethodPhone(TWO_FACTOR_METHOD.PHONE, null, PHONE_ONE, "1"),
                new AuthMethodPhone(TWO_FACTOR_METHOD.PHONE, null, PHONE_TWO, "2")));
    }

    /** Apple wants a second factor, and the only way to get one is a trusted device. */
    public static FakeAppleAuthService wantsTwoFactorFromATrustedDeviceOnly() {
        return new FakeAppleAuthService(LOGIN_STATE.REQUIRE_2FA, List.of(
                new AuthMethod(TWO_FACTOR_METHOD.TRUSTED_DEVICE, null)));
    }

    /** Already trusted: signing in completes without a second factor. */
    public static FakeAppleAuthService signsInImmediately() {
        return new FakeAppleAuthService(LOGIN_STATE.LOGGED_IN, null);
    }

    /** The password is wrong, or Apple is unreachable. */
    public static FakeAppleAuthService rejectsTheSignIn(final String message) {
        final FakeAppleAuthService fake =
                new FakeAppleAuthService(LOGIN_STATE.LOGGED_OUT, null);
        fake.loginFailsWith = new PythonAccountLoginException(message);
        return fake;
    }

    /**
     * Apple could not be reached at all - the failure that produced an empty error message.
     *
     * <p>Worth its own named state rather than {@code rejectsTheSignIn("")}, because the shape
     * is what matters: a timeout carries <b>no message</b>, so the screen has to build the
     * sentence from the reason instead of echoing what it was handed.
     */
    public static FakeAppleAuthService cannotReachApple() {
        final FakeAppleAuthService fake =
                new FakeAppleAuthService(LOGIN_STATE.LOGGED_OUT, null);
        // Empty, exactly as str(TimeoutError()) arrives from Python.
        fake.loginFailsWith = new PythonAccountLoginException(
                "", PythonAccountLoginException.REASON_NETWORK);
        return fake;
    }

    /** Signing in works, but the code that gets typed is refused. */
    public FakeAppleAuthService thatRejectsTheCode(final String message) {
        this.codeFailsWith = new PythonAccountLoginException(message);
        return this;
    }

    @Override
    public Observable<PythonAuthResponse> login(
            final String emailOrPhone, final String password, final String anisetteServerUrl,
            final AnisetteSource localAnisette) {
        this.calls.add("login");
        this.anisetteUsed = localAnisette;
        this.serverUrlUsed = anisetteServerUrl;

        if (this.loginFailsWith != null) {
            return Observable.error(this.loginFailsWith);
        }

        // The real sign-in runs in Python, which asks local Anisette whether it is usable,
        // falls back to the server if not, and records which one it ended up using. That
        // recording is what the screen reads afterwards to store the mode, so a fake that
        // skipped it would leave the screen reading a value nothing had written.
        if (localAnisette != null) {
            localAnisette.recordSessionProvenance(localAnisette.ensureReady());
        }
        return Observable.just(
                new PythonAuthResponse(null, this.loginState, this.authMethods));
    }

    @Override
    public Completable requestCode(final AuthMethod selectedAuthMethod) {
        this.calls.add("requestCode");
        return Completable.complete();
    }

    @Override
    public Completable submitCode(final AuthMethod selectedAuthMethod, final String authCode) {
        this.calls.add("submitCode");
        this.submittedCode = authCode;
        this.codeSubmittedAgainst = selectedAuthMethod;

        return this.codeFailsWith == null
                ? Completable.complete() : Completable.error(this.codeFailsWith);
    }

    @Override
    public Observable<byte[]> retrieveAuthData(final PythonAuthResponse authResponse) {
        this.calls.add("retrieveAuthData");
        return Observable.just(SESSION);
    }

    public List<String> calls() {
        return this.calls;
    }

    public long timesCalled(final String call) {
        return this.calls.stream().filter(call::equals).count();
    }

    public String submittedCode() {
        return this.submittedCode;
    }

    /** Which method the code went to - the phone number Apple actually texted. */
    public AuthMethod codeSubmittedAgainst() {
        return this.codeSubmittedAgainst;
    }

    /** What the screen handed over as its Anisette source. Null means it passed none. */
    public AnisetteSource anisetteUsed() {
        return this.anisetteUsed;
    }

    /** The fallback server URL the screen passed. Must never be null - Python needs one. */
    public String serverUrlUsed() {
        return this.serverUrlUsed;
    }
}
