package dev.wander.android.opentagviewer.python;

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

    /** Anything not recognised. The detail is shown as-is rather than guessed at. */
    public static final String REASON_UNKNOWN = "unknown";

    private final String reason;

    public PythonAccountLoginException(String message) {
        this(message, REASON_UNKNOWN);
    }

    public PythonAccountLoginException(String message, String reason) {
        super(message);
        this.reason = reason == null || reason.isBlank() ? REASON_UNKNOWN : reason;
    }

    public PythonAccountLoginException(String message, Throwable cause) {
        super(message, cause);
        this.reason = REASON_UNKNOWN;
    }

    public PythonAccountLoginException(Throwable cause) {
        super(cause);
        this.reason = REASON_UNKNOWN;
    }

    /** Which kind of failure this was, for choosing what to show. Never null. */
    public String getReason() {
        return this.reason;
    }
}
