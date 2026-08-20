package dev.wander.android.opentagviewer.python;

import com.chaquo.python.PyObject;

/**
 * A sign-in that did not work, with enough about it to tell the user something useful.
 *
 * <p><b>The reason exists because the message was not enough.</b> It used to carry
 * {@code str(e)} from Python and nothing else - and the failure people actually hit is a
 * connection timeout, whose {@code str()} is the empty string. The screen dutifully rendered
 * "Login failed:" followed by nothing at all, which tells somebody neither what went wrong nor
 * what to do.
 *
 * <p>The reason is a code, not prose: the sentence the user reads is chosen on this side, so it
 * can be translated. The message stays as the detail for logs and for anything unclassified.
 */
public class PythonAccountLoginException extends RuntimeException {

    /** Nothing answered - Apple was not reached at all. Matches {@code REASON_NETWORK}. */
    public static final String REASON_NETWORK = "network";

    /**
     * Apple wants agreement to updated terms. Matches {@code REASON_TERMS}.
     *
     * <p><b>The one failure here the user can actually do something about, and the only one that
     * is not the end of the attempt.</b> Authentication worked; the exchange after it did not.
     * Apple takes acceptance on one of its own devices or on iCloud.com and nowhere else, so a
     * user of this app generally has neither - which is why the app shows the documents itself
     * rather than reporting a dead end.
     *
     * <p>Carries {@link #getAccount()}, because agreeing needs the session that was just
     * established.
     */
    public static final String REASON_TERMS = "terms";

    /** Anything not recognised. The detail is shown as-is rather than guessed at. */
    public static final String REASON_UNKNOWN = "unknown";

    private final String reason;

    /**
     * The half-signed-in account, for {@link #REASON_TERMS} and nothing else.
     *
     * <p>Null for every other reason, deliberately: an account that failed for another cause is
     * not usable, and having one to hand is an invitation to store it - which is the bad write
     * behind issues #43 and #119.
     */
    private final transient PyObject account;

    public PythonAccountLoginException(String message) {
        this(message, REASON_UNKNOWN);
    }

    public PythonAccountLoginException(String message, String reason) {
        this(message, reason, null);
    }

    public PythonAccountLoginException(String message, String reason, PyObject account) {
        super(message);
        this.reason = reason == null || reason.isBlank() ? REASON_UNKNOWN : reason;
        this.account = account;
    }

    public PythonAccountLoginException(String message, Throwable cause) {
        super(message, cause);
        this.reason = REASON_UNKNOWN;
        this.account = null;
    }

    public PythonAccountLoginException(Throwable cause) {
        super(cause);
        this.reason = REASON_UNKNOWN;
        this.account = null;
    }

    /** Which kind of failure this was, for choosing what to show. Never null. */
    public String getReason() {
        return this.reason;
    }

    /** The account to agree to terms with, or null. See {@link #REASON_TERMS}. */
    public PyObject getAccount() {
        return this.account;
    }

    /** Whether this is a terms failure that can actually be recovered from in the app. */
    public boolean hasTermsToAccept() {
        return REASON_TERMS.equals(this.reason) && this.account != null;
    }
}
