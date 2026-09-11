package com.kutumlabs.chatapp.socket;

import com.kutumlabs.chatapp.chat.ChatModels.MessageView;
import com.kutumlabs.chatapp.chat.ChatModels.StoredMessage;
import com.kutumlabs.chatapp.chat.ChatStore;
import com.kutumlabs.chatapp.observability.ChatTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class LocalMessageDispatcher implements MessageDispatcher {
    private static final Logger log = LoggerFactory.getLogger(LocalMessageDispatcher.class);
    private final ChatTelemetry telemetry;
    private final ChatStore store;
    private final ConnectionRegistry connections;
    private final SimpMessagingTemplate messaging;
    private final MeterRegistry metrics;

    public LocalMessageDispatcher(
            ChatStore store,
            ConnectionRegistry connections,
            SimpMessagingTemplate messaging,
            MeterRegistry metrics,
            ChatTelemetry telemetry) {
        this.telemetry = telemetry;
        this.store = store;
        this.connections = connections;
        this.messaging = messaging;
        this.metrics = metrics;
    }

    @Override
    public void dispatch(StoredMessage message) {
        var view = MessageView.from(message);
        for (var user : store.participants(message.chatId())) {
            // Target each session individually: convertAndSendToUser() fans one shared Message out to every
            // session for a user, and OrderedMessageChannelDecorator locks its headers after the first session,
            // dropping delivery to the rest.
            for (var session : connections.list(user)) {
                try {
                    var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
                    headers.setSessionId(session.sessionId());
                    headers.setLeaveMutable(true);
                    messaging.convertAndSendToUser(
                            session.sessionId(), "/queue/messages", view, headers.getMessageHeaders());
                    metrics.counter("chat.delivery.submitted").increment();
                    log.atDebug()
                            .addKeyValue("event", "dispatch.submitted")
                            .addKeyValue("messageId", message.messageId())
                            .addKeyValue("chatId", message.chatId())
                            .addKeyValue("targetSessionId", session.sessionId())
                            .log("Message submitted to session");
                } catch (RuntimeException error) {
                    metrics.counter("chat.delivery.failures").increment();
                    telemetry.failure("dispatch", error);
                    log.atWarn()
                            .setCause(error)
                            .addKeyValue("event", "dispatch.failed")
                            .addKeyValue("messageId", message.messageId())
                            .addKeyValue("chatId", message.chatId())
                            .addKeyValue("targetSessionId", session.sessionId())
                            .log("Session dispatch submission failed");
                }
            }
        }
    }
}
