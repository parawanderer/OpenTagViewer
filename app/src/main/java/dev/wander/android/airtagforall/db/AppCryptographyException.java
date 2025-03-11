package dev.wander.android.airtagforall.db;

public class AppCryptographyException extends RuntimeException {
    public AppCryptographyException(String message) {
        super(message);
    }

    public AppCryptographyException(String message, Throwable cause) {
        super(message, cause);
    }

    public AppCryptographyException(Throwable cause) {
        super(cause);
    }
}
