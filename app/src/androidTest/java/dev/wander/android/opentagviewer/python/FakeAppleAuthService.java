package dev.wander.android.opentagviewer.python;

import com.chaquo.python.PyObject;

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

    /**
     * Stands in for the half-signed-in account a terms failure carries.
     *
     * <p>Never called into - the screen only passes it back - so any non-null PyObject does.
     * A real one would need Python and an Apple session, which is the whole reason this class
     * exists.
     */
    public static final PyObject AN_ACCOUNT = PyObject.fromJava("an account");

    private List<TermsDocument> termsDocuments = List.of();
    private final List<String> acceptedPageIds = new ArrayList<>();
    private String stateAfterAcceptingTerms = "LOGGED_IN";
    private RuntimeException termsFetchFailsWith;
    private RuntimeException acceptFailsWith;

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

    /**
     * <b>Apple takes the code and then fails to finish - so the code is spent.</b>
     *
     * <p>FindMy.py's submit does two calls: the check, which passed, and a Grand Slam
     * re-authentication, which 503s. The message is the one that actually arrives across the
     * bridge, because that string is what the app has to pattern-match on - FindMy.py folds
     * every non-OK status into {@code UnhandledProtocolError} carrying only the number.
     *
     * <p>Reported as issue #168; the desktop exporter fixed its half in #169.
     */
    public FakeAppleAuthService whereAppleTakesTheCodeThenFails() {
        this.codeFailsWith = new RuntimeException(
                "com.chaquo.python.PyException: findmy.errors.UnhandledProtocolError:"
                        + " Error response for GSA request: 503");
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

    @Override
    public Observable<List<TermsDocument>> pendingTerms(final PyObject account) {
        this.calls.add("pendingTerms");

        if (this.termsFetchFailsWith != null) {
            return Observable.error(this.termsFetchFailsWith);
        }

        return Observable.just(this.termsDocuments);
    }

    @Override
    public Observable<TermsAcceptance> acceptTerms(final PyObject account, final String pageId) {
        this.calls.add("acceptTerms");
        this.acceptedPageIds.add(pageId);

        if (this.acceptFailsWith != null) {
            return Observable.error(this.acceptFailsWith);
        }

        final int remaining = this.termsDocuments.size() - this.acceptedPageIds.size();

        return Observable.just(new TermsAcceptance(
                Math.max(remaining, 0),
                remaining > 0 ? null : this.stateAfterAcceptingTerms));
    }

    /**
     * An account Apple is waiting on terms for.
     *
     * <p>The documents are the point: what reaches the screen is what somebody would be agreeing
     * to, so a test can assert the text on screen is the text that was handed over, unshortened.
     */
    public static FakeAppleAuthService wantsTermsAccepted(final TermsDocument... documents) {
        final FakeAppleAuthService fake =
                new FakeAppleAuthService(LOGIN_STATE.LOGGED_OUT, List.of());
        fake.termsDocuments = List.of(documents);
        // Carries an account, which is what tells "terms, and they can be shown" apart from a
        // sign-in that merely stopped at the same place.
        fake.loginFailsWith = new PythonAccountLoginException(
                "MobileMeDelegateError: TERMS",
                PythonAccountLoginException.REASON_TERMS,
                AN_ACCOUNT);

        return fake;
    }

    /**
     * Terms were the reported cause and there turned out to be none - so it was something else.
     *
     * <p>The case the screen must not present as "here are your terms", because which failure
     * means "terms pending" is not established and this is how it finds out.
     */
    public static FakeAppleAuthService reportsTermsButHasNone() {
        return wantsTermsAccepted();
    }

    /** Signing in ends somewhere other than LOGGED_IN even after every document is agreed. */
    public FakeAppleAuthService endingAt(final String loginState) {
        this.stateAfterAcceptingTerms = loginState;
        return this;
    }

    public FakeAppleAuthService whereFetchingTermsFails(final RuntimeException failure) {
        this.termsFetchFailsWith = failure;
        return this;
    }

    public FakeAppleAuthService whereAgreeingFails(final RuntimeException failure) {
        this.acceptFailsWith = failure;
        return this;
    }

    /** Which documents were agreed to, in order, so a test can check nothing was skipped. */
    public List<String> acceptedPageIds() {
        return this.acceptedPageIds;
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
