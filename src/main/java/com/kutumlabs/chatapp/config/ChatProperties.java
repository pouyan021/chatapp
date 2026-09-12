package com.kutumlabs.chatapp.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chat")
public record ChatProperties(Security security, Socket socket, Broker broker, Storage storage) {
    public record Security(String issuer, String jwkSetUri, String audience, List<String> allowedOrigins) {}

    public record Socket(
            Duration heartbeatInterval,
            Duration idleTimeout,
            int maxPendingMessages,
            int sendBufferBytes,
            Duration sendTimeLimit,
            int maxFrameBytes,
            int maxTextBytes) {}

    public record Broker(String host, int port, String login, String passcode) {}

    public record Storage(
            URI endpoint,
            URI publicEndpoint,
            String region,
            String bucket,
            String accessKey,
            String secretKey,
            long maxUploadBytes,
            Duration uploadUrlTtl,
            Duration downloadUrlTtl,
            Duration intentTtl) {}
}
