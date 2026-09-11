package com.kutumlabs.chatapp.observability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.f4b6a3.ulid.UlidCreator;
import com.kutumlabs.chatapp.chat.ChatModels.*;
import com.kutumlabs.chatapp.chat.ChatService;
import com.kutumlabs.chatapp.chat.ChatStore;
import com.kutumlabs.chatapp.socket.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class MessageFailureTelemetryTests {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ChatTelemetry telemetry = new ChatTelemetry(ObservationRegistry.create(), metrics);
    private final ChatService service = mock(ChatService.class);
    private final ConnectionRegistry connections = mock(ConnectionRegistry.class);
    private final MessageDispatcher dispatcher = mock(MessageDispatcher.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final ChatStompController controller =
            new ChatStompController(service, connections, dispatcher, messaging, metrics, telemetry);
    private final StoredMessage stored = new StoredMessage(
            UlidCreator.getMonotonicUlid(),
            Instant.now(),
            UlidCreator.getMonotonicUlid(),
            UlidCreator.getMonotonicUlid(),
            "text/plain",
            "private-message-body",
            null,
            null,
            null,
            null);

    @Test
    void acceptedMessageStaysAcceptedWhenDispatchFailsAndLogsNoPayload() {
        when(connections.identity("session")).thenReturn(new SessionIdentity(stored.senderId(), "device", Instant.MAX));
        when(service.send(any(), any())).thenReturn(stored);
        doThrow(new IllegalStateException("dispatch unavailable"))
                .when(dispatcher)
                .dispatch(stored);
        Logger logger = (Logger) LoggerFactory.getLogger(ChatStompController.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            controller.send(
                    new SendCommand(
                            UlidCreator.getMonotonicUlid().toString(),
                            stored.chatId().toString(),
                            stored.body(),
                            null),
                    "session",
                    "request");
            var result = ArgumentCaptor.forClass(Object.class);
            verify(messaging).convertAndSendToUser(eq("session"), eq("/queue/results"), result.capture(), anyMap());
            assertThat(((SendResult) result.getValue()).acceptance()).isNotNull();
            assertThat(metrics.get("chat.delivery.failures").counter().count()).isEqualTo(1);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getThrowableProxy()).isNotNull();
            assertThat(appender.list.getFirst().getFormattedMessage()).doesNotContain(stored.body());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void partialFanOutContinuesToOtherSessions() {
        ChatStore store = mock(ChatStore.class);
        when(store.participants(stored.chatId())).thenReturn(List.of(stored.senderId()));
        when(connections.list(stored.senderId()))
                .thenReturn(List.of(
                        new ConnectionRegistry.SessionView("broken", "one", Instant.EPOCH, Instant.EPOCH, Instant.MAX),
                        new ConnectionRegistry.SessionView(
                                "healthy", "two", Instant.EPOCH, Instant.EPOCH, Instant.MAX)));
        doThrow(new IllegalStateException("closed"))
                .when(messaging)
                .convertAndSendToUser(eq("broken"), anyString(), any(), anyMap());
        new LocalMessageDispatcher(store, connections, messaging, metrics, telemetry).dispatch(stored);
        verify(messaging).convertAndSendToUser(eq("healthy"), eq("/queue/messages"), any(), anyMap());
        assertThat(metrics.get("chat.delivery.submitted").counter().count()).isEqualTo(1);
        assertThat(metrics.get("chat.delivery.failures").counter().count()).isEqualTo(1);
    }

    @Test
    void malformedPayloadAndUnexpectedFailureHaveDifferentResults() {
        var headers = StompHeaderAccessor.create(StompCommand.SEND);
        headers.setSessionId("session");
        headers.setNativeHeader("request-id", "request");
        var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        controller.invalid(new MessageConversionException("private-payload"), message);
        controller.invalid(new IllegalStateException("unavailable"), message);
        var results = ArgumentCaptor.forClass(Object.class);
        verify(messaging, times(2))
                .convertAndSendToUser(eq("session"), eq("/queue/results"), results.capture(), anyMap());
        assertThat(((SendResult) results.getAllValues().get(0)).error().code()).isEqualTo("INVALID_REQUEST");
        assertThat(((SendResult) results.getAllValues().get(1)).error().code()).isEqualTo("TEMPORARILY_UNAVAILABLE");
    }

    @Test
    void failedReplyDoesNotRecurseOrUndoAcceptance() {
        when(connections.identity("session")).thenReturn(new SessionIdentity(stored.senderId(), "device", Instant.MAX));
        when(service.send(any(), any())).thenReturn(stored);
        doThrow(new IllegalStateException("channel closed"))
                .when(messaging)
                .convertAndSendToUser(anyString(), anyString(), any(), anyMap());
        controller.send(
                new SendCommand(
                        UlidCreator.getMonotonicUlid().toString(),
                        stored.chatId().toString(),
                        stored.body(),
                        null),
                "session",
                "request");
        verify(service, times(1)).send(any(), any());
        verify(messaging, times(1)).convertAndSendToUser(anyString(), anyString(), any(), anyMap());
        assertThat(metrics.get("chat.reply.failures").counter().count()).isEqualTo(1);
    }
}
