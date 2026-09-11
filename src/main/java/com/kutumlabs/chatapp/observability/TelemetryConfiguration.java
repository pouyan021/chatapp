package com.kutumlabs.chatapp.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class TelemetryConfiguration {
    @Bean
    @Profile("observability")
    InitializingBean telemetryLogging(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }

    @Bean
    @Order(0)
    @Profile("observability")
    SecurityFilterChain managementSecurity(HttpSecurity http, Environment environment) throws Exception {
        int port = environment.getProperty("management.server.port", Integer.class, 8081);
        return http.securityMatcher(request -> request.getLocalPort() == port
                        && request.getRequestURI().startsWith("/actuator"))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/prometheus")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .build();
    }
}
