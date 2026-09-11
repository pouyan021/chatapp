package com.kutumlabs.chatapp.observability;

import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

@Component
public class StompObservations implements ExecutorChannelInterceptor {
    private final ChatTelemetry telemetry;
    private final ThreadLocal<Deque<ChatTelemetry.Scope>> scopes = ThreadLocal.withInitial(ArrayDeque::new);

    public StompObservations(ChatTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        var headers = StompHeaderAccessor.wrap(message);
        if (handler instanceof SimpAnnotationMethodMessageHandler && headers.getCommand() == StompCommand.SEND) {
            String operation =
                    switch (String.valueOf(headers.getDestination())) {
                        case "/app/v1/message.send" -> "message.send";
                        case "/app/v1/connection.info" -> "connection.info";
                        default -> "other";
                    };
            scopes.get()
                    .push(telemetry
                            .start("chat.stomp", operation)
                            .correlation(headers.getSessionId(), headers.getFirstNativeHeader("request-id")));
        }
        return message;
    }

    @Override
    public void afterMessageHandled(
            Message<?> message, MessageChannel channel, MessageHandler handler, Exception error) {
        if (!(handler instanceof SimpAnnotationMethodMessageHandler)
                || StompHeaderAccessor.wrap(message).getCommand() != StompCommand.SEND) return;
        var stack = scopes.get();
        if (stack.isEmpty()) {
            scopes.remove();
            return;
        }
        try (var scope = stack.pop()) {
            if (error != null) scope.error(error);
        } finally {
            if (stack.isEmpty()) scopes.remove();
        }
    }
}
