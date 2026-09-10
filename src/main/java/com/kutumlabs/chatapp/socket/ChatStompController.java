package com.kutumlabs.chatapp.socket;

import com.kutumlabs.chatapp.chat.ChatModels.SendCommand;
import com.kutumlabs.chatapp.chat.ChatService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;

@Controller
public class ChatStompController {
    private final ChatService service;
    private final ConnectionRegistry connections;
    private final MessageDispatcher dispatcher;
    private final SimpMessagingTemplate messaging;
    private final MeterRegistry metrics;

    public ChatStompController(
            ChatService service,
            ConnectionRegistry connections,
            MessageDispatcher dispatcher,
            SimpMessagingTemplate messaging,
            MeterRegistry metrics) {
        this.service = service;
        this.connections = connections;
        this.dispatcher = dispatcher;
        this.messaging = messaging;
        this.metrics = metrics;
    }

    @MessageMapping("/v1/message.send")
    public void send(
            SendCommand command, @Header("simpSessionId") String sessionId, @Header("request-id") String requestId) {
        var identity = connections.identity(sessionId);
        SendResult result;
        try {
            var message = service.send(identity.userId(), command);
            result = SendResult.accepted(command.clientMessageId(), message);
            try {
                dispatcher.dispatch(message);
            } catch (RuntimeException error) {
                metrics.counter("chat.delivery.failures").increment();
            }
        } catch (RuntimeException error) {
            result = SendResult.failed(error);
        }
        reply(sessionId, "/queue/results", requestId, result);
    }

    @MessageMapping("/v1/connection.info")
    public void info(@Header("simpSessionId") String sessionId, @Header("request-id") String requestId) {
        reply(sessionId, "/queue/connection", requestId, connections.view(sessionId));
    }

    @MessageExceptionHandler
    public void invalid(Exception error, Message<?> message) {
        var headers = StompHeaderAccessor.wrap(message);
        reply(
                headers.getSessionId(),
                "/queue/results",
                headers.getFirstNativeHeader("request-id"),
                SendResult.failed(com.kutumlabs.chatapp.chat.ChatFailure.invalid("Invalid request payload")));
    }

    private void reply(String sessionId, String destination, String requestId, Object body) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setNativeHeader("request-id", requestId);
        headers.setLeaveMutable(true);
        // Address the session ID rather than the user so results never reach another device.
        messaging.convertAndSendToUser(sessionId, destination, body, headers.getMessageHeaders());
    }
}
