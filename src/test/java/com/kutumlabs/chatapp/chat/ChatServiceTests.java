package com.kutumlabs.chatapp.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.f4b6a3.ulid.Ulid;
import com.github.f4b6a3.ulid.UlidCreator;
import com.kutumlabs.chatapp.chat.ChatModels.*;
import com.kutumlabs.chatapp.media.MediaService;
import com.kutumlabs.chatapp.support.MutableClock;
import com.kutumlabs.chatapp.support.TestSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

class ChatServiceTests {
    private final ChatStore store = mock(ChatStore.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    private final MediaService media = mock(MediaService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    private final MutableClock clock = new MutableClock();
    private final Ulid user = UlidCreator.getMonotonicUlid();
    private final Ulid chat = UlidCreator.getMonotonicUlid();
    private final AtomicReference<Reservation> reservation = new AtomicReference<>();
    private ChatService service;

    @BeforeEach
    void setUp() {
        service = service();
        when(store.isMember(chat, user)).thenReturn(true);
        when(store.reserve(any())).thenAnswer(call -> {
            reservation.compareAndSet(null, call.getArgument(0));
            return reservation.get();
        });
        when(store.findChat(any()))
                .thenReturn(Optional.of(new ChatRef(chat.toString(), "General", "GROUP", Instant.EPOCH)));
    }

    private ChatService service() {
        return new ChatService(
                store,
                new MembershipService(store),
                media,
                new HistoryCursor(),
                TestSettings.properties(256),
                new SimpleMeterRegistry(),
                clock,
                TestSettings.validator());
    }

    private SendCommand command(String clientId, String text) {
        return new SendCommand(clientId, chat.toString(), text, null);
    }

    @Test
    void retryAfterUncertainWriteReusesCanonicalIdentityAcrossServiceInstances() {
        var command = command(UlidCreator.getMonotonicUlid().toString(), "hello");
        AtomicReference<StoredMessage> persisted = new AtomicReference<>();
        when(store.findMessage(any(), any(), any())).thenAnswer(_ -> Optional.ofNullable(persisted.get()));
        when(store.insertMessage(any())).thenAnswer(call -> {
            persisted.set(call.getArgument(0));
            throw new IllegalStateException("Connection lost after Cassandra committed");
        });
        assertThatThrownBy(() -> service.send(user, command)).isInstanceOf(IllegalStateException.class);

        service = service();
        StoredMessage message = service.send(user, command);
        assertThat(message.messageId()).isEqualTo(reservation.get().messageId());
        assertThat(message).isEqualTo(persisted.get());
        verify(store, times(1)).insertMessage(any());
    }

    @Test
    void retryAfterReservationFailureCompletesTheOriginalMessage() {
        String clientId = UlidCreator.getMonotonicUlid().toString();
        when(store.findMessage(any(), any(), any())).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("Unavailable")).when(store).insertMessage(any());
        assertThatThrownBy(() -> service.send(user, command(clientId, "hello")))
                .isInstanceOf(IllegalStateException.class);

        var original = reservation.get();
        doAnswer(call -> call.getArgument(0)).when(store).insertMessage(any());
        StoredMessage message = service.send(user, command(clientId, "hello"));
        assertThat(message.messageId()).isEqualTo(original.messageId());
        assertThat(message.createdAt()).isEqualTo(original.createdAt());

        assertThatThrownBy(() -> service.send(user, command(clientId, "changed")))
                .isInstanceOfSatisfying(
                        ChatFailure.class, error -> assertThat(error.code()).isEqualTo("CONFLICT"));
    }

    @Test
    void nonmemberCannotReserveOrPersistAndTextLimitCountsUtf8Bytes() {
        when(store.isMember(chat, user)).thenReturn(false);
        assertThatThrownBy(() -> service.send(
                        user, command(UlidCreator.getMonotonicUlid().toString(), "hello")))
                .isInstanceOf(ChatFailure.class);
        verify(store, never()).reserve(any());

        assertThatThrownBy(() -> service.send(
                        user, command(UlidCreator.getMonotonicUlid().toString(), "🙂".repeat(2049))))
                .isInstanceOf(ChatFailure.class);
    }

    @Test
    void createSeedsChatSummaryForEveryMember() {
        var request = new CreateChat(
                "Team",
                "GROUP",
                List.of(
                        UlidCreator.getMonotonicUlid().toString(),
                        UlidCreator.getMonotonicUlid().toString()));

        ChatView created = service.create(user, request);

        Ulid chatId = ChatModels.id(created.chatId());
        verify(store, times(3)).upsertSummary(any(), eq(chatId), eq("Team"), eq(created.createdAt()), isNull());
    }

    @Test
    void sendFansOutSummaryToEveryParticipant() {
        var command = command(UlidCreator.getMonotonicUlid().toString(), "hello there");
        when(store.findMessage(any(), any(), any())).thenReturn(Optional.empty());
        when(store.insertMessage(any())).thenAnswer(call -> call.getArgument(0));
        Ulid other = UlidCreator.getMonotonicUlid();
        when(store.participants(chat)).thenReturn(List.of(user, other));

        StoredMessage message = service.send(user, command);

        verify(store).upsertSummary(user, chat, "General", message.createdAt(), "hello there");
        verify(store).upsertSummary(other, chat, "General", message.createdAt(), "hello there");
    }

    @Test
    void listChatsDelegatesToStore() {
        var summaries = List.of(new ChatSummaryView(chat.toString(), "General", "hi", Instant.EPOCH));
        when(store.listChats(user, 50)).thenReturn(summaries);

        assertThat(service.listChats(user)).isEqualTo(summaries);
    }

    @Test
    void markReadRequiresMembershipAndDelegates() {
        var messageId = UlidCreator.getMonotonicUlid();
        service.markRead(user, chat, new MarkReadCommand(messageId.toString()));
        verify(store).markRead(eq(user), eq(chat), eq(messageId), any());

        when(store.isMember(chat, user)).thenReturn(false);
        assertThatThrownBy(() -> service.markRead(user, chat, new MarkReadCommand(messageId.toString())))
                .isInstanceOf(ChatFailure.class);
    }

    @Test
    void registerDeviceValidatesAndDelegates() {
        service.registerDevice(user, "phone-1", new RegisterDevice("token-abc"));
        verify(store).registerDevice(user, "phone-1", "token-abc");

        assertThatThrownBy(() -> service.registerDevice(user, "phone-1", new RegisterDevice(" ")))
                .isInstanceOf(ChatFailure.class);
    }
}
