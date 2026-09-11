package com.kutumlabs.chatapp.socket;

import com.kutumlabs.chatapp.chat.ChatFailure;
import com.kutumlabs.chatapp.chat.ChatModels.SendCommand;
import com.kutumlabs.chatapp.chat.ChatModels.StoredMessage;
import com.kutumlabs.chatapp.chat.ChatService;
import com.kutumlabs.chatapp.observability.ChatTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;

@Controller
public class ChatStompController {

    private static final Logger log = LoggerFactory.getLogger(ChatStompController.class);
    public static final String EVENT = "event";
    public static final String CHAT_ID = "chatId";
    public static final String MESSAGE_ID = "messageId";
    private final ChatTelemetry telemetry;
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
            MeterRegistry metrics,
            ChatTelemetry telemetry) {
        this.telemetry = telemetry;
        this.service = service;
        this.connections = connections;
        this.dispatcher = dispatcher;
        this.messaging = messaging;
        this.metrics = metrics;
    }

    @MessageMapping("/v1/message.send")
    public void send(
            SendCommand command, @Header("simpSessionId") String sessionId, @Header("request-id") String requestId) {
        SendResult result;
        try {
            var identity = connections.identity(sessionId);
            var message = service.send(identity.userId(), command);
            log.atDebug()
                    .addKeyValue(EVENT, "message.accepted")
                    .addKeyValue(CHAT_ID, message.chatId())
                    .addKeyValue(MESSAGE_ID, message.messageId())
                    .log("Message accepted");
            result = SendResult.accepted(command.clientMessageId(), message);
            dispatch(message);
        } catch (RuntimeException error) {
            telemetry.failure("send", error);
            if (error instanceof ChatFailure) {
                log.atDebug().addKeyValue("code", ChatTelemetry.code(error)).log("Message rejected");
            } else {
                log.atError()
                        .setCause(error)
                        .addKeyValue(EVENT, "message.failed")
                        .log("Message processing failed");
            }
            result = SendResult.failed(error);
        }
        reply(sessionId, "/queue/results", requestId, result);
    }

    private void dispatch(StoredMessage message) {
        try {
            dispatcher.dispatch(message);
        } catch (RuntimeException error) {
            metrics.counter("chat.delivery.failures").increment();
            telemetry.failure("dispatch", error);
            log.atWarn()
                    .setCause(error)
                    .addKeyValue(EVENT, "dispatch.failed")
                    .addKeyValue(MESSAGE_ID, message.messageId())
                    .log("Message dispatch failed after acceptance");
        }
    }

    @MessageMapping("/v1/connection.info")
    public void info(@Header("simpSessionId") String sessionId, @Header("request-id") String requestId) {
        reply(sessionId, "/queue/connection", requestId, connections.view(sessionId));
    }

    @MessageExceptionHandler
    public void invalid(Exception error, Message<?> message) {
        var headers = StompHeaderAccessor.wrap(message);
        Throwable failure = error;
        if (error instanceof MessageConversionException || error instanceof MethodArgumentNotValidException) {
            failure = ChatFailure.invalid("Invalid request payload");
        }
        telemetry.failure("send", failure);
        if (!(failure instanceof ChatFailure)) {
            log.atError().setCause(error).addKeyValue(EVENT, "stomp.failed").log("STOMP operation failed");
        }
        reply(
                headers.getSessionId(),
                "/queue/results",
                headers.getFirstNativeHeader("request-id"),
                SendResult.failed(failure));
    }

    private void reply(String sessionId, String destination, String requestId, Object body) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setNativeHeader("request-id", requestId);
        headers.setLeaveMutable(true);
        // Address the session ID rather than the user so results never reach another device.
        try {
            messaging.convertAndSendToUser(sessionId, destination, body, headers.getMessageHeaders());
        } catch (RuntimeException error) {
            metrics.counter("chat.reply.failures").increment();
            telemetry.failure("reply", error);
            log.atWarn().setCause(error).addKeyValue(EVENT, "reply.failed").log("Session reply submission failed");
            // Retrying here could recurse through the message exception handler and cannot undo persistence.
        }
    }
}
