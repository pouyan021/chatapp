package com.kutumlabs.chatapp.observability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kutumlabs.chatapp.config.StompConfiguration;
import com.kutumlabs.chatapp.socket.ConnectionRegistry;
import com.kutumlabs.chatapp.socket.StompAuthenticationInterceptor;
import com.kutumlabs.chatapp.support.TestSettings;
import io.micrometer.context.ContextRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class StompContextTests {
    @Test
    void executorPropagatesObservationAndRestoresMdcAfterFailure()
            throws InterruptedException, ExecutionException, TimeoutException {
        var metrics = new SimpleMeterRegistry();
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(metrics));
        // Boot normally registers this accessor against its application registry.
        var contexts = ContextRegistry.getInstance();
        var previousAccessor = contexts.getThreadLocalAccessors().stream()
                .filter(a -> a.key().equals(ObservationThreadLocalAccessor.KEY))
                .findFirst();
        var accessor = new ObservationThreadLocalAccessor(registry);
        contexts.registerThreadLocalAccessor(accessor);
        var telemetry = new ChatTelemetry(registry, metrics);
        var configuration = new StompConfiguration(
                TestSettings.properties(16),
                mock(ConnectionRegistry.class),
                mock(StompAuthenticationInterceptor.class),
                new StompObservations(telemetry));
        try (var executor = configuration.stompExecutor()) {
            try (var _ = telemetry.start("chat.stomp", "message.send").correlation("session", "request")) {
                var parent = registry.getCurrentObservation();
                executor.submit(() -> {
                            assertThat(registry.getCurrentObservation()).isSameAs(parent);
                            assertThat(MDC.get("requestId")).isEqualTo("request");
                        })
                        .get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> executor.submit(() -> {
                                    MDC.put("requestId", "worker-only");
                                    throw new IllegalStateException("test failure");
                                })
                                .get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(IllegalStateException.class);
                assertThat(MDC.get("requestId")).isEqualTo("request");
            }
            executor.submit(() -> {
                        assertThat(registry.getCurrentObservation()).isNull();
                        assertThat(MDC.get("requestId")).isNull();
                    })
                    .get(5, TimeUnit.SECONDS);
        } finally {
            contexts.removeThreadLocalAccessor(ObservationThreadLocalAccessor.KEY);
            previousAccessor.ifPresent(contexts::registerThreadLocalAccessor);
            MDC.clear();
            metrics.close();
        }
    }

    @Test
    void onlyApplicationHandlersCountCommandsAndScopesCloseOnFailure() {
        var metrics = new SimpleMeterRegistry();
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(metrics));
        var interceptor = new StompObservations(new ChatTelemetry(registry, metrics));
        var headers = StompHeaderAccessor.create(StompCommand.SEND);
        headers.setDestination("/app/v1/message.send");
        headers.setSessionId("session");
        headers.setNativeHeader("request-id", "request");
        Message<?> message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        var ignored = mock(MessageHandler.class);
        interceptor.beforeHandle(message, null, ignored);
        interceptor.afterMessageHandled(message, null, ignored, null);
        assertThat(metrics.find("chat.stomp").timer()).isNull();
        var application = mock(SimpAnnotationMethodMessageHandler.class);
        interceptor.beforeHandle(message, null, application);
        assertThat(MDC.get("requestId")).isEqualTo("request");
        interceptor.afterMessageHandled(message, null, application, new IllegalStateException("test"));
        assertThat(metrics.get("chat.stomp").tag("outcome", "error").timer().count())
                .isOne();
        assertThat(registry.getCurrentObservation()).isNull();
        assertThat(MDC.get("requestId")).isNull();
        metrics.close();
    }
}
