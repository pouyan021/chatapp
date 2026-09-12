package com.kutumlabs.chatapp.socket;

import com.kutumlabs.chatapp.chat.ChatModels.MessageView;
import com.kutumlabs.chatapp.chat.ChatModels.StoredMessage;
import com.kutumlabs.chatapp.chat.ChatStore;
import com.kutumlabs.chatapp.observability.ChatTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class BrokerMessageDispatcher implements MessageDispatcher {
    private static final Logger log = LoggerFactory.getLogger(BrokerMessageDispatcher.class);
    private final ChatTelemetry telemetry;
    private final ChatStore store;
    private final SimpMessagingTemplate messaging;
    private final MeterRegistry metrics;

    public BrokerMessageDispatcher(
            ChatStore store, SimpMessagingTemplate messaging, MeterRegistry metrics, ChatTelemetry telemetry) {
        this.telemetry = telemetry;
        this.store = store;
        this.messaging = messaging;
        this.metrics = metrics;
    }

    @Override
    public void dispatch(StoredMessage message) {
        var view = MessageView.from(message);
        for (var user : store.participants(message.chatId())) {
            // Addressing by user (not by session) lets UserDestinationMessageHandler fan this out to every session
            // for that user - including sessions held by other nodes, via the STOMP broker relay.
            try {
                messaging.convertAndSendToUser(user.toString(), "/queue/messages", view);
                metrics.counter("chat.delivery.submitted").increment();
                log.atDebug()
                        .addKeyValue("event", "dispatch.submitted")
                        .addKeyValue("messageId", message.messageId())
                        .addKeyValue("chatId", message.chatId())
                        .addKeyValue("targetUserId", user)
                        .log("Message submitted for user");
            } catch (RuntimeException error) {
                metrics.counter("chat.delivery.failures").increment();
                telemetry.failure("dispatch", error);
                log.atWarn()
                        .setCause(error)
                        .addKeyValue("event", "dispatch.failed")
                        .addKeyValue("messageId", message.messageId())
                        .addKeyValue("chatId", message.chatId())
                        .addKeyValue("targetUserId", user)
                        .log("User dispatch submission failed");
            }
        }
    }
}
