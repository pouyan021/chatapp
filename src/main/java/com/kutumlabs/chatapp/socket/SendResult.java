package com.kutumlabs.chatapp.socket;

import com.kutumlabs.chatapp.chat.ChatFailure;
import com.kutumlabs.chatapp.chat.ChatModels.StoredMessage;
import java.time.Instant;

public record SendResult(Acceptance acceptance, Error error) {
    public record Acceptance(String clientMessageId, String messageId, Instant createdAt, String chatId) {}

    public record Error(String code, String message, boolean retryable) {}

    public SendResult {
        if ((acceptance == null) == (error == null))
            throw new IllegalArgumentException("Exactly one result is required");
    }

    public static SendResult accepted(String clientMessageId, StoredMessage message) {
        return new SendResult(
                new Acceptance(
                        clientMessageId,
                        message.messageId().toString(),
                        message.createdAt(),
                        message.chatId().toString()),
                null);
    }

    public static SendResult failed(Throwable failure) {
        if (failure instanceof ChatFailure error) {
            return new SendResult(null, new Error(error.code(), error.getMessage(), error.retryable()));
        }
        return new SendResult(
                null,
                new Error(
                        "TEMPORARILY_UNAVAILABLE",
                        "The operation could not be confirmed; retry with the same clientMessageId",
                        true));
    }
}
