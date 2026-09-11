package com.kutumlabs.chatapp.config;

import com.kutumlabs.chatapp.observability.ChatTelemetry;
import com.kutumlabs.chatapp.observability.StompObservations;
import com.kutumlabs.chatapp.socket.ConnectionRegistry;
import com.kutumlabs.chatapp.socket.StompAuthenticationInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocketMessageBroker
public class StompConfiguration implements WebSocketMessageBrokerConfigurer {
    private final StompObservations observations;
    private final ChatProperties properties;
    private final ConnectionRegistry connections;
    private final StompAuthenticationInterceptor authentication;

    public StompConfiguration(
            ChatProperties properties,
            ConnectionRegistry connections,
            StompAuthenticationInterceptor authentication,
            StompObservations observations) {
        this.observations = observations;
        this.properties = properties;
        this.connections = connections;
        this.authentication = authentication;
    }

    @Bean
    public SimpleAsyncTaskExecutor stompExecutor() {
        var executor = new SimpleAsyncTaskExecutor("stomp-");
        executor.setVirtualThreads(true);
        var propagation = new ContextPropagatingTaskDecorator();
        executor.setTaskDecorator(task -> {
            var captured = MDC.getCopyOfContextMap();
            var propagated = propagation.decorate(task);
            return () -> {
                var previous = MDC.getCopyOfContextMap();
                try {
                    ChatTelemetry.restore(captured);
                    propagated.run();
                } finally {
                    ChatTelemetry.restore(previous);
                }
            };
        });
        executor.setConcurrencyLimit(properties.socket().maxPendingMessages());
        executor.setRejectTasksWhenLimitReached(true);
        executor.setTaskTerminationTimeout(5000);
        return executor;
    }

    @Bean
    public ThreadPoolTaskScheduler stompScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(properties.socket().maxFrameBytes());
        container.setMaxBinaryMessageBufferSize(properties.socket().maxFrameBytes());
        container.setAsyncSendTimeout(properties.socket().sendTimeLimit().toMillis());
        return container;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setPreserveReceiveOrder(true);
        registry.addEndpoint("/ws/chat")
                .setAllowedOrigins(properties.security().allowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        long interval = properties.socket().heartbeatInterval().toMillis();
        registry.enableSimpleBroker("/queue")
                .setHeartbeatValue(new long[] {interval, interval})
                .setTaskScheduler(stompScheduler());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.executor(stompExecutor()).interceptors(authentication, observations);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.executor(stompExecutor());
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(properties.socket().maxFrameBytes())
                .setSendBufferSizeLimit(properties.socket().sendBufferBytes())
                .setSendTimeLimit(
                        Math.toIntExact(properties.socket().sendTimeLimit().toMillis()))
                .setTimeToFirstMessage(
                        Math.toIntExact(properties.socket().idleTimeout().toMillis()))
                .addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                        connections.opened(session);
                        try {
                            super.afterConnectionEstablished(session);
                        } catch (Exception error) {
                            connections.removed(session.getId());
                            throw error;
                        }
                    }

                    @Override
                    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                        try {
                            super.afterConnectionClosed(session, status);
                        } finally {
                            connections.removed(session.getId());
                        }
                    }
                });
    }
}
