package dev.wander.android.airtagforall.service.web;

public class UserCacheMissingDataException extends RuntimeException {

    public UserCacheMissingDataException(String message) {
        super(message);
    }

    public UserCacheMissingDataException(String message, Throwable cause) {
        super(message, cause);
    }

    public UserCacheMissingDataException(Throwable cause) {
        super(cause);
    }
}
