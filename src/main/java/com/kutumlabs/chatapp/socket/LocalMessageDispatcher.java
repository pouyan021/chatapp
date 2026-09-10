package com.kutumlabs.chatapp.socket;

import com.kutumlabs.chatapp.chat.ChatModels.MessageView;
import com.kutumlabs.chatapp.chat.ChatModels.StoredMessage;
import com.kutumlabs.chatapp.chat.ChatStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class LocalMessageDispatcher implements MessageDispatcher {
    private final ChatStore store;
    private final ConnectionRegistry connections;
    private final SimpMessagingTemplate messaging;
    private final MeterRegistry metrics;

    public LocalMessageDispatcher(
            ChatStore store, ConnectionRegistry connections, SimpMessagingTemplate messaging, MeterRegistry metrics) {
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
                } catch (RuntimeException error) {
                    metrics.counter("chat.delivery.failures").increment();
                }
            }
        }
    }
}
