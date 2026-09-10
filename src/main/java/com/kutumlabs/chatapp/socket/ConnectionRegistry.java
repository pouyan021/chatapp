package com.kutumlabs.chatapp.socket;

import com.github.f4b6a3.ulid.Ulid;
import com.kutumlabs.chatapp.chat.ChatStore;
import com.kutumlabs.chatapp.config.ChatProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Component
public class ConnectionRegistry {
    public record SessionView(
            String sessionId, String deviceId, Instant connectedAt, Instant lastSeenAt, Instant expiresAt) {}

    private static final class Connection {
        final WebSocketSession socket;
        final Instant connectedAt;
        final ConcurrentHashMap<String, String> subscriptions = new ConcurrentHashMap<>();
        volatile SessionIdentity identity;
        volatile Instant lastSeenAt;

        Connection(WebSocketSession socket, Instant now) {
            this.socket = socket;
            connectedAt = now;
            lastSeenAt = now;
        }
    }

    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final ChatStore store;
    private final Clock clock;
    private final ChatProperties properties;
    private final MeterRegistry metrics;

    public ConnectionRegistry(ChatStore store, Clock clock, ChatProperties properties, MeterRegistry metrics) {
        this.store = store;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
        metrics.gauge("chat.connections.active", active);
    }

    public void opened(WebSocketSession socket) {
        connections.put(socket.getId(), new Connection(socket, clock.instant()));
    }

    public void authenticate(String sessionId, SessionIdentity identity) {
        Connection connection = required(sessionId);
        synchronized (connection) {
            if (connection.identity != null) throw new IllegalArgumentException("Already connected");
            connection.identity = identity;
            active.incrementAndGet();
        }
        touch(identity);
    }

    public SessionIdentity identity(String sessionId) {
        Connection connection = required(sessionId);
        SessionIdentity identity = connection.identity;
        if (identity == null || !identity.expiresAt().isAfter(clock.instant())) {
            close(sessionId, CloseStatus.POLICY_VIOLATION);
            throw new IllegalArgumentException("Authentication required or expired");
        }
        return identity;
    }

    public void seen(String sessionId) {
        required(sessionId).lastSeenAt = clock.instant();
    }

    public void subscribe(String sessionId, String id, String destination) {
        Connection connection = required(sessionId);
        synchronized (connection) {
            if (id == null
                    || id.isBlank()
                    || connection.subscriptions.containsKey(id)
                    || connection.subscriptions.containsValue(destination))
                throw new IllegalArgumentException("Duplicate or invalid subscription");
            connection.subscriptions.put(id, destination);
        }
    }

    public void unsubscribe(String sessionId, String id) {
        if (id == null || required(sessionId).subscriptions.remove(id) == null)
            throw new IllegalArgumentException("Unknown subscription");
    }

    public SessionView view(String sessionId) {
        var connection = required(sessionId);
        var identity = identity(sessionId);
        return new SessionView(
                sessionId, identity.deviceId(), connection.connectedAt, connection.lastSeenAt, identity.expiresAt());
    }

    public List<SessionView> list(Ulid user) {
        return connections.values().stream()
                .filter(c -> c.identity != null && c.identity.userId().equals(user))
                .map(c -> new SessionView(
                        c.socket.getId(), c.identity.deviceId(), c.connectedAt, c.lastSeenAt, c.identity.expiresAt()))
                .toList();
    }

    public boolean disconnect(Ulid user, String sessionId) {
        var connection = connections.get(sessionId);
        if (connection == null
                || connection.identity == null
                || !connection.identity.userId().equals(user)) return false;
        close(sessionId, CloseStatus.NORMAL);
        return true;
    }

    public void close(String sessionId, CloseStatus status) {
        var connection = connections.get(sessionId);
        if (connection == null) return;
        try {
            connection.socket.close(status);
        } catch (IOException error) {
            metrics.counter("chat.delivery.failures").increment();
        } finally {
            removed(sessionId);
        }
    }

    public void removed(String sessionId) {
        var connection = connections.remove(sessionId);
        if (connection != null) {
            synchronized (connection) {
                if (connection.identity != null) {
                    active.decrementAndGet();
                    touch(connection.identity);
                }
            }
        }
    }

    @Scheduled(fixedDelay = 500)
    public void expire() {
        Instant now = clock.instant();
        connections.forEach((id, c) -> {
            if (c.identity != null && !c.identity.expiresAt().isAfter(now)) close(id, CloseStatus.POLICY_VIOLATION);
            else if (!c.lastSeenAt.plus(properties.socket().idleTimeout()).isAfter(now))
                close(id, CloseStatus.GOING_AWAY);
        });
    }

    @PreDestroy
    public void shutdown() {
        connections.keySet().forEach(id -> close(id, CloseStatus.GOING_AWAY));
    }

    private Connection required(String id) {
        Connection connection = connections.get(id);
        if (connection == null) throw new IllegalArgumentException("Connection closed");
        return connection;
    }

    private void touch(SessionIdentity identity) {
        try {
            store.touchDevice(identity.userId(), identity.deviceId(), clock.instant());
        } catch (RuntimeException error) {
            metrics.counter("chat.persistence.failures").increment();
        }
    }
}
