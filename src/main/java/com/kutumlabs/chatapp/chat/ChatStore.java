package com.kutumlabs.chatapp.chat;

import com.github.f4b6a3.ulid.Ulid;
import com.kutumlabs.chatapp.chat.ChatModels.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChatStore {
    void createChat(ChatView chat, Ulid owner);

    boolean isMember(Ulid chatId, Ulid userId);

    List<Ulid> participants(Ulid chatId);

    Reservation reserve(Reservation candidate);

    Optional<StoredMessage> findMessage(Ulid chatId, Instant createdAt, Ulid messageId);

    StoredMessage insertMessage(StoredMessage message);

    Page history(Ulid chatId, int size, byte[] pagingState);

    void saveUpload(UploadIntent intent, int ttlSeconds);

    Optional<UploadIntent> findUpload(Ulid uploadId);

    void touchDevice(Ulid userId, String deviceId, Instant now);

    Optional<ChatRef> findChat(Ulid chatId);

    void upsertSummary(Ulid userId, Ulid chatId, String chatName, Instant lastMessageAt, String lastMessagePreview);

    List<ChatSummaryView> listChats(Ulid userId, int limit);

    void markRead(Ulid userId, Ulid chatId, Ulid messageId, Instant readAt);

    void registerDevice(Ulid userId, String deviceId, String pushToken);
}
