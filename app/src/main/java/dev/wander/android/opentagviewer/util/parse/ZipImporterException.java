package dev.wander.android.opentagviewer.util.parse;

/**
 * An import that could not be completed.
 *
 * <p>Carries a {@link Reason}, because the person who hit this picked a file and the only
 * useful thing to tell them is what was wrong with it. Every failure used to arrive at the
 * same toast - "try to restart the app and retry" - which is advice for a broken app, and the
 * overwhelmingly likely cause is that they chose the wrong file. Someone who picked their
 * holiday photos was told to restart.
 *
 * <p>The message is for logcat and is not shown to anybody; the reason is what the UI reads.
 */
public class ZipImporterException extends RuntimeException {

    /**
     * What was wrong with the file, at the granularity a user can act on.
     *
     * <p>Deliberately not one per throw site. Splitting "the central directory is corrupt" from
     * "the manifest failed schema validation" would be honest and useless: the answer to both
     * is to export again.
     */
    public enum Reason {
        /** Not a zip at all - no zip signature. Usually the wrong file entirely. */
        NOT_A_ZIP,

        /** A real zip, but with no {@code OPENTAGVIEWER.yml}, so it is somebody else's zip. */
        NOT_AN_EXPORT,

        /** Ours, but unreadable: truncated, corrupt, or a format this version cannot parse. */
        DAMAGED,

        /**
         * Locked, and no code has been supplied yet.
         *
         * <p>Not a failure so much as a question. The exporter locks bundles by default, so
         * this is the ordinary path for a current export, and the caller is expected to ask
         * for the code and try again rather than show an error.
         */
        LOCKED,

        /** Locked, a code was supplied, and it was not the right one. */
        WRONG_PASSCODE,

        /** A valid export that carries no tags, so importing it would do nothing. */
        NO_TAGS,

        /** The file itself could not be opened - revoked permission, or it has since moved. */
        UNREADABLE,

        /** Anything not diagnosed. The generic message is the honest one here. */
        UNKNOWN
    }

    private final Reason reason;

    public ZipImporterException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ZipImporterException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public ZipImporterException(String message, Throwable cause) {
        this(Reason.UNKNOWN, message, cause);
    }

    public ZipImporterException(String message) {
        this(Reason.UNKNOWN, message);
    }

    public Reason getReason() {
        return this.reason;
    }

    /**
     * The reason behind a throwable, wherever it ended up in the cause chain.
     *
     * <p>Needed because this travels out through RxJava, which wraps what it is handed, so the
     * error the subscriber sees is rarely the one that was thrown.
     */
    public static Reason reasonOf(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ZipImporterException) {
                return ((ZipImporterException) t).getReason();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Reason.UNKNOWN;
    }
}
