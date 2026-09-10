package com.kutumlabs.chatapp.chat;

import com.github.f4b6a3.ulid.Ulid;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ChatModels {
    private ChatModels() {}

    public record CreateChat(
            @Schema(example = "Alice and Bob") @NotBlank @Size(max = 200)
            String name,

            @Pattern(regexp = "DIRECT|GROUP", message = "must be DIRECT or GROUP")
            @Schema(
                    allowableValues = {"DIRECT", "GROUP"},
                    example = "DIRECT")
            String type,

            @Schema(
                    description = "Other participants; caller is added automatically",
                    example = "[\"01ARZ3NDEKTSV4RRFFQ69G5FAZ\"]")
            @NotEmpty
            @Size(max = 100)
            List<@NotBlank String> members) {}

    public record ChatView(String chatId, String name, String type, Instant createdAt, List<String> members) {}

    public record MediaReference(
            @NotBlank String uploadId,

            @NotBlank
            @Size(max = 1024)
            @Pattern(regexp = "^(?!null$).+", message = "must be an immutable object version ID")
            String versionId) {}

    public record SendCommand(
            @NotBlank String clientMessageId,
            @NotBlank String chatId,
            String text,
            @Valid MediaReference media) {}

    public record Reservation(
            Ulid chatId, Ulid senderId, Ulid clientMessageId, Ulid messageId, Instant createdAt, String payloadHash) {}

    public record StoredMessage(
            Ulid chatId,
            Instant createdAt,
            Ulid messageId,
            Ulid senderId,
            String contentType,
            String body,
            String mediaBucket,
            String mediaKey,
            String mediaVersionId,
            Long mediaSizeBytes) {}

    public record MediaView(String contentType, long sizeBytes) {}

    public record MessageView(
            String chatId, Instant createdAt, String messageId, String senderId, String text, MediaView media) {
        public static MessageView from(StoredMessage m) {
            return new MessageView(
                    m.chatId().toString(),
                    m.createdAt(),
                    m.messageId().toString(),
                    m.senderId().toString(),
                    m.body(),
                    m.mediaBucket() == null ? null : new MediaView(m.contentType(), m.mediaSizeBytes()));
        }
    }

    public record History(List<MessageView> messages, String nextCursor) {}

    public record Page(List<StoredMessage> messages, byte[] nextPage) {

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Page(List<StoredMessage> messages1, byte[] nextPage1))) return false;

            return Arrays.equals(nextPage, nextPage1) && Objects.equals(messages, messages1);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(messages);
            result = 31 * result + Arrays.hashCode(nextPage);
            return result;
        }

        @Override
        public String toString() {
            return "Page{" + "messages=" + messages + ", nextPage=" + Arrays.toString(nextPage) + '}';
        }
    }

    public record UploadRequest(
            @Schema(example = "1024") @Positive long sizeBytes,

            @NotBlank
            @Size(max = 128)
            @Pattern(
                    regexp = "[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+",
                    message = "must be a valid media content type")
            @Schema(example = "image/png")
            String contentType) {}

    public record UploadIntent(
            Ulid uploadId,
            Ulid userId,
            Ulid chatId,
            String bucket,
            String key,
            long sizeBytes,
            String contentType,
            Instant expiresAt) {}

    public record MediaObject(String bucket, String key, String versionId, long sizeBytes, String contentType) {}

    public record MessageLocator(
            @Schema(example = "01ARZ3NDEKTSV4RRFFQ69G5FAX") @NotBlank
            String messageId,

            @Schema(example = "2026-09-10T10:00:00Z") @NotNull
            Instant createdAt) {}

    public record ChatRef(String chatId, String name, String type, Instant createdAt) {}

    public record ChatSummaryView(String chatId, String chatName, String lastMessagePreview, Instant lastMessageAt) {}

    public record MarkReadCommand(
            @Schema(example = "01ARZ3NDEKTSV4RRFFQ69G5FAX") @NotBlank
            String messageId) {}

    public record RegisterDevice(
            @Schema(example = "demo-push-token") @NotBlank @Size(max = 4096)
            String pushToken) {}

    public static Ulid id(String value) {
        try {
            if (value == null || value.length() != 26) throw new IllegalArgumentException();
            return Ulid.from(value);
        } catch (IllegalArgumentException e) {
            throw ChatFailure.invalid("Expected a 26-character ULID");
        }
    }

    /** Runs Bean Validation constraints declared on {@code value} and rejects with {@link ChatFailure} on failure. */
    public static <T> void validate(Validator validator, T value) {
        var violations = validator.validate(value);
        if (violations.isEmpty()) return;
        String message = violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        throw ChatFailure.invalid(message);
    }
}
