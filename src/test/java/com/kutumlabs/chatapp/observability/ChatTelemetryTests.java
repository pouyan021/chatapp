package com.kutumlabs.chatapp.observability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kutumlabs.chatapp.chat.ChatFailure;
import com.kutumlabs.chatapp.chat.ChatStore;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

class ChatTelemetryTests {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ObservationRegistry registry = ObservationRegistry.create();
    private final ChatTelemetry telemetry = new ChatTelemetry(registry, metrics);

    ChatTelemetryTests() {
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(metrics));
    }

    @AfterEach
    void cleanup() {
        MDC.clear();
        metrics.close();
    }

    @Test
    void nestedScopesRestoreContextAndKeepIdentifiersOutOfMetrics() {
        MDC.put("existing", "parent");
        try (var outer = telemetry.start("chat.stomp", "message.send").correlation("session", "line\nbreak")) {
            assertThat(MDC.get("requestId")).isEqualTo("line_break");
            var parent = registry.getCurrentObservation();
            try (var inner = telemetry.start("chat.operation", "send").correlation("other", "request")) {
                inner.error(ChatFailure.forbidden());
            }
            assertThat(registry.getCurrentObservation()).isSameAs(parent);
            assertThat(MDC.get("sessionId")).isEqualTo("session");
        }
        assertThat(registry.getCurrentObservation()).isNull();
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(Map.of("existing", "parent"));
        assertThat(metrics.get("chat.operation")
                        .tag("outcome", "rejected")
                        .tag("code", "FORBIDDEN")
                        .timer()
                        .count())
                .isOne();
        metrics.getMeters().forEach(meter -> assertThat(meter.getId().getTags())
                .noneMatch(tag -> tag.getKey().contains("id") || tag.getKey().equals("requestId")));
    }

    @Test
    void persistenceBoundaryCountsFailureExactlyOnceAndRestoresContext() {
        var target = mock(ChatStore.class);
        when(target.participants(any())).thenThrow(new IllegalStateException("database unavailable"));
        var factory = new AspectJProxyFactory(target);
        factory.addAspect(new OperationObservations(telemetry, metrics));
        ChatStore proxy = factory.getProxy();
        assertThatThrownBy(() -> proxy.participants(null)).isInstanceOf(IllegalStateException.class);
        assertThat(metrics.get("chat.persistence.failures").counter().count()).isEqualTo(1);
        assertThat(metrics.get("chat.failures")
                        .tag("stage", "persistence")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(metrics.get("chat.persistence")
                        .tag("operation", "participants")
                        .tag("outcome", "error")
                        .timer()
                        .count())
                .isOne();
        assertThat(registry.getCurrentObservation()).isNull();
    }

    @Test
    void unknownFailureLabelsAreBounded() {
        telemetry.failure(
                "user-supplied-stage",
                new ChatFailure(
                        "user-supplied-code", org.springframework.http.HttpStatus.BAD_REQUEST, "private", false));
        assertThat(metrics.get("chat.failures")
                        .tag("stage", "other")
                        .tag("code", "TEMPORARILY_UNAVAILABLE")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(ChatTelemetry.safe("x".repeat(200))).hasSize(128);
    }
}
