package com.kutumlabs.chatapp.observability;

import com.kutumlabs.chatapp.chat.ChatFailure;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class ChatTelemetry {
    private static final Set<String> CODES =
            Set.of("INVALID_REQUEST", "FORBIDDEN", "NOT_FOUND", "CONFLICT", "TEMPORARILY_UNAVAILABLE");
    private static final Set<String> STAGES =
            Set.of("send", "persistence", "storage", "dispatch", "reply", "connection", "authentication", "protocol");
    private final ObservationRegistry observations;
    private final MeterRegistry metrics;

    public ChatTelemetry(ObservationRegistry observations, MeterRegistry metrics) {
        this.observations = observations;
        this.metrics = metrics;
    }

    public Scope start(String name, String operation) {
        return new Scope(Observation.createNotStarted(name, observations)
                .lowCardinalityKeyValue("operation", operation)
                .lowCardinalityKeyValue("outcome", "success")
                .lowCardinalityKeyValue("code", "NONE")
                .start());
    }

    public void failure(String stage, Throwable error) {
        metrics.counter("chat.failures", "stage", STAGES.contains(stage) ? stage : "other", "code", code(error))
                .increment();
        var current = observations.getCurrentObservation();
        if (current != null) markFailure(current, error);
    }

    public static String code(Throwable error) {
        return error instanceof ChatFailure failure && CODES.contains(failure.code())
                ? failure.code()
                : "TEMPORARILY_UNAVAILABLE";
    }

    public static String safe(String value) {
        if (value == null) return "unknown";
        return value.substring(0, Math.min(128, value.length())).replaceAll("[^A-Za-z0-9._:-]", "_");
    }

    public static void restore(Map<String, String> context) {
        if (context == null) MDC.clear();
        else MDC.setContextMap(context);
    }

    private static void markFailure(Observation observation, Throwable error) {
        observation
                .lowCardinalityKeyValue("outcome", error instanceof ChatFailure ? "rejected" : "error")
                .lowCardinalityKeyValue("code", code(error));
        // Expected business failures need an outcome, not exception details that may contain input.
        if (!(error instanceof ChatFailure)) observation.error(error);
    }

    public static final class Scope implements AutoCloseable {
        private final Observation observation;
        private final Observation.Scope scope;
        private final Map<String, String> previous;

        private Scope(Observation observation) {
            this.observation = observation;
            previous = MDC.getCopyOfContextMap();
            scope = observation.openScope();
        }

        public Scope correlation(String sessionId, String requestId) {
            MDC.put("sessionId", safe(sessionId));
            MDC.put("requestId", safe(requestId));
            observation.highCardinalityKeyValue("session.id", safe(sessionId));
            observation.highCardinalityKeyValue("request.id", safe(requestId));
            return this;
        }

        public void error(Throwable error) {
            markFailure(observation, error);
        }

        @Override
        public void close() {
            try {
                scope.close();
            } finally {
                try {
                    observation.stop();
                } finally {
                    restore(previous);
                }
            }
        }
    }
}
