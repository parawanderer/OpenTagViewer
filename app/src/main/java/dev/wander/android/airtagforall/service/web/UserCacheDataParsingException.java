package dev.wander.android.airtagforall.service.web;

public class UserCacheDataParsingException extends RuntimeException {
    public UserCacheDataParsingException(String message) {
        super(message);
    }

    public UserCacheDataParsingException(String message, Throwable cause) {
        super(message, cause);
    }

    public UserCacheDataParsingException(Throwable cause) {
        super(cause);
    }
}
