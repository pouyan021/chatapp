package com.kutumlabs.chatapp.chat;

import static com.datastax.oss.driver.api.querybuilder.QueryBuilder.bindMarker;
import static com.datastax.oss.driver.api.querybuilder.QueryBuilder.insertInto;
import static com.datastax.oss.driver.api.querybuilder.QueryBuilder.selectFrom;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.github.f4b6a3.ulid.Ulid;
import com.kutumlabs.chatapp.chat.ChatModels.*;
import com.kutumlabs.chatapp.entity.Chat;
import com.kutumlabs.chatapp.entity.ChatParticipant;
import com.kutumlabs.chatapp.entity.ChatParticipantKey;
import com.kutumlabs.chatapp.entity.ChatReadState;
import com.kutumlabs.chatapp.entity.ChatReadStateKey;
import com.kutumlabs.chatapp.entity.ChatSummary;
import com.kutumlabs.chatapp.entity.ChatSummaryKey;
import com.kutumlabs.chatapp.entity.Message;
import com.kutumlabs.chatapp.entity.MessageKey;
import com.kutumlabs.chatapp.entity.UserDevice;
import com.kutumlabs.chatapp.entity.UserDeviceKey;
import com.kutumlabs.chatapp.repository.ChatParticipantRepository;
import com.kutumlabs.chatapp.repository.ChatReadStateRepository;
import com.kutumlabs.chatapp.repository.ChatRepository;
import com.kutumlabs.chatapp.repository.ChatSummaryRepository;
import com.kutumlabs.chatapp.repository.MessageRepository;
import com.kutumlabs.chatapp.repository.UserDeviceRepository;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Repository;

@Repository
public class CassandraChatStore implements ChatStore {
    private static final Duration SUMMARY_TTL = Duration.ofDays(30);

    private final CqlSession session;
    private final CassandraOperations cassandraOperations;
    private final ChatRepository chatRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final MessageRepository messageRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final ChatSummaryRepository chatSummaryRepository;
    private final ChatReadStateRepository chatReadStateRepository;

    public CassandraChatStore(
            CqlSession session,
            CassandraOperations cassandraOperations,
            ChatRepository chatRepository,
            ChatParticipantRepository chatParticipantRepository,
            MessageRepository messageRepository,
            UserDeviceRepository userDeviceRepository,
            ChatSummaryRepository chatSummaryRepository,
            ChatReadStateRepository chatReadStateRepository) {
        this.session = session;
        this.cassandraOperations = cassandraOperations;
        this.chatRepository = chatRepository;
        this.chatParticipantRepository = chatParticipantRepository;
        this.messageRepository = messageRepository;
        this.userDeviceRepository = userDeviceRepository;
        this.chatSummaryRepository = chatSummaryRepository;
        this.chatReadStateRepository = chatReadStateRepository;
    }

    // send_reservations and media_uploads have no Spring Data entity; they stay on the driver directly
    // because both need INSERT ... IF NOT EXISTS / USING TTL, which plain repositories can't express.
    private ResultSet execute(SimpleStatement statement) {
        return session.execute(statement
                .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM)
                .setSerialConsistencyLevel(DefaultConsistencyLevel.LOCAL_SERIAL));
    }

    private static ByteBuffer blob(Ulid id) {
        return ByteBuffer.wrap(id.toBytes());
    }

    private static Ulid id(Row row, String column) {
        ByteBuffer stored = row.getByteBuffer(column);
        if (stored == null) throw new IllegalStateException("Column " + column + " must not be null");
        ByteBuffer buffer = stored.duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return Ulid.from(bytes);
    }

    private static StoredMessage toStoredMessage(Message m) {
        return new StoredMessage(
                m.getKey().getChatId(),
                m.getKey().getCreatedAt(),
                m.getKey().getMessageId(),
                m.getSenderId(),
                m.getContentType(),
                m.getBody(),
                m.getMediaBucket(),
                m.getMediaKey(),
                m.getMediaVersionId(),
                m.getMediaSizeBytes());
    }

    private static Message toEntity(StoredMessage m) {
        return new Message(
                new MessageKey(m.chatId(), m.createdAt(), m.messageId()),
                m.senderId(),
                m.contentType(),
                m.body(),
                m.mediaBucket(),
                m.mediaKey(),
                m.mediaSizeBytes(),
                m.mediaVersionId());
    }

    @Override
    public void createChat(ChatView chat, Ulid owner) {
        Ulid chatId = ChatModels.id(chat.chatId());
        // Publish the chat row last. Authorization also requires this row.
        for (String member : chat.members()) {
            chatParticipantRepository.save(new ChatParticipant(
                    new ChatParticipantKey(chatId, ChatModels.id(member)),
                    chat.createdAt(),
                    member.equals(owner.toString()) ? "OWNER" : "MEMBER"));
        }
        chatRepository.save(new Chat(chatId, chat.name(), chat.type(), chat.createdAt()));
    }

    @Override
    public boolean isMember(Ulid chatId, Ulid userId) {
        if (!chatRepository.existsById(chatId)) return false;
        return chatParticipantRepository.existsById(new ChatParticipantKey(chatId, userId));
    }

    @Override
    public List<Ulid> participants(Ulid chatId) {
        return chatParticipantRepository.findByKeyChatId(chatId).stream()
                .map(p -> p.getKey().getUserId())
                .toList();
    }

    @Override
    public Reservation reserve(Reservation candidate) {
        var result = execute(insertInto("send_reservations")
                .value("chat_id", bindMarker())
                .value("sender_id", bindMarker())
                .value("client_message_id", bindMarker())
                .value("message_id", bindMarker())
                .value("created_at", bindMarker())
                .value("payload_hash", bindMarker())
                .ifNotExists()
                .build(
                        blob(candidate.chatId()),
                        blob(candidate.senderId()),
                        blob(candidate.clientMessageId()),
                        blob(candidate.messageId()),
                        candidate.createdAt(),
                        candidate.payloadHash()));
        if (result.wasApplied()) return candidate;
        Row row = execute(selectFrom("send_reservations")
                        .columns("message_id", "created_at", "payload_hash")
                        .whereColumn("chat_id")
                        .isEqualTo(bindMarker())
                        .whereColumn("sender_id")
                        .isEqualTo(bindMarker())
                        .whereColumn("client_message_id")
                        .isEqualTo(bindMarker())
                        .build(blob(candidate.chatId()), blob(candidate.senderId()), blob(candidate.clientMessageId())))
                .one();
        if (row == null) throw new IllegalStateException("Missing send reservation");
        return new Reservation(
                candidate.chatId(),
                candidate.senderId(),
                candidate.clientMessageId(),
                id(row, "message_id"),
                row.getInstant("created_at"),
                row.getString("payload_hash"));
    }

    @Override
    public Optional<StoredMessage> findMessage(Ulid chatId, Instant createdAt, Ulid messageId) {
        return messageRepository
                .findById(new MessageKey(chatId, createdAt, messageId))
                .map(CassandraChatStore::toStoredMessage);
    }

    @Override
    public StoredMessage insertMessage(StoredMessage m) {
        var result = cassandraOperations.insert(
                toEntity(m), InsertOptions.builder().withIfNotExists().build());
        if (result.wasApplied()) return m;
        return findMessage(m.chatId(), m.createdAt(), m.messageId())
                .orElseThrow(() -> new IllegalStateException("Missing committed message"));
    }

    @Override
    public Page history(Ulid chatId, int size, byte[] pagingState) {
        Pageable pageable = pagingState == null
                ? CassandraPageRequest.first(size)
                : CassandraPageRequest.of(Pageable.ofSize(size), ByteBuffer.wrap(pagingState));
        Slice<Message> slice = messageRepository.findByKeyChatId(chatId, pageable);
        List<StoredMessage> messages = slice.getContent().stream()
                .map(CassandraChatStore::toStoredMessage)
                .toList();
        byte[] next = null;
        if (slice.hasNext() && slice.nextPageable() instanceof CassandraPageRequest nextPage) {
            ByteBuffer buffer = nextPage.getPagingState();
            if (buffer != null) {
                next = new byte[buffer.remaining()];
                buffer.duplicate().get(next);
            }
        }
        return new Page(messages, next);
    }

    @Override
    public void saveUpload(UploadIntent intent, int ttlSeconds) {
        execute(insertInto("media_uploads")
                .value("upload_id", bindMarker())
                .value("user_id", bindMarker())
                .value("chat_id", bindMarker())
                .value("bucket", bindMarker())
                .value("object_key", bindMarker())
                .value("size_bytes", bindMarker())
                .value("content_type", bindMarker())
                .value("expires_at", bindMarker())
                .usingTtl(bindMarker())
                .build(
                        blob(intent.uploadId()),
                        blob(intent.userId()),
                        blob(intent.chatId()),
                        intent.bucket(),
                        intent.key(),
                        intent.sizeBytes(),
                        intent.contentType(),
                        intent.expiresAt(),
                        ttlSeconds));
    }

    @Override
    public Optional<UploadIntent> findUpload(Ulid uploadId) {
        return Optional.ofNullable(execute(selectFrom("media_uploads")
                                .all()
                                .whereColumn("upload_id")
                                .isEqualTo(bindMarker())
                                .build(blob(uploadId)))
                        .one())
                .map(row -> new UploadIntent(
                        uploadId,
                        id(row, "user_id"),
                        id(row, "chat_id"),
                        row.getString("bucket"),
                        row.getString("object_key"),
                        row.getLong("size_bytes"),
                        row.getString("content_type"),
                        row.getInstant("expires_at")));
    }

    @Override
    public void touchDevice(Ulid userId, String deviceId, Instant now) {
        // Passing null for pushToken relies on Spring Data Cassandra skipping null fields on insert
        // (InsertOptions.insertNulls defaults to false), so an existing push token is left untouched.
        userDeviceRepository.save(new UserDevice(new UserDeviceKey(userId, deviceId), null, now));
    }

    @Override
    public Optional<ChatRef> findChat(Ulid chatId) {
        return chatRepository
                .findById(chatId)
                .map(c -> new ChatRef(c.getChatId().toString(), c.getName(), c.getType(), c.getCreatedAt()));
    }

    @Override
    public void upsertSummary(
            Ulid userId, Ulid chatId, String chatName, Instant lastMessageAt, String lastMessagePreview) {
        var summary = new ChatSummary(new ChatSummaryKey(userId, lastMessageAt, chatId), chatName, lastMessagePreview);
        cassandraOperations.insert(
                summary, InsertOptions.builder().ttl(SUMMARY_TTL).build());
    }

    @Override
    public List<ChatSummaryView> listChats(Ulid userId, int limit) {
        // (user_id, last_message_at DESC, chat_id) can't be updated in place when the last message
        // changes, so each new message appends a fresh row (see upsertSummary) instead of replacing
        // one. Rows are TTL'd; de-duplicate by chat_id here, keeping the newest (first) occurrence.
        Map<Ulid, ChatSummaryView> uniqueByChat = new LinkedHashMap<>();
        for (ChatSummary summary : chatSummaryRepository.findByKeyUserId(userId)) {
            Ulid chatId = summary.getKey().getChatId();
            if (uniqueByChat.containsKey(chatId)) continue;
            uniqueByChat.put(
                    chatId,
                    new ChatSummaryView(
                            chatId.toString(),
                            summary.getChatName(),
                            summary.getLastMessagePreview(),
                            summary.getKey().getLastMessageAt()));
            if (uniqueByChat.size() == limit) break;
        }
        return List.copyOf(uniqueByChat.values());
    }

    @Override
    public void markRead(Ulid userId, Ulid chatId, Ulid messageId, Instant readAt) {
        chatReadStateRepository.save(new ChatReadState(new ChatReadStateKey(userId, chatId), messageId, readAt));
    }

    @Override
    public void registerDevice(Ulid userId, String deviceId, String pushToken) {
        userDeviceRepository.save(new UserDevice(new UserDeviceKey(userId, deviceId), pushToken, null));
    }
}
