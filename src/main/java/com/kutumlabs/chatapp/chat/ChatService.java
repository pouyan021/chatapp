package com.kutumlabs.chatapp.chat;

import com.github.f4b6a3.ulid.Ulid;
import com.github.f4b6a3.ulid.UlidCreator;
import com.kutumlabs.chatapp.chat.ChatModels.*;
import com.kutumlabs.chatapp.config.ChatProperties;
import com.kutumlabs.chatapp.media.MediaService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private final ChatStore store;
    private final MembershipService membership;
    private final MediaService media;
    private final HistoryCursor cursors;
    private final ChatProperties properties;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final Validator validator;

    public ChatService(
            ChatStore store,
            MembershipService membership,
            MediaService media,
            HistoryCursor cursors,
            ChatProperties properties,
            MeterRegistry metrics,
            Clock clock,
            Validator validator) {
        this.store = store;
        this.membership = membership;
        this.media = media;
        this.cursors = cursors;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.validator = validator;
    }

    public ChatView create(Ulid user, CreateChat request) {
        ChatModels.validate(validator, request);
        boolean direct = "DIRECT".equals(request.type());
        var members = new LinkedHashSet<String>();
        members.add(user.toString());
        request.members().forEach(id -> members.add(ChatModels.id(id).toString()));
        if (members.size() < 2 || members.size() > 100 || (direct && members.size() != 2)) {
            throw ChatFailure.invalid("DIRECT requires two members; GROUP requires 2-100");
        }
        var chat = new ChatView(
                UlidCreator.getMonotonicUlid().toString(),
                request.name().strip(),
                request.type(),
                now(),
                members.stream().toList());
        store.createChat(chat, user);
        Ulid chatId = ChatModels.id(chat.chatId());
        for (String member : members) {
            store.upsertSummary(ChatModels.id(member), chatId, chat.name(), chat.createdAt(), null);
        }
        return chat;
    }

    public List<ChatSummaryView> listChats(Ulid user) {
        return store.listChats(user, 50);
    }

    public void markRead(Ulid user, Ulid chat, MarkReadCommand command) {
        ChatModels.validate(validator, command);
        Ulid messageId = ChatModels.id(command.messageId());
        membership.require(chat, user);
        store.markRead(user, chat, messageId, now());
    }

    public void registerDevice(Ulid user, String deviceId, RegisterDevice command) {
        ChatModels.validate(validator, command);
        store.registerDevice(user, deviceId, command.pushToken());
    }

    public History history(Ulid user, Ulid chat, int size, String cursor) {
        if (size < 1 || size > 100) throw ChatFailure.invalid("History limit must be between 1 and 100");
        byte[] pagingState = cursors.decode(chat, size, cursor);
        membership.require(chat, user);
        Page page = store.history(chat, size, pagingState);
        return new History(
                page.messages().stream().map(MessageView::from).toList(), cursors.encode(chat, size, page.nextPage()));
    }

    public StoredMessage send(Ulid user, SendCommand command) {
        try {
            validateSend(command);
            Ulid chat = ChatModels.id(command.chatId());
            Ulid clientId = ChatModels.id(command.clientMessageId());
            String hash = payloadHash(command);
            membership.require(chat, user);
            Reservation reservation =
                    store.reserve(new Reservation(chat, user, clientId, UlidCreator.getMonotonicUlid(), now(), hash));
            if (!Objects.equals(reservation.payloadHash(), hash)) {
                throw ChatFailure.conflict("clientMessageId was already used with different content");
            }
            StoredMessage message = store.findMessage(chat, reservation.createdAt(), reservation.messageId())
                    .orElseGet(() -> persist(user, command, reservation));
            metrics.counter("chat.messages.accepted").increment();
            return message;
        } catch (RuntimeException error) {
            metrics.counter("chat.messages.rejected").increment();
            throw error;
        }
    }

    private StoredMessage persist(Ulid user, SendCommand command, Reservation reservation) {
        StoredMessage message;
        if (command.media() == null) {
            message = store.insertMessage(new StoredMessage(
                    reservation.chatId(),
                    reservation.createdAt(),
                    reservation.messageId(),
                    user,
                    "text/plain",
                    command.text(),
                    null,
                    null,
                    null,
                    null));
        } else {
            var object = media.verify(user, reservation.chatId(), command.media());
            message = store.insertMessage(new StoredMessage(
                    reservation.chatId(),
                    reservation.createdAt(),
                    reservation.messageId(),
                    user,
                    object.contentType(),
                    null,
                    object.bucket(),
                    object.key(),
                    object.versionId(),
                    object.sizeBytes()));
        }
        fanOutSummary(message);
        return message;
    }

    private void fanOutSummary(StoredMessage message) {
        var chat = store.findChat(message.chatId())
                .orElseThrow(() -> new IllegalStateException("Missing chat for delivered message"));
        String preview = message.mediaBucket() != null ? "[media]" : truncate(message.body());
        for (Ulid participant : store.participants(message.chatId())) {
            store.upsertSummary(participant, message.chatId(), chat.name(), message.createdAt(), preview);
        }
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= 200) return text;
        return text.substring(0, 200);
    }

    private void validateSend(SendCommand command) {
        if (command == null) throw ChatFailure.invalid("A command is required");
        ChatModels.validate(validator, command);
        if ((command.text() == null) == (command.media() == null)) {
            throw ChatFailure.invalid("Provide exactly one of text or media");
        }
        if (command.text() != null
                && (command.text().isBlank()
                        || command.text().getBytes(StandardCharsets.UTF_8).length
                                > properties.socket().maxTextBytes())) {
            throw ChatFailure.invalid("Text must be nonblank and within the configured byte limit");
        }
    }

    static String payloadHash(SendCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[] {
                command.text(),
                command.media() == null
                        ? null
                        : ChatModels.id(command.media().uploadId()).toString(),
                command.media() == null ? null : command.media().versionId()
            }) {
                byte[] bytes = field == null ? new byte[0] : field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4)
                        .putInt(field == null ? -1 : bytes.length)
                        .array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
