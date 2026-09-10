package com.kutumlabs.chatapp.config;

import com.kutumlabs.chatapp.chat.ChatModels;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ChatProperties.class)
public class SecurityConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtDecoder jwtDecoder(ChatProperties properties) {
        var settings = properties.security();
        var decoder = NimbusJwtDecoder.withJwkSetUri(settings.jwkSetUri()).build();
        OAuth2TokenValidator<Jwt> identity = jwt -> {
            try {
                ChatModels.id(jwt.getClaimAsString("user_id"));
                if (jwt.getExpiresAt() == null
                        || !Objects.requireNonNull(jwt.getAudience()).contains(settings.audience())) {
                    throw new IllegalArgumentException();
                }
                return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException e) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Required user_id, expiration or audience is invalid", null));
            }
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(settings.issuer()), identity));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ChatProperties properties, Environment environment) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.security().allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return http.csrf(AbstractHttpConfigurer::disable)
                .cors(spec -> spec.configurationSource(source))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    if (environment.matchesProfiles("dev")) {
                        auth.requestMatchers(
                                        HttpMethod.GET,
                                        "/dev/chat",
                                        "/dev/assets/**",
                                        "/dev/jwks",
                                        "/dev/protocol",
                                        "/swagger-ui.html",
                                        "/swagger-ui/**",
                                        "/v3/api-docs",
                                        "/v3/api-docs/**")
                                .permitAll()
                                .requestMatchers(HttpMethod.POST, "/dev/token")
                                .permitAll();
                    }
                    auth.requestMatchers(HttpMethod.GET, "/ws/chat", "/actuator/health", "/actuator/health/**")
                            .permitAll()
                            .requestMatchers("/api/**")
                            .authenticated()
                            .anyRequest()
                            .denyAll();
                })
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }
}
