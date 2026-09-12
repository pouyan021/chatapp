package com.kutumlabs.chatapp.support;

import com.kutumlabs.chatapp.chat.ChatModels.MessageView;
import com.kutumlabs.chatapp.chat.ChatModels.SendCommand;
import com.kutumlabs.chatapp.socket.ConnectionRegistry.SessionView;
import com.kutumlabs.chatapp.socket.SendResult;
import java.lang.reflect.Type;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.tomcat.websocket.WsWebSocketContainer;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final WsWebSocketContainer container = new WsWebSocketContainer();
    private final ThreadPoolTaskExecutor connector = new ThreadPoolTaskExecutor();
    private final ThreadPoolTaskScheduler scheduler;
    private final WebSocketStompClient client;
    private final StompSession session;
    private StompSession.Subscription subscription;
    private final SessionView info;

    public TestStomp(URI uri, String jwt, String device) {
        this(uri, jwt, device, true, "http://localhost:3000");
    }

    public TestStomp(URI uri, String jwt, String device, boolean heartbeat, String origin) {
        scheduler = new ThreadPoolTaskScheduler() {
            @Override
            public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
                // Negotiate heartbeats normally, but simulate a silent peer when requested.
                return super.scheduleWithFixedDelay(
                        () -> {
                            if (heartbeat) task.run();
                        },
                        delay);
            }
        };
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("test-stomp-heartbeat-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(10);
        connector.setCorePoolSize(1);
        connector.setMaxPoolSize(1);
        connector.setQueueCapacity(0);
        connector.setThreadNamePrefix("test-stomp-connect-");
        connector.setAwaitTerminationSeconds(10);
        var socketClient = new StandardWebSocketClient(container);
        socketClient.setTaskExecutor(connector);
        client = new WebSocketStompClient(socketClient);
        client.setMessageConverter(new RawStringMessageConverter());
        client.setTaskScheduler(scheduler);
        client.setDefaultHeartbeat(new long[] {500, 500});
        var connect = new StompHeaders();
        if (jwt != null) connect.add("Authorization", "Bearer " + jwt);
        if (device != null) connect.add("device-id", device);
        var handshake = new WebSocketHttpHeaders();
        if (origin != null) handshake.setOrigin(origin);
        try {
            scheduler.initialize();
            connector.initialize();
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
            // Subscriptions and unsubscriptions are relayed to the broker asynchronously (unlike the old in-process
            // simple broker, where registering a subscription was synchronous and immediate) - without waiting for a
            // receipt, a send issued right after subscribing/unsubscribing can race the broker actually applying it.
            awaitReceipt(subscribe("/user/queue/results", replyHandler()));
            awaitReceipt(subscribe("/user/queue/connection", replyHandler()));
            subscribeMessages();
            info = request("/app/v1/connection.info", "", SessionView.class);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            try {
                close();
            } catch (RuntimeException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw new IllegalStateException("STOMP connection failed", error);
        }
    }

    private void failed(Throwable error) {
        failure.complete(error);
        pending.forEach(wait -> wait.completeExceptionally(error));
    }

    private StompFrameHandler replyHandler() {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                String id = headers.getFirst("request-id");
                if (id == null) return;
                var reply = replies.get(id);
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
            return mapper.readValue(awaitResult(future, "reply from " + destination), type);
        } catch (Exception error) {
            throw new IllegalStateException("STOMP request failed: " + destination, error);
        } finally {
            replies.remove(id);
        }
    }

    public void subscribeMessages() {
        awaitReceipt(subscribeMessagesWithoutWaiting());
    }

    /** Submit a subscription for tests that expect rejection instead of a receipt. */
    public StompSession.Subscription subscribeMessagesWithoutWaiting() {
        subscription = subscribe("/user/queue/messages", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add(mapper.readValue((String) payload, MessageView.class));
            }
        });
        return subscription;
    }

    public void unsubscribeMessages() {
        var headers = new StompHeaders();
        headers.setReceipt(UUID.randomUUID().toString());
        awaitReceipt(subscription.unsubscribe(headers));
        subscription = null;
    }

    private StompSession.Subscription subscribe(String destination, StompFrameHandler handler) {
        var headers = new StompHeaders();
        headers.setDestination(destination);
        headers.setReceipt(UUID.randomUUID().toString());
        return session.subscribe(headers, handler);
    }

    private void awaitReceipt(StompSession.Receiptable receiptable) {
        var receipt = new CompletableFuture<Void>();
        receiptable.addReceiptTask(() -> receipt.complete(null));
        receiptable.addReceiptLostTask(() -> receipt.completeExceptionally(new IllegalStateException("Receipt lost")));
        awaitResult(receipt, "receipt " + receiptable.getReceiptId());
    }

    private <T> T awaitResult(CompletableFuture<T> result, String description) {
        pending.add(result);
        try {
            Throwable error = failure.getNow(null);
            if (error != null) result.completeExceptionally(error);
            return result.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for STOMP " + description, error);
        } catch (ExecutionException | TimeoutException error) {
            throw new IllegalStateException("Failed waiting for STOMP " + description, error);
        } finally {
            pending.remove(result);
        }
    }

    public MessageView awaitMessage() {
        try {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            do {
                var message = messages.poll(20, TimeUnit.MILLISECONDS);
                if (message != null) return message;
                Throwable error = failure.getNow(null);
                if (error != null) throw new AssertionError("STOMP connection failed while waiting for message", error);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("No message received");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    public MessageView pollMessage() {
        return messages.poll();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        failed(new IllegalStateException("STOMP client closed"));
        RuntimeException failure = null;
        for (Runnable cleanup : List.<Runnable>of(
                () -> {
                    if (session != null && session.isConnected()) session.disconnect();
                },
                client::stop,
                connector::shutdown,
                container::destroy,
                scheduler::shutdown)) {
            try {
                cleanup.run();
            } catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
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
