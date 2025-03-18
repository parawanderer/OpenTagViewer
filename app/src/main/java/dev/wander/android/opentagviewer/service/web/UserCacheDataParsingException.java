package dev.wander.android.opentagviewer.service.web;

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
