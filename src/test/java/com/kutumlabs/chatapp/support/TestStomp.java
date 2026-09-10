package com.kutumlabs.chatapp.support;

import com.kutumlabs.chatapp.chat.ChatModels.MessageView;
import com.kutumlabs.chatapp.chat.ChatModels.SendCommand;
import com.kutumlabs.chatapp.socket.ConnectionRegistry.SessionView;
import com.kutumlabs.chatapp.socket.SendResult;
import java.lang.reflect.Type;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.json.JsonMapper;

public final class TestStomp implements AutoCloseable {
    public static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, CompletableFuture<String>> replies = new ConcurrentHashMap<>();
    private final BlockingQueue<MessageView> messages = new LinkedBlockingQueue<>();
    private final CompletableFuture<Throwable> failure = new CompletableFuture<>();
    private final ThreadPoolTaskScheduler scheduler;
    private final WebSocketStompClient client;
    private StompSession session;
    private StompSession.Subscription subscription;
    private SessionView info;

    public TestStomp(URI uri, String jwt, String device) {
        this(uri, jwt, device, true, "http://localhost:3000");
    }

    public TestStomp(URI uri, String jwt, String device, boolean heartbeat, String origin) {
        scheduler = new ThreadPoolTaskScheduler() {
            @Override
            public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
                return heartbeat
                        ? super.scheduleWithFixedDelay(task, delay)
                        : super.schedule(task, Instant.now().plusSeconds(60));
            }
        };
        scheduler.setPoolSize(1);
        scheduler.initialize();
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new RawStringMessageConverter());
        client.setTaskScheduler(scheduler);
        client.setDefaultHeartbeat(new long[] {500, 500});
        var connect = new StompHeaders();
        if (jwt != null) connect.add("Authorization", "Bearer " + jwt);
        if (device != null) connect.add("device-id", device);
        var handshake = new WebSocketHttpHeaders();
        if (origin != null) handshake.setOrigin(origin);
        try {
            session = client.connectAsync(uri, handshake, connect, new StompSessionHandlerAdapter() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return String.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            failed(new IllegalStateException("STOMP ERROR: " + payload));
                        }

                        @Override
                        public void handleTransportError(StompSession s, Throwable error) {
                            failed(error);
                        }

                        @Override
                        public void handleException(
                                StompSession s, StompCommand c, StompHeaders h, byte[] p, Throwable error) {
                            failed(error);
                        }
                    })
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            session.subscribe("/user/queue/results", replyHandler());
            session.subscribe("/user/queue/connection", replyHandler());
            subscribeMessages();
            info = request("/app/v1/connection.info", "", SessionView.class);
        } catch (Exception error) {
            close();
            throw new IllegalStateException("STOMP connection failed", error);
        }
    }

    private void failed(Throwable error) {
        failure.complete(error);
        replies.values().forEach(reply -> reply.completeExceptionally(error));
    }

    private StompFrameHandler replyHandler() {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                var reply = replies.get(headers.getFirst("request-id"));
                if (reply != null) reply.complete((String) payload);
            }
        };
    }

    public SessionView session() {
        return info;
    }

    public StompSession stomp() {
        return session;
    }

    public SendResult send(SendCommand command) {
        return request("/app/v1/message.send", command, SendResult.class);
    }

    public <T> T request(String destination, Object body, Class<T> type) {
        String id = UUID.randomUUID().toString();
        var future = new CompletableFuture<String>();
        replies.put(id, future);
        var headers = new StompHeaders();
        headers.setDestination(destination);
        headers.add("request-id", id);
        headers.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
        try {
            session.send(headers, body instanceof String s ? s : mapper.writeValueAsString(body));
            return mapper.readValue(future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), type);
        } catch (Exception error) {
            throw new IllegalStateException("STOMP request failed", error);
        } finally {
            replies.remove(id);
        }
    }

    public void subscribeMessages() {
        subscription = session.subscribe("/user/queue/messages", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add(mapper.readValue((String) payload, MessageView.class));
            }
        });
    }

    public void unsubscribeMessages() {
        subscription.unsubscribe();
    }

    public MessageView awaitMessage() {
        try {
            var message = messages.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (message == null) throw new AssertionError("No message received");
            return message;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    public MessageView pollMessage() {
        return messages.poll();
    }

    public void awaitClosed() {
        await(() -> !session.isConnected());
    }

    public static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Condition did not become true");
            try {
                Thread.sleep(20);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
        }
    }

    @Override
    public void close() {
        if (session != null && session.isConnected()) session.disconnect();
        client.stop();
        scheduler.shutdown();
    }

    /** Decodes every frame as UTF-8 text regardless of the server's declared content-type (e.g. application/json),
     * so tests can parse the raw JSON payload themselves instead of going through a second Jackson conversion. */
    private static final class RawStringMessageConverter extends StringMessageConverter {
        @Override
        protected boolean supportsMimeType(MessageHeaders headers) {
            return true;
        }
    }
}
