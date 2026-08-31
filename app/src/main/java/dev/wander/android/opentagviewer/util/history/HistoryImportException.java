package dev.wander.android.opentagviewer.util.history;

import lombok.Getter;

/** A whole-archive failure, distinct from one malformed row that can be skipped. */
@Getter
public final class HistoryImportException extends Exception {
    public enum Reason {
        UNSUPPORTED_LEGACY,
        INVALID_ARCHIVE,
        READ_FAILED,
        DATABASE_FAILED,
    }

    private final Reason reason;

    public HistoryImportException(final Reason reason, final String message) {
        super(message);
        this.reason = reason;
    }

    public HistoryImportException(
            final Reason reason,
            final String message,
            final Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }
}
