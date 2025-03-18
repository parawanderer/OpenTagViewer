package dev.wander.android.opentagviewer.python;

public class PythonAccountLoginException extends RuntimeException {
    public PythonAccountLoginException(String message) {
        super(message);
    }

    public PythonAccountLoginException(String message, Throwable cause) {
        super(message, cause);
    }

    public PythonAccountLoginException(Throwable cause) {
        super(cause);
    }
}
