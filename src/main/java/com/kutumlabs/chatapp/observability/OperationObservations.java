package com.kutumlabs.chatapp.observability;

import com.kutumlabs.chatapp.chat.ChatFailure;
import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** Observe public application boundaries; never capture method arguments or return values. */
@Aspect
@Component
public class OperationObservations {
    private final ChatTelemetry telemetry;
    private final MeterRegistry metrics;

    public OperationObservations(ChatTelemetry telemetry, MeterRegistry metrics) {
        this.telemetry = telemetry;
        this.metrics = metrics;
    }

    @Around("execution(public * com.kutumlabs.chatapp.chat.ChatStore+.*(..))")
    public Object database(ProceedingJoinPoint call) throws Throwable {
        return observe(call, "chat.persistence", "persistence");
    }

    @Around("execution(public * com.kutumlabs.chatapp.media.ObjectStorage+.*(..))")
    public Object storage(ProceedingJoinPoint call) throws Throwable {
        return observe(call, "chat.storage", "storage");
    }

    @Around(
            "execution(public * com.kutumlabs.chatapp.chat.ChatService.*(..)) || execution(public * com.kutumlabs.chatapp.media.MediaService.*(..))")
    public Object service(ProceedingJoinPoint call) throws Throwable {
        return observe(call, "chat.operation", null);
    }

    @Around("execution(public * com.kutumlabs.chatapp.socket.MessageDispatcher+.*(..))")
    public Object dispatch(ProceedingJoinPoint call) throws Throwable {
        return observe(call, "chat.dispatch", null);
    }

    private Object observe(ProceedingJoinPoint call, String name, String stage) throws Throwable {
        try (var scope = telemetry.start(name, call.getSignature().getName())) {
            try {
                return call.proceed();
            } catch (Throwable error) {
                scope.error(error);
                if (stage != null && !(error instanceof ChatFailure)) {
                    telemetry.failure(stage, error);
                    if (stage.equals("persistence"))
                        metrics.counter("chat.persistence.failures").increment();
                }
                throw error;
            }
        }
    }
}
