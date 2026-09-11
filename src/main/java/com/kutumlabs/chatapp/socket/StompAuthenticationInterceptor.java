package com.kutumlabs.chatapp.socket;

import com.kutumlabs.chatapp.chat.ChatModels;
import com.kutumlabs.chatapp.config.ChatProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

@Component
public class StompAuthenticationInterceptor implements ChannelInterceptor {
    private static final Set<String> SUBSCRIPTIONS =
            Set.of("/user/queue/messages", "/user/queue/results", "/user/queue/connection");
    private static final Set<String> SENDS = Set.of("/app/v1/message.send", "/app/v1/connection.info");
    private final MeterRegistry metrics;
    private final JwtDecoder decoder;
    private final ConnectionRegistry connections;
    private final ChatProperties properties;
    private final Clock clock;

    public StompAuthenticationInterceptor(
            JwtDecoder decoder,
            ConnectionRegistry connections,
            ChatProperties properties,
            Clock clock,
            MeterRegistry metrics) {
        this.metrics = metrics;
        this.decoder = decoder;
        this.connections = connections;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        try {
            return authenticate(message);
        } catch (RuntimeException error) {
            var command = StompHeaderAccessor.wrap(message).getCommand();
            String stage =
                    command == StompCommand.CONNECT || command == StompCommand.STOMP ? "authentication" : "protocol";
            metrics.counter("chat.stomp.rejected", "stage", stage).increment();
            throw error;
        }
    }

    private Message<?> authenticate(Message<?> message) {
        var headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null) throw new IllegalArgumentException("STOMP required");
        String sessionId = headers.getSessionId();
        StompCommand command = headers.getCommand();
        // Framework-generated disconnects must remain valid after transport cleanup.
        switch (command) {
            case DISCONNECT -> {
                return message;
            }
            case CONNECT, STOMP -> {
                String authorization = single(headers, "Authorization");
                String device = single(headers, "device-id");
                if (authorization == null
                        || !authorization.startsWith("Bearer ")
                        || device == null
                        || !device.matches("[A-Za-z0-9._-]{1,128}"))
                    throw new IllegalArgumentException("Invalid credentials or device");
                long[] heartbeat = headers.getHeartbeat();
                long interval = properties.socket().heartbeatInterval().toMillis();
                if (heartbeat[0] <= 0 || heartbeat[1] <= 0 || heartbeat[0] > interval || heartbeat[1] > interval)
                    throw new IllegalArgumentException("Heartbeats are required within server limits");
                var jwt = decoder.decode(authorization.substring(7));
                if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant()))
                    throw new IllegalArgumentException("Authentication expired");
                var identity =
                        new SessionIdentity(ChatModels.id(jwt.getClaimAsString("user_id")), device, jwt.getExpiresAt());
                connections.authenticate(sessionId, identity);
                headers.setUser(UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of()));
                headers.removeNativeHeader("Authorization");
                return message;
            }
            case null, default -> {}
        }
        connections.identity(sessionId);
        connections.seen(sessionId);
        try {
            if (headers.getNativeHeader("Authorization") != null || headers.getNativeHeader("device-id") != null) {
                throw new IllegalArgumentException("Credentials are only allowed on CONNECT");
            }
            if (command == null) return message; // STOMP heartbeat
            switch (command) {
                case SEND -> {
                    if (!SENDS.contains(headers.getDestination()))
                        throw new IllegalArgumentException("Destination not allowed");
                    String requestId = single(headers, "request-id");
                    if (requestId == null || requestId.isBlank() || requestId.length() > 128)
                        throw new IllegalArgumentException("Invalid request-id");
                }
                case SUBSCRIBE -> {
                    if (!SUBSCRIPTIONS.contains(headers.getDestination()))
                        throw new IllegalArgumentException("Subscription not allowed");
                    String ack = headers.getFirstNativeHeader("ack");
                    if (ack != null && !ack.equals("auto"))
                        throw new IllegalArgumentException("Only auto acknowledgement is supported");
                    connections.subscribe(sessionId, headers.getSubscriptionId(), headers.getDestination());
                }
                case UNSUBSCRIBE -> connections.unsubscribe(sessionId, headers.getSubscriptionId());
                default -> throw new IllegalArgumentException("Unsupported STOMP command");
            }
            return message;
        } catch (RuntimeException error) {
            // A STOMP ERROR frame alone doesn't close the transport; close it explicitly so a
            // misbehaving client can't keep sending on a connection that violated the protocol.
            connections.close(sessionId, CloseStatus.POLICY_VIOLATION);
            throw error;
        }
    }

    private static String single(StompHeaderAccessor headers, String name) {
        var values = headers.getNativeHeader(name);
        if (values == null) return null;
        if (values.size() != 1) throw new IllegalArgumentException("Duplicate " + name);
        return values.getFirst();
    }
}
