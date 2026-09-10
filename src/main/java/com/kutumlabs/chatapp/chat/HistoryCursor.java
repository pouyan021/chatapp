package com.kutumlabs.chatapp.chat;

import com.github.f4b6a3.ulid.Ulid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class HistoryCursor {
    private final byte[] key = new byte[32];

    public HistoryCursor() {
        new SecureRandom().nextBytes(key);
    }

    public String encode(Ulid chat, int size, byte[] state) {
        if (state == null) return null;
        String payload = chat + ":" + size + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(state);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }

    public byte[] decode(Ulid chat, int size, String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 8192) throw new IllegalArgumentException();
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(sign(payload), Base64.getUrlDecoder().decode(parts[1]))) {
                throw new IllegalArgumentException();
            }
            String prefix = chat + ":" + size + ":";
            if (!payload.startsWith(prefix)) throw new IllegalArgumentException();
            return Base64.getUrlDecoder().decode(payload.substring(prefix.length()));
        } catch (IllegalArgumentException e) {
            throw ChatFailure.invalid("Invalid or expired history cursor; restart pagination");
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
