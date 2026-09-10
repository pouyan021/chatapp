package com.kutumlabs.chatapp.dev;

import com.kutumlabs.chatapp.chat.ChatFailure;
import com.kutumlabs.chatapp.config.ChatProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("dev")
@RequestMapping("/dev")
public class DevController {
    public static final Map<String, String> USERS =
            Map.of("alice", "01ARZ3NDEKTSV4RRFFQ69G5FAY", "bob", "01ARZ3NDEKTSV4RRFFQ69G5FAZ");
    private final RSAKey key;
    private final ChatProperties properties;
    private final Clock clock;

    public DevController(ChatProperties properties, Clock clock) throws Exception {
        this.properties = properties;
        this.clock = clock;
        this.key = new RSAKeyGenerator(2048)
                .keyID("dev-" + java.util.UUID.randomUUID())
                .generate();
    }

    public record TokenRequest(String identity) {}

    public record TokenResponse(String token, Instant expiresAt, String userId) {}

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(@RequestBody TokenRequest request) throws Exception {
        String userId = request.identity() == null ? null : USERS.get(request.identity());
        if (userId == null) throw ChatFailure.invalid("Choose alice or bob");
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(3600);
        var claims = new JWTClaimsSet.Builder()
                .issuer(properties.security().issuer())
                .subject(request.identity())
                .audience(properties.security().audience())
                .claim("user_id", userId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TokenResponse(jwt.serialize(), expiresAt, userId));
    }

    @GetMapping("/jwks")
    public Map<String, Object> jwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }

    @GetMapping(value = "/chat", produces = "text/html")
    public Resource playground() {
        return new ClassPathResource("dev-assets/index.html");
    }

    @GetMapping(value = "/protocol", produces = "text/plain")
    public Resource protocol() {
        return new ClassPathResource("dev-assets/stomp.md");
    }
}
