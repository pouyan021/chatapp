package com.kutumlabs.chatapp.support;

import com.github.f4b6a3.ulid.Ulid;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

public final class TestJwtIssuer implements AutoCloseable {
    private final RSAKey key;
    private final HttpServer server;

    public TestJwtIssuer() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(jwks);
                }
            });
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String jwks() {
        return issuer() + "/jwks";
    }

    public String token(Ulid user) {
        return token(user.toString(), "chatapp", issuer(), Duration.ofMinutes(5));
    }

    public String token(String userId, String audience, String issuer, Duration lifetime) {
        try {
            var claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject("external-user")
                    .audience(audience)
                    .claim("user_id", userId)
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plus(lifetime)))
                    .build();
            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(key.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
