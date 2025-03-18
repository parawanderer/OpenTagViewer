package dev.wander.android.opentagviewer.db.repo;

public class RepoQueryException extends RuntimeException {
    public RepoQueryException(String message) {
        super(message);
    }

    public RepoQueryException(String message, Throwable cause) {
        super(message, cause);
    }

    public RepoQueryException(Throwable cause) {
        super(cause);
    }
}
