package com.kutumlabs.chatapp.chat;

import org.springframework.http.HttpStatus;

public class ChatFailure extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final boolean retryable;

    public ChatFailure(String code, HttpStatus status, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public static ChatFailure invalid(String message) {
        return new ChatFailure("INVALID_REQUEST", HttpStatus.BAD_REQUEST, message, false);
    }

    public static ChatFailure forbidden() {
        return new ChatFailure("FORBIDDEN", HttpStatus.FORBIDDEN, "Chat membership is required", false);
    }

    public static ChatFailure missing() {
        return new ChatFailure("NOT_FOUND", HttpStatus.NOT_FOUND, "Resource not found", false);
    }

    public static ChatFailure conflict(String message) {
        return new ChatFailure("CONFLICT", HttpStatus.CONFLICT, message, false);
    }
}
