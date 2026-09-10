package com.kutumlabs.chatapp.support;

import com.kutumlabs.chatapp.config.ChatProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.Duration;
import java.util.List;

public final class TestSettings {
    private TestSettings() {}

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    public static Validator validator() {
        return VALIDATOR;
    }

    public static ChatProperties properties(int queueSize) {
        return new ChatProperties(
                new ChatProperties.Security(
                        "https://issuer.test", "https://issuer.test/jwks", "chatapp", List.of("http://localhost:3000")),
                new ChatProperties.Socket(
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(90),
                        queueSize,
                        524288,
                        Duration.ofSeconds(10),
                        65536,
                        8192),
                new ChatProperties.Storage(
                        URI.create("http://localhost:9000"),
                        URI.create("http://localhost:9000"),
                        "us-east-1",
                        "chat-media",
                        "test",
                        "test-secret",
                        26214400,
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(15),
                        Duration.ofHours(24)));
    }
}
