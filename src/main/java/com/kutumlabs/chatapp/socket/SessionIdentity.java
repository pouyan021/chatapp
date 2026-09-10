package com.kutumlabs.chatapp.socket;

import com.github.f4b6a3.ulid.Ulid;
import java.security.Principal;
import java.time.Instant;

public record SessionIdentity(Ulid userId, String deviceId, Instant expiresAt) implements Principal {
    @Override
    public String getName() {
        return userId.toString();
    }
}
